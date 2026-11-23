#ifndef __KSU_H_ACE_LSM
#define __KSU_H_ACE_LSM

#include <linux/types.h>

/* 在 LSM 层注册隐藏钩子（runtime 替换 bpf_lsm_* 桩）。返回 0 表示至少成功一个。 */
int ace_lsm_init(void);
void ace_lsm_exit(void);

/* LSM 是否真正可用（注册成功且靶点非空）：可用时 syscall 层让位 */
bool ace_lsm_active(void);

/* 只要 LSM 注册成功就算就绪（不看靶点表）：供 syscall 层决定是否让位 */
bool ace_lsm_ready_for_paths(void);

/* 已注册成功的数量 / 总数，用于状态行 */
void ace_lsm_status(int *ok, int *total);

#endif
