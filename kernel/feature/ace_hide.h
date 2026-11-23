#ifndef __KSU_H_ACE_HIDE
#define __KSU_H_ACE_HIDE

#include <linux/types.h>
#include <linux/ptrace.h>

struct inode;
struct task_struct;

#define ACE_HIDE_MAX_KW 12
#define ACE_HIDE_KW_LEN 24
#define ACE_HIDE_PATH_LEN 256

/* 总开关：隐藏是否生效。热路径用 READ_ONCE 读取。 */
bool ace_hide_enabled(void);

/* 作用域：当前进程是否允许被隐藏（root / 管理器 / 已授权 app 豁免） */
bool ace_scope_allows(void);

/*
 * inode 是否属于隐藏目标。按 (s_dev, i_ino) 精确匹配，
 * 命中即代表整棵子树不可达（path walk 每一级都会查）。
 */
bool ace_inode_hidden(const struct inode *inode);

/* 进程是否应被视为隐藏（comm 命中关键字表） */
bool ace_task_hidden(const struct task_struct *task);

/* 当前解析到的隐藏靶点数量（0 表示 LSM 层没有可拦的东西） */
int ace_target_num(void);

/* 重新解析隐藏路径的 (dev, ino)；开关打开、配置重置、开机恢复时调用 */
void ace_targets_refresh(void);

/* LSM 层命中计数 */
void ace_note_lsm_hit(void);
long long ace_lsm_hit_count(void);

/* 名字（不含路径）是否命中关键字表 */
bool ace_hide_name_matches(const char *name, int len);

/* 绝对路径的 basename 是否命中关键字表；控制节点自身恒不命中 */
bool ace_hide_path_matches(const char *path);

/* getdents64 结果就地过滤，返回过滤后的字节数（失败时原样返回）。
 * proc_dir 为真时额外按 pid 剔除隐藏进程的 /proc/<pid> 目录项。 */
long ace_hide_filter_dirent64(void __user *dirent, long total, bool proc_dir);

/* 对 newfstatat/statx/faccessat 一族的路径判定：命中则调用方直接返回 -ENOENT */
bool ace_hide_stat_blocked(const struct pt_regs *regs, int path_arg);

/* /proc/modules 一类的整文件屏蔽 */
bool ace_hide_open_blocked(const struct pt_regs *regs, int path_arg);

/* execve/execveat 的路径判定：命中则返回 -ENOENT，不再执行 */
bool ace_hide_exec_blocked(const char __user *filename);

int ace_hide_init(void);
void ace_hide_exit(void);

/* syscall hooks */
long ace_hook_getdents64(int orig_nr, const struct pt_regs *regs);
long ace_hook_openat(int orig_nr, const struct pt_regs *regs);
long ace_hook_openat2(int orig_nr, const struct pt_regs *regs);
long ace_hook_statx(int orig_nr, const struct pt_regs *regs);
long ace_hook_faccessat2(int orig_nr, const struct pt_regs *regs);
long ace_hook_readlinkat(int orig_nr, const struct pt_regs *regs);
long ace_hook_read(int orig_nr, const struct pt_regs *regs);

#endif
