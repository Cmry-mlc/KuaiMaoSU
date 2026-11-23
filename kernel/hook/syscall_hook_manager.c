#include "linux/printk.h"
#include <linux/spinlock.h>
#include <linux/kprobes.h>
#include <linux/tracepoint.h>
#include <asm/syscall.h>
#include <linux/ptrace.h>
#include <linux/slab.h>
#include <trace/events/syscalls.h>

#include <linux/version.h>
#if LINUX_VERSION_CODE >= KERNEL_VERSION(4, 11, 0)
#include <linux/sched/task_stack.h>
#endif
#include <linux/compat.h>

#include "compat/syscall_no.h"

#include "arch.h"
#include "klog.h" // IWYU pragma: keep
#include "hook/syscall_hook_manager.h"
#include "hook/tp_marker.h"
#include "feature/sucompat.h"
#include "hook/setuid_hook.h"
#include "hook/syscall_hook.h"
#include "hook/syscall_event_bridge.h"
#include "feature/ace_hide.h"
#if defined(__riscv)
#include "hook/riscv64/syscall_regs.h"
#endif

#ifdef CONFIG_KRETPROBES

static struct kretprobe *init_kretprobe(const char *name, kretprobe_handler_t handler)
{
    struct kretprobe *rp = kzalloc(sizeof(struct kretprobe), GFP_KERNEL);
    if (!rp)
        return NULL;
    rp->kp.symbol_name = name;
    rp->handler = handler;
    rp->data_size = 0;
    rp->maxactive = 0;

    int ret = register_kretprobe(rp);
    pr_info("hook_manager: register_%s kretprobe: %d\n", name, ret);
    if (ret) {
        kfree(rp);
        return NULL;
    }

    return rp;
}

static void destroy_kretprobe(struct kretprobe **rp_ptr)
{
    struct kretprobe *rp = *rp_ptr;
    if (!rp)
        return;
    unregister_kretprobe(rp);
    synchronize_rcu();
    kfree(rp);
    *rp_ptr = NULL;
}

static int syscall_regfunc_handler(struct kretprobe_instance *ri, struct pt_regs *regs)
{
    unsigned long flags;
    ksu_tp_marker_lock(&flags);
    if (ksu_tp_marker_reg_count() < 1) {
        // while install our tracepoint, mark our processes
        ksu_mark_running_process_locked();
    } else if (ksu_tp_marker_reg_count() == 1) {
        // while other tracepoint first added, mark all processes
        ksu_mark_all_process();
    }
    ksu_tp_marker_inc_reg_count();
    ksu_tp_marker_unlock(&flags);
    return 0;
}

static int syscall_unregfunc_handler(struct kretprobe_instance *ri, struct pt_regs *regs)
{
    unsigned long flags;
    ksu_tp_marker_lock(&flags);
    ksu_tp_marker_dec_reg_count();
    if (ksu_tp_marker_reg_count() <= 0) {
        // while no tracepoint left, unmark all processes
        ksu_unmark_all_process();
    } else if (ksu_tp_marker_reg_count() == 1) {
        // while just our tracepoint left, unmark disallowed processes
        ksu_mark_running_process_locked();
    }
    ksu_tp_marker_unlock(&flags);
    return 0;
}

static struct kretprobe *syscall_regfunc_rp = NULL;
static struct kretprobe *syscall_unregfunc_rp = NULL;
#endif

