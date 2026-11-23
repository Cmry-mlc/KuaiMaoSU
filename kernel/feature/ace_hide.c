// SPDX-License-Identifier: GPL-2.0
/*
 * ace_hide.c — 内核态隐藏（编入 kernelsu.ko，不依赖 eBPF / 不依赖 SUSFS）
 *
 * 与随包 eBPF 方案的区别：这里跑在 sys_call_table 调度器里，可以直接决定
 * syscall 的返回值，因此能做到 eBPF 在当前设备上做不到的两件事：
 *   1) stat 系列对隐藏路径返回 -ENOENT（eBPF 无 BPF_KPROBE_OVERRIDE，改不了返回值）
 *   2) 目录项过滤与 stat 判定共用同一张关键字表，不会出现 getdents 有 ino、
 *      stat 却查不到的名实不符（旧方案里 dirent 与 kstat 两套表不同步的根因）
 *
 * 控制接口：/proc/kuaimao_hide（0600，root）
 *   echo on                    启用
 *   echo off                   关闭（立即解除全部干预）
 *   echo kw=ksu,susfs,kernelsu 覆盖关键字表
 *   echo kw+=kpm               追加关键字
 *   echo files=/proc/modules   覆盖整文件屏蔽表
 *   cat                        查看状态与命中计数
 *
 * 隐藏判定是"名字级"的：目录项按关键字子串匹配，stat 按路径最后一段匹配，
 * 这样同一条规则可以同时覆盖 /data/adb/ksu、/sys/module/kernelsu、
 * /data/adb/modules 下以 _susfs 结尾的目录等多处，不需要维护易漏的路径白名单。
 */

#include <linux/fs.h>
#include <linux/file.h>
#include <linux/dcache.h>
#include <linux/init.h>
#include <linux/kernel.h>
#include <linux/magic.h>
#include <linux/mm.h>
#include <linux/namei.h>
#include <linux/proc_fs.h>
#include <linux/sched.h>
#include <linux/sched/signal.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#include <linux/version.h>
#include <linux/vmalloc.h>
#include <linux/workqueue.h>

#include "arch.h"
#include "klog.h" // IWYU pragma: keep
#include "feature/ace_hide.h"
#include "hook/syscall_hook.h"
#include "feature/ace_lsm.h"
#include "feature/ace_lsm.h"
#include "hook/syscall_hook.h"
#include "hook/tp_marker.h"
#include "manager/manager_identity.h"
#include "policy/allowlist.h"

#define ACE_TAG "ace_hide"

/*
 * 隐藏模块协议版本。改动控制节点语法或 hook 覆盖范围时 +1，
 * 管理器据此判断内核里跑的是不是自己配套的那份 ko。
 * （装新 APK 不会替换已加载的 ko，late-load 见到 has_kernelsu 直接跳过。）
 */
#define ACE_VER 11

#define ACE_MAX_FILES 8
#define ACE_FILE_LEN 64

/* 目录项结构（内核未导出该定义，自己声明一份，布局与 fs/readdir.c 一致） */
struct ace_dirent64 {
	u64 d_ino;
	s64 d_off;
	unsigned short d_reclen;
	unsigned char d_type;
	char d_name[];
};

static bool ace_on;
static bool ace_dents_on;
static bool ace_files_on;
static bool ace_strict;
static bool ace_procs_on;    /* /proc/<pid> 目录项 + /proc/<pid>/exe 一类链接 */
static bool ace_maps_on;     /* /proc/<pid>/maps 行擦除 */
static bool ace_kallsyms_on; /* /proc/kallsyms 行擦除 */
static bool ace_log_on;      /* 默认关：dmesg 里出现 "ace_hide: enabled" 本身就是泄漏 */
/* 上一次写入里无法识别的行数：用于定位协议不一致，不参与判定 */
static int ace_last_unknown;

/*
 * 日志默认静默。检测器 `dmesg | grep -i ace` 能直接看到我们的痕迹，
 * 所以除了致命错误，所有信息级输出都受 log=on 控制。
 */
