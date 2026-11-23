// SPDX-License-Identifier: GPL-2.0
/*
 * 隐藏模块的 LSM 层钩子。
 *
 * 为什么不再只靠 sys_call_table：
 *   syscall 改写只能拦住"走 syscall 且被我们注册过"的入口。open/openat/
 *   openat2/statx/newfstatat/faccessat2/execve/execveat 每个都要单独补，
 *   漏一个就是一条检测通路；io_uring 的 openat 更是完全绕过。
 *   LSM 的 inode_permission 在 path walk 的每一级都会被调用，只要隐藏
 *   目标的根 inode 命中，整棵子树对任何路径访问都不可达——一处生效，
 *   所有入口覆盖。
 *
 * 为什么用 (s_dev, i_ino) 而不是字符串：
 *   - 不做 strncpy_from_user、不做子串匹配，热路径只比较两个整数；
 *   - 不会误伤名字里恰好含 ksu 的无关文件；
 *   - 软链、相对路径、".." 都绕不过去，inode 是唯一的。
 *
 * 注册方式：运行时替换 bpf_lsm_<hook> 桩（ksu_register_lsm_hook）。
 * 不改 security_hook_heads 链表结构，也不依赖 security_add_hooks 的
 * 初始化时机，因此在 late-load 的模块里同样可用。
 */
#include <linux/version.h>
#include <linux/fs.h>
#include <linux/file.h>
#include <linux/dcache.h>
#include <linux/path.h>
#include <linux/sched.h>
#include <linux/cred.h>
#include <linux/binfmts.h>
#include <linux/lsm_hooks.h>
#include <linux/ptrace.h>

#include "feature/ace_hide.h"
#include "feature/ace_lsm.h"
#include "hook/lsm_hook_magic.h"

enum {
	ACE_LSM_PERM,
	ACE_LSM_GETATTR,
	ACE_LSM_READLINK,
	ACE_LSM_OPEN,
	ACE_LSM_BPRM,
	ACE_LSM_PTRACE,
	ACE_LSM_NR
};

static int ace_lsm_ok_count;
static bool ace_lsm_ready;

static int ace_lsm_inode_permission(struct inode *inode, int mask);
static int ace_lsm_inode_getattr(const struct path *path);
static int ace_lsm_inode_readlink(struct dentry *dentry);
static int ace_lsm_file_open(struct file *file);
static int ace_lsm_bprm_check(struct linux_binprm *bprm);
static int ace_lsm_ptrace_check(struct task_struct *child, unsigned int mode);

/*
 * target_name 必须显式给出：ksu_register_lsm_hook() 要在
 * security_hook_heads 的链上找到"指向该符号"的那一项来替换。
 */
static struct ksu_lsm_hook ace_lsm_hooks[ACE_LSM_NR] = {
	KSU_LSM_HOOK_INIT(inode_permission, "bpf_lsm_inode_permission", ace_lsm_inode_permission, 0),
	KSU_LSM_HOOK_INIT(inode_getattr, "bpf_lsm_inode_getattr", ace_lsm_inode_getattr, 0),
	KSU_LSM_HOOK_INIT(inode_readlink, "bpf_lsm_inode_readlink", ace_lsm_inode_readlink, 0),
	KSU_LSM_HOOK_INIT(file_open, "bpf_lsm_file_open", ace_lsm_file_open, 0),
	KSU_LSM_HOOK_INIT(bprm_check_security, "bpf_lsm_bprm_check_security", ace_lsm_bprm_check, 0),
	KSU_LSM_HOOK_INIT(ptrace_access_check, "bpf_lsm_ptrace_access_check", ace_lsm_ptrace_check, 0),
};

#define ACE_CALL_ORIG(idx, cast, ...)                                                              \
	(ace_lsm_hooks[idx].original ? ((cast)ace_lsm_hooks[idx].original)(__VA_ARGS__) : 0)

/*
 * 判定：开关 + 作用域 + inode 命中。
 * 作用域仍是必须的，否则 root、管理器、提权中的 ksud 会被自己挡死。
 */
static bool ace_lsm_block_inode(const struct inode *inode)
{
	/*
	 * 顺序很关键：inode_permission 在 path walk 的每一级都会调用，
	 * 是全场最热的 hook。原来的写法一进来就做作用域判定（is_manager()
	 * 走 RCU + 链表，ksu_is_allow_uid_for_current() 再查一次授权表），
	 * 等于给每一次文件路径访问加了两轮链表遍历。
	 *
	 * 先比 (dev, ino) 两个整数：不命中的绝大多数调用在这里就返回，
	 * 只剩一次 READ_ONCE + 一到两次数组比较。作用域判定只留给真正
	 * 命中靶点的极少数调用。
	 */
	if (!ace_hide_enabled())
		return false;
	if (!ace_inode_hidden(inode))
		return false;
	if (!ace_scope_allows())
		return false;
	ace_note_lsm_hit();
	return true;
}