#ifdef CONFIG_HAVE_SYSCALL_TRACEPOINTS
// sys_enter handler: redirect hooked syscalls to the dispatcher
static void ksu_sys_enter_handler(void *data, struct pt_regs *regs, long id)
{
// we need handle arm64 kernel with arm32 userspace
// clang-format off
#ifdef CONFIG_COMPAT
    #if defined(__x86_64__)
        if (unlikely(in_compat_syscall()))
            return;
    #elif defined(__riscv)
        if (unlikely(is_compat_task()))
            return;
    #elif defined(__aarch64__)
        if (unlikely(is_compat_task()))
            goto aarch64_compat;
    #else
        #error Unsupported arch
    #endif
#endif
    // clang-format on

    if (ksu_dispatcher_nr < 0)
        return;

    if (ksu_has_syscall_hook(id)) {
        struct pt_regs *current_regs = task_pt_regs(current);

#if defined(__x86_64__)
        // Stash the original syscall number in ax.
        // We use ax because it currently just holds -ENOSYS and is safe to overwrite.
        current_regs->ax = id;
        current_regs->orig_ax = ksu_dispatcher_nr;
#elif defined(__aarch64__)
        PT_REGS_ORIG_SYSCALL(current_regs) = id;
        current_regs->syscallno = ksu_dispatcher_nr;
#elif defined(__riscv)
        /* orig_a0 retains argument zero; a0 is the -ENOSYS return slot. */
        ksu_riscv_redirect_syscall(current_regs, id, ksu_dispatcher_nr);
#endif
    }

    return;
#if defined(__aarch64__) && defined(CONFIG_COMPAT)
aarch64_compat:
    if (ksu_compat_dispatcher_nr < 0)
        return;

    if (ksu_has_compat_syscall_hook(id)) {
        struct pt_regs *current_regs = task_pt_regs(current);

        /* arm32 syscall numbers are passed in r7, not arm64 x8. */
        PT_REGS_ORIG_SYSCALL(current_regs) = (u32)id;
        current_regs->syscallno = ksu_compat_dispatcher_nr;
    }
#endif
}
#endif

void __init ksu_syscall_hook_manager_init(void)
{
    int ret;
    pr_info("hook_manager: ksu_hook_manager_init called\n");

#ifdef CONFIG_KRETPROBES
    syscall_regfunc_rp = init_kretprobe("syscall_regfunc", syscall_regfunc_handler);
    syscall_unregfunc_rp = init_kretprobe("syscall_unregfunc", syscall_unregfunc_handler);
#endif

    // Register syscall hooks via dispatcher
    ksu_register_syscall_hook(__NR_setresuid, ksu_hook_setresuid);
    ksu_register_syscall_hook(__NR_execve, ksu_hook_execve);
    ksu_register_syscall_hook(__NR_execveat, ksu_hook_execveat);
    ksu_register_syscall_hook(__NR_newfstatat, ksu_hook_newfstatat);
    ksu_register_syscall_hook(__NR_faccessat, ksu_hook_faccessat);

    /*
     * 隐藏的入口分两类：
     *
     *   路径类（openat/openat2/statx/faccessat2/readlinkat）
     *     LSM 的 inode_permission / inode_getattr / inode_readlink 已经在
     *     内核自己的 path walk 里把 (dev,ino) 判掉了，不需要再往
     *     sys_call_table 里塞 trampoline。每少一个 hook，对应的 syscall
     *     就少一次跳转 + 一次间接调用 —— syscall 时延检测测的正是这个。
     *
     *   内容类（getdents64 / read）
     *     LSM 覆盖不到目录项列举和文件内容，只能留在 syscall 层。
     */
    ksu_register_syscall_hook(__NR_getdents64, ace_hook_getdents64);
    ksu_register_syscall_hook(__NR_read, ace_hook_read);

    if (!ace_lsm_ready_for_paths()) {
        pr_warn("hook_manager: LSM unavailable, fall back to syscall path hooks\n");
        ksu_register_syscall_hook(__NR_openat, ace_hook_openat);
#ifdef __NR_openat2
        ksu_register_syscall_hook(__NR_openat2, ace_hook_openat2);
#endif
#ifdef __NR_statx
        ksu_register_syscall_hook(__NR_statx, ace_hook_statx);
#endif
#ifdef __NR_faccessat2
        ksu_register_syscall_hook(__NR_faccessat2, ace_hook_faccessat2);
#endif
    }

#if defined(CONFIG_COMPAT) && defined(__aarch64__)
    ksu_register_compat_syscall_hook(ksu_get_compat_syscall_no(setresuid32), ksu_hook_setresuid);
    ksu_register_compat_syscall_hook(ksu_get_compat_syscall_no(execve), ksu_hook_execve);
    ksu_register_compat_syscall_hook(ksu_get_compat_syscall_no(execveat), ksu_hook_execveat);
    ksu_register_compat_syscall_hook(ksu_get_compat_syscall_no(fstatat64), ksu_hook_newfstatat);
    ksu_register_compat_syscall_hook(ksu_get_compat_syscall_no(faccessat), ksu_hook_faccessat);
#endif

#ifdef __aarch64__
    /*
     * 所有 hook 已登记，现在决定分发方式。
     * direct 模式下不再注册 sys_enter tracepoint——tracepoint 一旦有人注册，
     * 内核的 static key 就永久打开，每个 syscall 平白多一次 trace 入口，
     * 这正是「syscall 时延」类侧信道检测能测出来的差异。
     */
    ksu_syscall_hook_finalize();
#endif

#ifdef CONFIG_HAVE_SYSCALL_TRACEPOINTS
#ifdef __aarch64__
    if (!ksu_syscall_hook_is_direct()) {
#endif
    ret = register_trace_prio_sys_enter(ksu_sys_enter_handler, NULL, INT_MIN);
#ifndef CONFIG_KRETPROBES
    ksu_mark_running_process_locked();
#endif
    if (ret) {
        pr_err("hook_manager: failed to register sys_enter tracepoint: %d\n", ret);
    } else {
        pr_info("hook_manager: sys_enter tracepoint registered\n");
    }
#ifdef __aarch64__
    } else {
        pr_info("hook_manager: direct mode, tracepoint skipped\n");
    }
#endif
#endif

    ksu_setuid_hook_init();
    ksu_sucompat_init();
}