#define ace_log(fmt, ...)                                        \
	do {                                                     \
		if (READ_ONCE(ace_log_on))                       \
			pr_info(ACE_TAG ": " fmt, ##__VA_ARGS__); \
	} while (0)
static char ace_keywords[ACE_HIDE_MAX_KW][ACE_HIDE_KW_LEN];
static int ace_kw_count;
static char ace_files[ACE_MAX_FILES][ACE_FILE_LEN];
static int ace_file_count;

static DEFINE_RWLOCK(ace_cfg_lock);

static atomic64_t n_dents_hidden = ATOMIC64_INIT(0);
static atomic64_t n_stat_blocked = ATOMIC64_INIT(0);
static atomic64_t n_open_blocked = ATOMIC64_INIT(0);
static atomic64_t n_proc_hidden = ATOMIC64_INIT(0);
static atomic64_t n_link_blocked = ATOMIC64_INIT(0);
static atomic64_t n_maps_scrub = ATOMIC64_INIT(0);
static atomic64_t n_kallsyms_scrub = ATOMIC64_INIT(0);
static atomic64_t n_mount_scrub = ATOMIC64_INIT(0);

/* 控制节点自身永远不被隐藏，否则配置写不回来 */
static const char ace_ctrl_name[] = "kuaimao_hide";
static const char ace_ctrl_path[] = "/proc/kuaimao_hide";

static const char *ace_default_kw[] = { "ksu", "susfs", "kernelsu", "kpm", "ksud" };
static const char *ace_default_files[] = { "/proc/modules", "/proc/kcore" };

/*
 * 隐藏目标。LSM 层用 (s_dev, i_ino) 精确匹配这些路径，
 * 命中根 inode 后整棵子树在 path walk 阶段就不可达，不再逐个 syscall 补。
 */
#define ACE_MAX_TARGETS 8

struct ace_target {
	dev_t dev;
	unsigned long ino;
};

static struct ace_target ace_targets[ACE_MAX_TARGETS];
static int ace_target_count;

static const char *ace_default_targets[] = {
	"/data/adb/ksu",
	"/data/adb/modules",
	"/data/adb/ksud",
	"/sys/module/kernelsu",
};

static atomic64_t n_lsm_hits = ATOMIC64_INIT(0);

void ace_note_lsm_hit(void)
{
	atomic64_inc(&n_lsm_hits);
}

long long ace_lsm_hit_count(void)
{
	return atomic64_read(&n_lsm_hits);
}

/* 路径 -> inode 号。路径不存在（已被 SUSFS 藏掉）时跳过，不影响其余目标。 */
void ace_targets_refresh(void)
{
	struct path p;
	struct inode *in;
	int i, n = 0;

	WRITE_ONCE(ace_target_count, 0);

	for (i = 0; i < ARRAY_SIZE(ace_default_targets) && n < ACE_MAX_TARGETS; i++) {
		if (kern_path(ace_default_targets[i], LOOKUP_FOLLOW, &p))
			continue;
		in = d_inode(p.dentry);
		if (in && in->i_sb) {
			ace_targets[n].dev = in->i_sb->s_dev;
			ace_targets[n].ino = in->i_ino;
			n++;
		}
		path_put(&p);
	}

	WRITE_ONCE(ace_target_count, n);
}

int ace_target_num(void)
{
	return READ_ONCE(ace_target_count);
}

bool ace_inode_hidden(const struct inode *inode)
{
	int i, n;

	if (!inode || !inode->i_sb)
		return false;

	n = READ_ONCE(ace_target_count);
	if (unlikely(!n))
		return false;
	for (i = 0; i < n; i++) {
		if (ace_targets[i].dev == inode->i_sb->s_dev &&
		    ace_targets[i].ino == inode->i_ino)
			return true;
	}
	return false;
}

bool ace_task_hidden(const struct task_struct *task)
{
	int len;

	if (!task)
		return false;
	len = strnlen(task->comm, TASK_COMM_LEN);
	return ace_hide_name_matches(task->comm, len);
}

bool ace_hide_enabled(void)
{
	return READ_ONCE(ace_on);
}

/*
 * 作用域：默认只对普通应用（uid >= 10000）生效。
 * 管理器与 ksud/su 必须保持可见，否则模块管理、安装、授权会被自己的隐藏打断。
 * strict=on 时取消该限制，对全部进程生效。
 *
 * 已授权 root 的 uid 也豁免：su 的提权链路是
 *   app 执行 /system/bin/su -> 内核重定向到 /data/adb/ksud -> 读 /data/adb/ksu 授权数据
 * 提权完成前这个 ksud 进程仍是 app 的 uid（>=10000），而它要访问的路径全部
 * 命中关键字；不豁免的话，一开隐藏 su 就变成 ENOENT，等于把 root 功能拆了。
 * 请求 su 的 app 必然已在授权列表里（未授权时 sucompat 根本不响应），
 * 所以这个豁免不会把未授权的检测进程放进来。
 */
bool ace_scope_allows(void)
{
	if (READ_ONCE(ace_strict))
		return true;
	if (ksu_get_uid_t(current_euid()) < 10000)
		return false;
	if (is_manager())
		return false;
	if (ksu_is_allow_uid_for_current(ksu_get_uid_t(current_uid())))
		return false;
	return true;
}

/* ascii 大小写不敏感子串匹配 */
static bool ace_substr_ci(const char *hay, int haylen, const char *needle)
{
	int nlen = strlen(needle), i, j;

	if (nlen == 0 || nlen > haylen)
		return false;

	for (i = 0; i + nlen <= haylen; i++) {
		for (j = 0; j < nlen; j++) {
			char a = hay[i + j], b = needle[j];
			if (a >= 'A' && a <= 'Z')
				a += 'a' - 'A';
			if (b >= 'A' && b <= 'Z')
				b += 'a' - 'A';
			if (a != b)
				break;
		}
		if (j == nlen)
			return true;
	}
	return false;
}

bool ace_hide_name_matches(const char *name, int len)
{
	int i;
	bool hit = false;

	if (!name || len <= 0)
		return false;

	/* 配置节点自身豁免 */
	if (len == (int)sizeof(ace_ctrl_name) - 1 && !memcmp(name, ace_ctrl_name, len))
		return false;

	read_lock(&ace_cfg_lock);
	for (i = 0; i < ace_kw_count; i++) {
		if (ace_substr_ci(name, len, ace_keywords[i])) {
			hit = true;
			break;
		}
	}
	read_unlock(&ace_cfg_lock);
	return hit;
}

bool ace_hide_path_matches(const char *path)
{
	const char *base;
	int len;

	if (!path || path[0] != '/')
		return false;
	if (!strcmp(path, ace_ctrl_path))
		return false;

	base = strrchr(path, '/');
	base = base ? base + 1 : path;
	len = strlen(base);
	if (len <= 0)
		return false;

	return ace_hide_name_matches(base, len);
}

/* 从 syscall 参数读入绝对路径。arg 为 1-based 参数序号。 */
static bool ace_read_path(const struct pt_regs *regs, int arg, char *buf, int buflen)
{
	const char __user *fn;
	long n;

	switch (arg) {
	case 1:
		fn = (const char __user *)PT_REGS_USER_PTR(regs, 1);
		break;
	case 2:
		fn = (const char __user *)PT_REGS_USER_PTR(regs, 2);
		break;
	case 3:
		/* arch.h 只定义了 PARM1/2/3/5/6，这里直接取第三个参数寄存器 */
		fn = (const char __user *)ksu_task_user_ptr(PT_REGS_PARM3(regs));
		break;
	default:
		return false;
	}

	if (!fn || IS_ERR(fn))
		return false;

	n = strncpy_from_user_nofault(buf, fn, buflen - 1);
	if (n <= 0)
		return false;
	buf[n < buflen ? n : buflen - 1] = '\0';

	/* 只处理绝对路径：相对路径依赖 dfd，误判代价太高 */
	return buf[0] == '/';
}

/*
 * 从 "/proc/<pid>/..." 里解析 pid。支持数字、self、thread-self。
 * 只做前缀解析，不校验后续段，调用方自己决定要看哪一段。
 */
static bool ace_proc_path_pid(const char *path, pid_t *out_pid)
{
	const char *p;
	char *end;
	unsigned long pid;

	if (!path || strncmp(path, "/proc/", 6))
		return false;

	p = path + 6;
	if (!strncmp(p, "self/", 5)) {
		*out_pid = task_tgid_vnr(current);
		return true;
	}
	if (!strncmp(p, "thread-self/", 12)) {
		*out_pid = task_pid_vnr(current);
		return true;
	}
	if (*p < '0' || *p > '9')
		return false;

	pid = simple_strtoul(p, &end, 10);
	if (end == p || (*end != '/' && *end != '\0'))
		return false;
	if (pid == 0 || pid > INT_MAX)
		return false;

	*out_pid = (pid_t)pid;
	return true;
}

/* 进程的 comm 是否命中关键字表（ksud、susfsd、kworker/u8:2 之类） */
static bool ace_pid_hidden(pid_t pid)
{
	struct task_struct *t;
	char comm[TASK_COMM_LEN];
	int len;
	bool hit = false;

	if (pid <= 0)
		return false;

	rcu_read_lock();
	t = find_task_by_vpid(pid);
	if (t) {
		len = strnlen(t->comm, TASK_COMM_LEN);
		memcpy(comm, t->comm, len);
		comm[len] = '\0';
		hit = ace_hide_name_matches(comm, len);
	}
	rcu_read_unlock();
	return hit;
}

/* 数字目录项直接判 pid，不拼路径 */
static bool ace_pid_hidden_simple(const char *name, int len)
{
	unsigned long pid = 0;
	int i;

	if (len <= 0 || len > 10)
		return false;
	for (i = 0; i < len; i++) {
		if (name[i] < '0' || name[i] > '9')
			return false;
		pid = pid * 10 + (unsigned long)(name[i] - '0');
	}
	return ace_pid_hidden((pid_t)pid);
}

/* /proc/<pid>/exe、cwd、root、fd/N 等链接：pid 隐藏则整条链接不存在 */
bool ace_hide_link_blocked(const char *path)
{
	pid_t pid;

	if (!ace_hide_enabled() || !READ_ONCE(ace_procs_on))
		return false;
	if (!ace_scope_allows())
		return false;
	if (ace_proc_path_pid(path, &pid) && ace_pid_hidden(pid)) {
		atomic64_inc(&n_link_blocked);
		return true;
	}
	return false;
}

/* fd 是否指向 /proc 根目录（getdents64 的入参） */
static bool ace_fd_is_proc_dir(int fd)
{
	struct file *f;
	struct dentry *d;
	bool ok = false;

	if (fd < 0)
		return false;

	/* 6.12+ 把 struct fd 改成了不透明字，直接用 fget_raw 兼容所有版本 */
	f = fget_raw(fd);
	if (f) {
		d = f->f_path.dentry;
		if (d && d->d_sb && d->d_sb->s_magic == PROC_SUPER_MAGIC)
			ok = (d == d->d_sb->s_root);
		fput(f);
	}
	return ok;
}

/*
 * read(2) 是系统里最热的 syscall，这里必须只做一次 fget。
 * 返回 0=不是目标文件，1=maps/smaps，2=kallsyms。
 * 旧实现每次都做 2~3 次 fget_raw/fput，等于给每个 read 加了三倍开销。
 */
enum ace_proc_kind {
	ACE_PROC_OTHER = 0,
	ACE_PROC_MAPS,
	ACE_PROC_KALLSYMS,
	ACE_PROC_MOUNT,
};

static int ace_fd_proc_kind(int fd)
{
	struct file *f;
	struct dentry *d;
	const char *name;
	int kind = ACE_PROC_OTHER;

	if (fd < 0)
		return ACE_PROC_OTHER;

	f = fget_raw(fd);
	if (!f)
		return ACE_PROC_OTHER;

	d = f->f_path.dentry;
	if (d && d->d_sb && d->d_sb->s_magic == PROC_SUPER_MAGIC) {
		name = d->d_name.name;
		if (!strcmp(name, "maps") || !strcmp(name, "smaps"))
			kind = ACE_PROC_MAPS;
		else if (!strcmp(name, "kallsyms"))
			kind = ACE_PROC_KALLSYMS;
		else if (!strcmp(name, "mountinfo") || !strcmp(name, "mounts"))
			kind = ACE_PROC_MOUNT;
	}
	fput(f);
	return kind;
}

/*
 * 行级擦除：把命中关键字的行内容全部换成空格（保留换行）。
 * 长度不变，所以用户的 read 偏移、文件位置不受影响；
 * 检测器看到的是一行空白，grep 不到任何关键字。
 */
static bool ace_scrub_lines(char *buf, long len)
{
	long i, start = 0;
	bool dirty = false;

	for (i = 0; i <= len; i++) {
		if (i == len || buf[i] == '\n') {
			int l = (int)(i - start);

			if (l > 0 && ace_hide_name_matches(buf + start, l)) {
				memset(buf + start, ' ', l);
				dirty = true;
			}
			start = i + 1;
		}
	}
	return dirty;
}

/*
 * /proc/<pid> 及其子路径：进程被隐藏时整棵子树都当作不存在。
 * 只隐藏目录项是不够的——检测器可以绕开 getdents，直接按 pid 打开
 * /proc/<pid>/comm、status、cmdline 来发现 ksud。
 */
static bool ace_hide_proc_pid_blocked(const char *path)
{
	pid_t pid;

	if (!READ_ONCE(ace_procs_on))
		return false;
	if (!ace_proc_path_pid(path, &pid))
		return false;
	return ace_pid_hidden(pid);
}

bool ace_hide_stat_blocked(const struct pt_regs *regs, int path_arg)
{
	char path[ACE_HIDE_PATH_LEN];

	if (!ace_hide_enabled() || !READ_ONCE(ace_dents_on))
		return false;
	/*
	 * LSM 侧已经在 path walk 上按 inode 拦住了，这里再走一遍
	 * strncpy_from_user + 子串匹配纯属重复劳动，而 stat 族恰恰是
	 * 检测器最爱刷的 syscall。只有 LSM 不可用时才需要这层兜底。
	 */
	if (ace_lsm_active())
		return false;
	if (!ace_scope_allows())
		return false;
	if (!ace_read_path(regs, path_arg, path, sizeof(path)))
		return false;
	if (ace_hide_path_matches(path)) {
		atomic64_inc(&n_stat_blocked);
		return true;
	}
	if (ace_hide_proc_pid_blocked(path)) {
		atomic64_inc(&n_proc_hidden);
		return true;
	}
	return false;
}

bool ace_hide_open_blocked(const struct pt_regs *regs, int path_arg)
{
	char path[ACE_HIDE_PATH_LEN];
	int i;
	bool hit = false;

	if (!ace_hide_enabled())
		return false;
	/* LSM 可用时 openat/openat2 的路径判定交给 inode 层，省掉这次用户态拷贝 */
	if (ace_lsm_active())
		return false;
	/* 这里原先漏了作用域判断：root / 管理器 / su 提权中的 ksud
	 * 打开 /data/adb/ksu 也会被拦成 ENOENT，等于把授权流程拆了。 */
	if (!ace_scope_allows())
		return false;
	if (!ace_read_path(regs, path_arg, path, sizeof(path)))
		return false;

	if (READ_ONCE(ace_files_on)) {
		read_lock(&ace_cfg_lock);
		for (i = 0; i < ace_file_count; i++) {
			if (!strcmp(path, ace_files[i])) {
				hit = true;
				break;
			}
		}
		read_unlock(&ace_cfg_lock);
	}

	/* 隐藏路径本身也不允许 open（否则 open 成功而 stat 报 ENOENT，自相矛盾） */
	if (!hit && READ_ONCE(ace_dents_on))
		hit = ace_hide_path_matches(path);
	/* /proc/<pid> 子树：隐藏进程的 comm/status/cmdline 一并关掉 */
	if (!hit && READ_ONCE(ace_procs_on))
		hit = ace_hide_proc_pid_blocked(path);
	if (hit)
		atomic64_inc(&n_open_blocked);
	return hit;
}

/*
 * 就地压缩目录项。返回值即新的 syscall 返回长度。
 * 任何异常（读失败、结构损坏、内存不足）都退回原始结果，绝不返回半截数据。
 */
long ace_hide_filter_dirent64(void __user *dirent, long total, bool proc_dir)
{
	struct ace_dirent64 *buf, *src;
	long off = 0, newlen = 0;
	int namelen;

	if (total <= 0 || total > (1L << 20))
		return total;

	/* 每批只判定一次作用域，避免在目录项循环里反复查管理器身份 */
	if (!READ_ONCE(ace_dents_on) || !ace_scope_allows())
		return total;

	buf = kvmalloc(total, GFP_KERNEL);
	if (!buf)
		return total;

	if (copy_from_user(buf, dirent, total)) {
		kvfree(buf);
		return total;
	}

	while (off < total) {
		src = (struct ace_dirent64 *)((char *)buf + off);

		if (src->d_reclen < offsetof(struct ace_dirent64, d_name) + 1 ||
		    off + src->d_reclen > total) {
			/* 结构异常，放弃过滤 */
			newlen = total;
			goto out;
		}

		namelen = src->d_reclen - offsetof(struct ace_dirent64, d_name);
		namelen = strnlen(src->d_name, namelen);

		if (ace_hide_name_matches(src->d_name, namelen)) {
			atomic64_inc(&n_dents_hidden);
		} else if (proc_dir && READ_ONCE(ace_procs_on) &&
			   ace_pid_hidden_simple(src->d_name, namelen)) {
			/* /proc 根目录下的纯数字项：进程被隐藏则整项剔除 */
			atomic64_inc(&n_proc_hidden);
		} else {
			if (newlen != off)
				memmove((char *)buf + newlen, src, src->d_reclen);
			newlen += src->d_reclen;
		}
		off += src->d_reclen;
	}

	if (newlen != total && copy_to_user(dirent, buf, newlen))
		newlen = total;

out:
	kvfree(buf);
	return newlen;
}

long __nocfi ace_hook_getdents64(int orig_nr, const struct pt_regs *regs)
{
	long ret = ksu_call_syscall(orig_nr, regs);
	void __user *dirent = (void __user *)PT_REGS_USER_PTR(regs, 2);
	int fd = (int)PT_REGS_PARM1(regs);
	bool proc_dir;

	if (!ace_hide_enabled() || ret <= 0)
		return ret;

	proc_dir = READ_ONCE(ace_procs_on) && ace_fd_is_proc_dir(fd);
	return ace_hide_filter_dirent64(dirent, ret, proc_dir);
}

long __nocfi ace_hook_openat(int orig_nr, const struct pt_regs *regs)
{
	if (unlikely(ace_hide_open_blocked(regs, 2)))
		return -ENOENT;

	return ksu_call_syscall(orig_nr, regs);
}

/*
 * openat2(dfd, filename, struct open_how __user *, size)
 * Android 11+ 的检测框架和部分 Rust/Go 程序直接用这个接口，
 * 只 hook openat 会留一个同义入口。
 */
long __nocfi ace_hook_openat2(int orig_nr, const struct pt_regs *regs)
{
	if (unlikely(ace_hide_open_blocked(regs, 2)))
		return -ENOENT;

	return ksu_call_syscall(orig_nr, regs);
}

/*
 * execve/execveat 的入口判定。原先只拦 stat/open，
 * 检测器可以直接 execve("/data/adb/ksu/bin/su") 看返回值来判定文件存在。
 * 这里在 sucompat 之前拦，命中直接 -ENOENT。
 */
bool ace_hide_exec_blocked(const char __user *filename)
{
	char path[ACE_HIDE_PATH_LEN];
	long n;

	if (!ace_hide_enabled() || !READ_ONCE(ace_dents_on))
		return false;
	if (!ace_scope_allows())
		return false;
	if (!filename)
		return false;

	n = strncpy_from_user(path,
			      (const char __user *)ksu_task_user_ptr((unsigned long)filename),
			      sizeof(path) - 1);
	if (n <= 0)
		return false;
	path[n] = '\0';

	if (!ace_hide_path_matches(path) && !ace_hide_proc_pid_blocked(path))
		return false;

	atomic64_inc(&n_stat_blocked);
	return true;
}

/* statx(int dfd, const char *filename, int flags, unsigned int mask, struct statx *buf) */
long __nocfi ace_hook_statx(int orig_nr, const struct pt_regs *regs)
{
	if (unlikely(ace_hide_stat_blocked(regs, 2)))
		return -ENOENT;

	return ksu_call_syscall(orig_nr, regs);
}

/* faccessat2(int dfd, const char *filename, int mode, int flags) */
long __nocfi ace_hook_faccessat2(int orig_nr, const struct pt_regs *regs)
{
	if (unlikely(ace_hide_stat_blocked(regs, 2)))
		return -ENOENT;

	return ksu_call_syscall(orig_nr, regs);
}

/* readlinkat(int dfd, const char *pathname, char *buf, int bufsiz) */
long __nocfi ace_hook_readlinkat(int orig_nr, const struct pt_regs *regs)
{
	char path[ACE_HIDE_PATH_LEN];

	if (ace_hide_enabled() && READ_ONCE(ace_procs_on) && !ace_lsm_active() &&
	    ace_scope_allows() &&
	    ace_read_path(regs, 2, path, sizeof(path)) &&
	    ace_hide_link_blocked(path))
		return -ENOENT;

	return ksu_call_syscall(orig_nr, regs);
}

/*
 * read(fd, buf, count)：
 *   /proc/<pid>/maps、/proc/<pid>/smaps  → 命中关键字的行擦成空格
 *   /proc/kallsyms                        → 同上
 *
 * 只对 procfs 的具体文件生效，先用 dentry 名字做一次常数级判定，
 * 不是这两个文件的 read 只多一次 fdget/fdput，不进拷贝路径。
 */
long __nocfi ace_hook_read(int orig_nr, const struct pt_regs *regs)
{
	long ret;
	void __user *ubuf = (void __user *)PT_REGS_USER_PTR(regs, 2);
	int fd = (int)PT_REGS_PARM1(regs);
	int kind;
	char *buf;

	/*
	 * read 是最高频的 syscall，顺序按代价排：
	 *   开关 → fd 是不是 procfs 的那几个文件（一次 fdget）→ 身份判定。
	 * 普通文件的 read 在第二步行就返回了，不碰 RCU 链表与授权表。
	 */
	if (!ace_hide_enabled())
		return ksu_call_syscall(orig_nr, regs);
	if (!READ_ONCE(ace_maps_on) && !READ_ONCE(ace_kallsyms_on) &&
	    !READ_ONCE(ace_dents_on))
		return ksu_call_syscall(orig_nr, regs);

	kind = ace_fd_proc_kind(fd);
	if (kind == ACE_PROC_OTHER)
		return ksu_call_syscall(orig_nr, regs);
	if (kind == ACE_PROC_MAPS && !READ_ONCE(ace_maps_on))
		return ksu_call_syscall(orig_nr, regs);
	if (kind == ACE_PROC_KALLSYMS && !READ_ONCE(ace_kallsyms_on))
		return ksu_call_syscall(orig_nr, regs);
	/* 挂载表属于路径可见面：路径隐藏开着才擦 */
	if (kind == ACE_PROC_MOUNT && !READ_ONCE(ace_dents_on))
		return ksu_call_syscall(orig_nr, regs);

	if (!ace_scope_allows())
		return ksu_call_syscall(orig_nr, regs);

	ret = ksu_call_syscall(orig_nr, regs);
	if (ret <= 0 || ret > (256L << 10))
		return ret;

	buf = kvmalloc(ret, GFP_KERNEL);
	if (!buf)
		return ret;
	if (copy_from_user(buf, ubuf, ret)) {
		kvfree(buf);
		return ret;
	}

	if (ace_scrub_lines(buf, ret)) {
		if (copy_to_user(ubuf, buf, ret))
			ret = -EFAULT;
		else if (kind == ACE_PROC_MAPS)
			atomic64_inc(&n_maps_scrub);
		else if (kind == ACE_PROC_MOUNT)
			atomic64_inc(&n_mount_scrub);
		else
			atomic64_inc(&n_kallsyms_scrub);
	}

	kvfree(buf);
	return ret;
}

/* ---------------- /proc 控制接口 ---------------- */

static void ace_set_defaults(void)
{
	int i;

	write_lock(&ace_cfg_lock);
	ace_kw_count = 0;
	for (i = 0; i < ARRAY_SIZE(ace_default_kw) && ace_kw_count < ACE_HIDE_MAX_KW; i++) {
		strscpy(ace_keywords[ace_kw_count], ace_default_kw[i], ACE_HIDE_KW_LEN);
		ace_kw_count++;
	}
	ace_file_count = 0;
	for (i = 0; i < ARRAY_SIZE(ace_default_files) && ace_file_count < ACE_MAX_FILES; i++) {
		strscpy(ace_files[ace_file_count], ace_default_files[i], ACE_FILE_LEN);
		ace_file_count++;
	}
	write_unlock(&ace_cfg_lock);
}

static void ace_kw_add(const char *s, int len)
{
	int i;

	if (len <= 0 || len >= ACE_HIDE_KW_LEN)
		return;

	write_lock(&ace_cfg_lock);
	for (i = 0; i < ace_kw_count; i++) {
		if ((int)strlen(ace_keywords[i]) == len && !memcmp(ace_keywords[i], s, len)) {
			write_unlock(&ace_cfg_lock);
			return;
		}
	}
	if (ace_kw_count < ACE_HIDE_MAX_KW) {
		memcpy(ace_keywords[ace_kw_count], s, len);
		ace_keywords[ace_kw_count][len] = '\0';
		ace_kw_count++;
	}
	write_unlock(&ace_cfg_lock);
}

static void ace_file_add(const char *s, int len)
{
	int i;

	if (len <= 0 || len >= ACE_FILE_LEN || s[0] != '/')
		return;

	write_lock(&ace_cfg_lock);
	for (i = 0; i < ace_file_count; i++) {
		if ((int)strlen(ace_files[i]) == len && !memcmp(ace_files[i], s, len)) {
			write_unlock(&ace_cfg_lock);
			return;
		}
	}
	if (ace_file_count < ACE_MAX_FILES) {
		memcpy(ace_files[ace_file_count], s, len);
		ace_files[ace_file_count][len] = '\0';
		ace_file_count++;
	}
	write_unlock(&ace_cfg_lock);
}

static int ace_apply_line(char *line)
{
	char *p = line, *end;

	while (*p == ' ' || *p == '\t')
		p++;
	end = p + strlen(p);
	while (end > p && (end[-1] == '\n' || end[-1] == '\r' || end[-1] == ' '))
		*--end = '\0';

	if (!*p)
		return 0;

	if (!strcmp(p, "on")) {
		/* 先解析目标 inode 再开总开关，避免刷新过程被自己的 hook 影响 */
		ace_targets_refresh();
		WRITE_ONCE(ace_on, true);
		/* 开启后立刻补标记：已经在跑的 app 不会等下一次 reg_count 变化 */
		ksu_mark_running_process();
		ace_log("enabled\n");
		return 0;
	}
	if (!strcmp(p, "off")) {
		WRITE_ONCE(ace_on, false);
		ace_log("disabled\n");
		return 0;
	}
	if (!strcmp(p, "reset")) {
		ace_set_defaults();
		/* 模块开关一并复位，避免上一次的 off 残留到新配置里 */
		WRITE_ONCE(ace_dents_on, true);
		WRITE_ONCE(ace_files_on, true);
		WRITE_ONCE(ace_procs_on, true);
		WRITE_ONCE(ace_maps_on, true);
		WRITE_ONCE(ace_kallsyms_on, true);
		WRITE_ONCE(ace_strict, false);
		return 0;
	}	if (!strcmp(p, "dents=on") || !strcmp(p, "dents=1")) {
		WRITE_ONCE(ace_dents_on, true);
		return 0;
	}
	if (!strcmp(p, "dents=off") || !strcmp(p, "dents=0")) {
		WRITE_ONCE(ace_dents_on, false);
		return 0;
	}
	if (!strcmp(p, "files=off") || !strcmp(p, "files=0")) {
		WRITE_ONCE(ace_files_on, false);
		return 0;
	}
	if (!strcmp(p, "files=on") || !strcmp(p, "files=1")) {
		WRITE_ONCE(ace_files_on, true);
		return 0;
	}
	if (!strcmp(p, "strict=on") || !strcmp(p, "strict=1")) {
		WRITE_ONCE(ace_strict, true);
		return 0;
	}
	if (!strcmp(p, "strict=off") || !strcmp(p, "strict=0")) {
		WRITE_ONCE(ace_strict, false);
		return 0;
	}
	if (!strcmp(p, "log=on") || !strcmp(p, "log=1")) {
		WRITE_ONCE(ace_log_on, true);
		return 0;
	}
	if (!strcmp(p, "log=off") || !strcmp(p, "log=0")) {
		WRITE_ONCE(ace_log_on, false);
		return 0;
	}
	if (!strcmp(p, "procs=on") || !strcmp(p, "procs=1")) {
		WRITE_ONCE(ace_procs_on, true);
		return 0;
	}
	if (!strcmp(p, "procs=off") || !strcmp(p, "procs=0")) {
		WRITE_ONCE(ace_procs_on, false);
		return 0;
	}
	if (!strcmp(p, "maps=on") || !strcmp(p, "maps=1")) {
		WRITE_ONCE(ace_maps_on, true);
		return 0;
	}
	if (!strcmp(p, "maps=off") || !strcmp(p, "maps=0")) {
		WRITE_ONCE(ace_maps_on, false);
		return 0;
	}
	/*
	 * 状态行里这一项叫 syms，早期配置脚本用 kallsyms，两个都认。
	 * 只认一个的话，管理器发 syms=on 会被判为非法行，整次写入直接 EINVAL。
	 */
	if (!strcmp(p, "syms=on") || !strcmp(p, "syms=1") ||
	    !strcmp(p, "sym=on") || !strcmp(p, "sym=1") ||
	    !strcmp(p, "kallsyms=on") || !strcmp(p, "kallsyms=1")) {
		WRITE_ONCE(ace_kallsyms_on, true);
		return 0;
	}
	if (!strcmp(p, "syms=off") || !strcmp(p, "syms=0") ||
	    !strcmp(p, "sym=off") || !strcmp(p, "sym=0") ||
	    !strcmp(p, "kallsyms=off") || !strcmp(p, "kallsyms=0")) {
		WRITE_ONCE(ace_kallsyms_on, false);
		return 0;
	}

	if (!strncmp(p, "kw=", 3)) {
		write_lock(&ace_cfg_lock);
		ace_kw_count = 0;
		write_unlock(&ace_cfg_lock);
		p += 3;
		goto list;
	}
	if (!strncmp(p, "kw+=", 4)) {
		p += 4;
	list:
		while (*p) {
			char *comma = strchr(p, ',');
			int len = comma ? (int)(comma - p) : (int)strlen(p);
			if (len > 0)
				ace_kw_add(p, len);
			if (!comma)
				break;
			p = comma + 1;
		}
		return 0;
	}

	if (!strncmp(p, "files=", 6)) {
		write_lock(&ace_cfg_lock);
		ace_file_count = 0;
		write_unlock(&ace_cfg_lock);
		p += 6;
		while (*p) {
			char *comma = strchr(p, ',');
			int len = comma ? (int)(comma - p) : (int)strlen(p);
			if (len > 0)
				ace_file_add(p, len);
			if (!comma)
				break;
			p = comma + 1;
		}
		return 0;
	}
	if (!strncmp(p, "files+=", 7)) {
		p += 7;
		while (*p) {
			char *comma = strchr(p, ',');
			int len = comma ? (int)(comma - p) : (int)strlen(p);
			if (len > 0)
				ace_file_add(p, len);
			if (!comma)
				break;
			p = comma + 1;
		}
		return 0;
	}

	return -EINVAL;
}

/*
 * 持久化：LKM 在 post-fs-data 阶段 late-load，此时 /data 已解密挂载，
 * 可以直接读管理器的配置把隐藏重新 arm 起来，重启不需要手动再点一次。
 * 失败（/data 未就绪、文件不存在）按 3s 间隔重试，最多 20 次。
 * strict 永远不落盘：它只用于管理器自证，开机恢复会把自己也挡在外面。
 */
#define ACE_CONF_PATH "/data/adb/kuaimao/ace_hide.conf"

static struct delayed_work ace_boot_work;
static int ace_boot_tries;

static bool ace_load_conf(void)
{
	struct file *f;
	loff_t pos = 0;
	char *buf;
	ssize_t n;
	char *p, *nl;

	f = filp_open(ACE_CONF_PATH, O_RDONLY, 0);
	if (IS_ERR(f))
		return false;

	buf = kzalloc(2048, GFP_KERNEL);
	if (!buf) {
		filp_close(f, NULL);
		return false;
	}

	n = kernel_read(f, buf, 2047, &pos);
	filp_close(f, NULL);
	if (n <= 0) {
		kfree(buf);
		return false;
	}
	buf[n] = '\0';

	p = buf;
	while (p && *p) {
		nl = strchr(p, '\n');
		if (nl)
			*nl = '\0';
		if (*p)
			ace_apply_line(p);
		p = nl ? nl + 1 : NULL;
	}
	kfree(buf);
	return true;
}

static void ace_boot_work_fn(struct work_struct *w)
{
	if (READ_ONCE(ace_on))
		return;

	if (!ace_load_conf()) {
		if (++ace_boot_tries < 20)
			schedule_delayed_work(&ace_boot_work, 3 * HZ);
		return;
	}

	if (READ_ONCE(ace_on))
		ace_log("restored from %s\n", ACE_CONF_PATH);
	else
		ace_log("%s present but not enabled\n", ACE_CONF_PATH);
}

/* SUSFS 是否在场：只查控制节点，不依赖它的内部符号 */
static bool ace_susfs_present(void)
{
	struct path p;

	if (kern_path("/proc/susfs_path", LOOKUP_FOLLOW, &p))
		return false;
	path_put(&p);
	return true;
}

static ssize_t ace_proc_read(struct file *file, char __user *ubuf, size_t count, loff_t *ppos)
{
	char *buf;
	int len = 0, i;
	ssize_t ret;

	if (*ppos > 0)
		return 0;

	buf = kzalloc(1024, GFP_KERNEL);
	if (!buf)
		return -ENOMEM;

	len += scnprintf(buf + len, 1024 - len,
			 "state=%s dents=%s files=%s procs=%s maps=%s syms=%s strict=%s log=%s unknown=%d ver=%d\n",
			 ace_hide_enabled() ? "on" : "off",
			 READ_ONCE(ace_dents_on) ? "on" : "off",
			 READ_ONCE(ace_files_on) ? "on" : "off",
			 READ_ONCE(ace_procs_on) ? "on" : "off",
			 READ_ONCE(ace_maps_on) ? "on" : "off",
			 READ_ONCE(ace_kallsyms_on) ? "on" : "off",
			 READ_ONCE(ace_strict) ? "on" : "off",
			 READ_ONCE(ace_log_on) ? "on" : "off",
			 READ_ONCE(ace_last_unknown),
			 ACE_VER);

	/* mode 是排查侧信道时延问题最直接的一行：direct 表示没有注册
	 * sys_enter tracepoint，未 hook 的 syscall 零额外开销。 */
#ifdef __aarch64__
	len += scnprintf(buf + len, 1024 - len, " mode=%s targets=%d hooks=%d\n",
			 ksu_syscall_hook_is_direct() ? "direct" : "shared",
			 ace_target_num(),
			 ksu_syscall_hook_count());
#else
	len += scnprintf(buf + len, 1024 - len, "\n");
#endif

	len += scnprintf(buf + len, 1024 - len, "kw=");
	read_lock(&ace_cfg_lock);
	for (i = 0; i < ace_kw_count; i++)
		len += scnprintf(buf + len, 1024 - len, "%s%s", i ? "," : "", ace_keywords[i]);
	len += scnprintf(buf + len, 1024 - len, "\nfiles=");
	for (i = 0; i < ace_file_count; i++)
		len += scnprintf(buf + len, 1024 - len, "%s%s", i ? "," : "", ace_files[i]);
	read_unlock(&ace_cfg_lock);

	len += scnprintf(buf + len, 1024 - len,
			 "\nhits_dents=%lld hits_stat=%lld hits_open=%lld\n"
			 "hits_proc=%lld hits_link=%lld hits_maps=%lld hits_syms=%lld hits_mount=%lld\n",
			 (long long)atomic64_read(&n_dents_hidden),
			 (long long)atomic64_read(&n_stat_blocked),
			 (long long)atomic64_read(&n_open_blocked),
			 (long long)atomic64_read(&n_proc_hidden),
			 (long long)atomic64_read(&n_link_blocked),
			 (long long)atomic64_read(&n_maps_scrub),
			 (long long)atomic64_read(&n_kallsyms_scrub),
			 (long long)atomic64_read(&n_mount_scrub));

	{
		int lsm_ok = 0, lsm_total = 0;

		ace_lsm_status(&lsm_ok, &lsm_total);
		len += scnprintf(buf + len, 1024 - len,
				 "lsm=%d/%d targets=%d hits_lsm=%lld susfs=%s\n",
				 lsm_ok, lsm_total, READ_ONCE(ace_target_count),
				 ace_lsm_hit_count(),
				 ace_susfs_present() ? "on" : "off");
	}

	ret = simple_read_from_buffer(ubuf, count, ppos, buf, len);
	kfree(buf);
	return ret;
}

static ssize_t ace_proc_write(struct file *file, const char __user *ubuf, size_t count, loff_t *ppos)
{
	char *buf;
	ssize_t ret = count;
	int bad = 0, good = 0;

	if (count == 0 || count > 512)
		return -EINVAL;

	buf = memdup_user_nul(ubuf, count);
	if (IS_ERR(buf))
		return PTR_ERR(buf);

	/* 支持一次写入多行，便于用户态原子下发整套配置 */
	{
		char *cur = buf, *nl;
		while (cur && *cur) {
			nl = strchr(cur, '\n');
			if (nl)
				*nl = '\0';
			if (ace_apply_line(cur))
				bad++;
			else if (*cur)
				good++;
			cur = nl ? nl + 1 : NULL;
		}
	}

	kfree(buf);
	WRITE_ONCE(ace_last_unknown, bad);

	/*
	 * 只有整批都无法识别才算失败。部分行不认识时仍然放行，
	 * 否则一条拼写差异就会让整套配置半途而废（前面已生效、后面没下发），
	 * 界面显示的状态和内核真实状态从此对不上。
	 */
	if (bad && !good)
		return -EINVAL;
	return ret;
}

#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 6, 0)
static const struct proc_ops ace_proc_ops = {
	.proc_read = ace_proc_read,
	.proc_write = ace_proc_write,
	.proc_lseek = default_llseek,
};
#else
static const struct file_operations ace_proc_ops = {
	.read = ace_proc_read,
	.write = ace_proc_write,
	.llseek = default_llseek,
};
#endif

static struct proc_dir_entry *ace_proc_entry;

int ace_hide_init(void)
{
	ace_set_defaults();
	WRITE_ONCE(ace_on, false);
	WRITE_ONCE(ace_dents_on, true);
	WRITE_ONCE(ace_files_on, true);
	WRITE_ONCE(ace_procs_on, true);
	WRITE_ONCE(ace_maps_on, true);
	WRITE_ONCE(ace_kallsyms_on, true);
	WRITE_ONCE(ace_strict, false);

	ace_proc_entry = proc_create(ace_ctrl_name, 0600, NULL, &ace_proc_ops);
	if (!ace_proc_entry) {
		pr_err(ACE_TAG ": failed to create /proc/%s\n", ace_ctrl_name);
		return -ENOMEM;
	}

	/* 路径 -> inode 号：LSM 层靠它做 (dev, ino) 精确匹配 */
	ace_targets_refresh();
	/* 注册 LSM 钩子。失败不致命：syscall 层仍然是完整的兜底，状态行会显示 lsm=x/6 */
	ace_lsm_init();

	/* 延迟 5s 让 /data 与 ksud 的模块目录就位，再尝试恢复上次的配置 */
	INIT_DELAYED_WORK(&ace_boot_work, ace_boot_work_fn);
	schedule_delayed_work(&ace_boot_work, 5 * HZ);

	ace_log("ready, control=/proc/%s (default off)\n", ace_ctrl_name);
	return 0;
}

void ace_hide_exit(void)
{
	cancel_delayed_work_sync(&ace_boot_work);
	WRITE_ONCE(ace_on, false);
	/* 先把 LSM 槽位还原，再拆控制节点，避免中途有请求走进半拆状态 */
	ace_lsm_exit();
	if (ace_proc_entry) {
		remove_proc_entry(ace_ctrl_name, NULL);
		ace_proc_entry = NULL;
	}
	ace_log("removed\n");
}