/*
 * LSM 是否已接管路径类判定。
 *
 * 这里刻意不再要求 ace_target_num() > 0：靶点表是开机后才异步刷新的，
 * 而 syscall hook 的注册决定发生在更早的初始化阶段。用「空靶点」当判据
 * 会让 syscall 层误以为自己仍是唯一防线，从而把 openat/statx 全部塞回
 * sys_call_table——那正是要避免的开销。靶点为空时 LSM 本身什么都不做，
 * 两层都放行，语义一致。
 */
bool ace_lsm_ready_for_paths(void)
{
	return ace_lsm_ready && ace_lsm_ok_count > 0;
}

bool ace_lsm_active(void)
{
	return ace_lsm_ready_for_paths();
}

/*
 * path walk 的每一级都会进这里（may_lookup / may_open / may_create）。
 * 返回 -ENOENT 而不是 -EACCES：EACCES 等于承认"这里有东西但你不能看"，
 * ENOENT 才和不存在的路径一致。
 */
static int ace_lsm_inode_permission(struct inode *inode, int mask)
{
	if (ace_lsm_block_inode(inode))
		return -ENOENT;
	return ACE_CALL_ORIG(ACE_LSM_PERM, int (*)(struct inode *, int), inode, mask);
}

/* stat/lstat/fstatat 最终都走 vfs_getattr -> security_inode_getattr */
static int ace_lsm_inode_getattr(const struct path *path)
{
	if (path && path->dentry && ace_lsm_block_inode(d_inode(path->dentry)))
		return -ENOENT;
	return ACE_CALL_ORIG(ACE_LSM_GETATTR, int (*)(const struct path *), path);
}

/* readlink：符号链接本身被隐藏时不返回目标路径 */
static int ace_lsm_inode_readlink(struct dentry *dentry)
{
	if (dentry && ace_lsm_block_inode(d_inode(dentry)))
		return -ENOENT;
	return ACE_CALL_ORIG(ACE_LSM_READLINK, int (*)(struct dentry *), dentry);
}

/* 已解析路径的 open：兜住 inode_permission 覆盖不到的直连 inode 场景 */
static int ace_lsm_file_open(struct file *file)
{
	if (file && ace_lsm_block_inode(file_inode(file)))
		return -ENOENT;
	return ACE_CALL_ORIG(ACE_LSM_OPEN, int (*)(struct file *), file);
}

/* execve/execveat：命中直接不可执行 */
static int ace_lsm_bprm_check(struct linux_binprm *bprm)
{
	if (bprm && bprm->file && ace_lsm_block_inode(file_inode(bprm->file)))
		return -ENOENT;
	return ACE_CALL_ORIG(ACE_LSM_BPRM, int (*)(struct linux_binprm *), bprm);
}

/*
 * 拦住对隐藏进程的 ptrace / process_vm_readv：
 * 检测器拿到 pid 后可以直接读 ksud 的内存，不经过路径检查。
 */
static int ace_lsm_ptrace_check(struct task_struct *child, unsigned int mode)
{
	if (child && ace_hide_enabled() && ace_task_hidden(child) && ace_scope_allows()) {
		ace_note_lsm_hit();
		return -ENOENT;
	}
	return ACE_CALL_ORIG(ACE_LSM_PTRACE, int (*)(struct task_struct *, unsigned int), child, mode);
}

int ace_lsm_init(void)
{
	int i, ok = 0;

	if (ace_lsm_ready)
		return 0;

	/*
	 * 不要调 ksu_lsm_hook_magic_init()：它在 __init 段，late-load 时该段
	 * 早已释放，调用等于跳进已回收的内存。它本身也只打印一行日志。
	 */

	for (i = 0; i < ACE_LSM_NR; i++) {
		if (ksu_register_lsm_hook(&ace_lsm_hooks[i]) == 0)
			ok++;
	}

	ace_lsm_ok_count = ok;
	ace_lsm_ready = ok > 0;
	return ok ? 0 : -1;
}

void ace_lsm_exit(void)
{
	int i;

	if (!ace_lsm_ready)
		return;

	for (i = 0; i < ACE_LSM_NR; i++)
		ksu_unregister_lsm_hook(&ace_lsm_hooks[i]);

	ace_lsm_ok_count = 0;
	ace_lsm_ready = false;
}

void ace_lsm_status(int *ok, int *total)
{
	if (ok)
		*ok = ace_lsm_ok_count;
	if (total)
		*total = ACE_LSM_NR;
}