void __exit ksu_syscall_hook_manager_exit(void)
{
    pr_info("hook_manager: ksu_hook_manager_exit called\n");
#ifdef CONFIG_HAVE_SYSCALL_TRACEPOINTS
    unregister_trace_sys_enter(ksu_sys_enter_handler, NULL);
    tracepoint_synchronize_unregister();
    pr_info("hook_manager: sys_enter tracepoint unregistered\n");
#endif

#ifdef CONFIG_KRETPROBES
    destroy_kretprobe(&syscall_regfunc_rp);
    destroy_kretprobe(&syscall_unregfunc_rp);
#endif

    ksu_unregister_syscall_hook(__NR_setresuid);
    ksu_unregister_syscall_hook(__NR_execve);
    ksu_unregister_syscall_hook(__NR_execveat);
    ksu_unregister_syscall_hook(__NR_newfstatat);
    ksu_unregister_syscall_hook(__NR_faccessat);

    ksu_unregister_syscall_hook(__NR_getdents64);
    ksu_unregister_syscall_hook(__NR_openat);
#ifdef __NR_openat2
    ksu_unregister_syscall_hook(__NR_openat2);
#endif
#ifdef __NR_statx
    ksu_unregister_syscall_hook(__NR_statx);
#endif
#ifdef __NR_faccessat2
    ksu_unregister_syscall_hook(__NR_faccessat2);
#endif
    ksu_unregister_syscall_hook(__NR_readlinkat);
    ksu_unregister_syscall_hook(__NR_read);

#if defined(CONFIG_COMPAT) && defined(__aarch64__)
    ksu_unregister_compat_syscall_hook(ksu_get_compat_syscall_no(setresuid32));
    ksu_unregister_compat_syscall_hook(ksu_get_compat_syscall_no(execve));
    ksu_unregister_compat_syscall_hook(ksu_get_compat_syscall_no(execveat));
    ksu_unregister_compat_syscall_hook(ksu_get_compat_syscall_no(fstatat64));
    ksu_unregister_compat_syscall_hook(ksu_get_compat_syscall_no(faccessat));
#endif

    ksu_syscall_hook_exit();

    ksu_sucompat_exit();
    ksu_setuid_hook_exit();
}
