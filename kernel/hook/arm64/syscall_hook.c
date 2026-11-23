#ifdef __aarch64__

#include "../syscall_hook.h"

#include <linux/kallsyms.h>
#include <linux/mutex.h>
#include <linux/version.h>
#include <linux/compat.h>
#include <asm/cacheflush.h>
#include "infra/symbol_resolver.h"
#include "../patch_memory.h"
#include "arch.h"
#include "klog.h" // IWYU pragma: keep

// https://github.com/torvalds/linux/commit/7fe33e9f662c0a2f5110be4afff0a24e0c123540
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 11, 0) || defined(KSU_COMPAT_HAS_NR_COMPAT32_SYSCALLS)
#include <asm/unistd_compat_32.h>
#define __NR_compat_syscalls __NR_compat32_syscalls
#else
#include <asm/unistd.h>
#endif

syscall_fn_t *ksu_syscall_table = NULL;
int ksu_dispatcher_nr = -1;
#ifdef CONFIG_COMPAT
syscall_fn_t *ksu_compat_syscall_table = NULL;
int ksu_compat_dispatcher_nr = -1;
#endif

/*
 * 两种分发模式：
 *   direct   —— 直接改写 sys_call_table[被 hook 的 nr]，不注册 sys_enter
 *                tracepoint。未 hook 的 syscall 零额外开销。
 *   shared   —— 上游的共享 dispatcher：所有被 hook 的 nr 都指向同一个槽位，
 *                靠 sys_enter tracepoint 把 x8 改成 dispatcher_nr。
 *
 * direct 更贵在初始化（每个 nr 一次 text patch），但热路径只多一次跳转；
 * shared 的代价是 tracepoint 一旦注册，static key 打开，**所有** syscall
 * 都要多走一遍 trace 入口——这正是侧信道时延检测能测出来的东西。
 * 所以 finalize 时优先 direct，失败再退回 shared。
 */
// Hook registration table — read with READ_ONCE from dispatcher/trampoline
// context, written with WRITE_ONCE from init/exit context.
static ksu_syscall_hook_fn syscall_hooks[__NR_syscalls];
#ifdef CONFIG_COMPAT
static ksu_syscall_hook_fn compat_syscall_hooks[__NR_compat_syscalls];
#endif

static bool ksu_direct_mode;
static syscall_fn_t syscall_orig[__NR_syscalls];
static unsigned long syscall_hook_bits[BITS_TO_LONGS(__NR_syscalls)];
#ifdef CONFIG_COMPAT
static syscall_fn_t compat_syscall_orig[__NR_compat_syscalls];
static unsigned long compat_syscall_hook_bits[BITS_TO_LONGS(__NR_compat_syscalls)];
#endif

long __nocfi ksu_call_syscall(int nr, const struct pt_regs *regs)
{
#ifdef CONFIG_COMPAT
    if (is_compat_task()) {
        syscall_fn_t fn;

        if (nr < 0 || nr >= __NR_compat_syscalls)
            return -ENOSYS;
        fn = READ_ONCE(compat_syscall_orig[nr]);
        return (fn ? fn : ksu_compat_syscall_table[nr])(regs);
    }
#endif
    if (nr < 0 || nr >= __NR_syscalls)
        return -ENOSYS;
    {
        syscall_fn_t fn = READ_ONCE(syscall_orig[nr]);
        return (fn ? fn : ksu_syscall_table[nr])(regs);
    }
}

/* direct 模式下，被替换的表项统一指向这里；nr 从 regs->syscallno 取回。 */
static long __nocfi ksu_syscall_trampoline(const struct pt_regs *regs)
{
    int nr = (int)regs->syscallno;

#ifdef CONFIG_COMPAT
    if (is_compat_task()) {
        if (likely(nr >= 0 && nr < __NR_compat_syscalls)) {
            ksu_syscall_hook_fn fn = READ_ONCE(compat_syscall_hooks[nr]);
            if (likely(fn))
                return fn(nr, regs);
            if (compat_syscall_orig[nr])
                return compat_syscall_orig[nr](regs);
        }
        return -ENOSYS;
    }
#endif
    if (likely(nr >= 0 && nr < __NR_syscalls)) {
        ksu_syscall_hook_fn fn = READ_ONCE(syscall_hooks[nr]);
        if (likely(fn))
            return fn(nr, regs);
        if (syscall_orig[nr])
            return syscall_orig[nr](regs);
    }
    return -ENOSYS;
}


// Track all hooked syscall entries for restoration.
// Protected by hooked_entries_lock.
struct syscall_hook_entry {
    int nr;
    syscall_fn_t orig;
};

static DEFINE_MUTEX(hooked_entries_lock);
static struct syscall_hook_entry hooked_entries[16];
static int hooked_count = 0;
#ifdef CONFIG_COMPAT
static struct syscall_hook_entry hooked_compat_entries[16];
static int compat_hooked_count = 0;
#endif

static int patch_syscall_table(int nr, syscall_fn_t fn)
{
    if (ksu_syscall_table == NULL)
        return -ENOENT;
    if (nr < 0 || nr >= __NR_syscalls)
        return -EINVAL;

    pr_info("patch syscall %d, 0x%lx -> 0x%lx\n", nr, (unsigned long)READ_ONCE(ksu_syscall_table[nr]),
            (unsigned long)fn);

    if (ksu_patch_text(&ksu_syscall_table[nr], &fn, sizeof(fn), KSU_PATCH_TEXT_FLUSH_DCACHE)) {
        pr_err("patch syscall %d failed\n", nr);
        return -EIO;
    }

    return 0;
}

#ifdef CONFIG_COMPAT
static int patch_compat_syscall_table(int nr, syscall_fn_t fn)
{
    if (ksu_compat_syscall_table == NULL)
        return -ENOENT;
    if (nr < 0 || nr >= __NR_compat_syscalls)
        return -EINVAL;

    pr_info("patch syscall %d, 0x%lx -> 0x%lx\n", nr, (unsigned long)READ_ONCE(ksu_compat_syscall_table[nr]),
            (unsigned long)fn);

    if (ksu_patch_text(&ksu_compat_syscall_table[nr], &fn, sizeof(fn), KSU_PATCH_TEXT_FLUSH_DCACHE)) {
        pr_err("patch syscall %d failed\n", nr);
        return -EIO;
    }

    return 0;
}
#endif

// Direct syscall table patching: overwrite syscall_table[nr] with fn,
// save original to *old, and record for restoration at module exit.
void ksu_syscall_table_hook(int nr, syscall_fn_t fn, syscall_fn_t *old)
{
    if (ksu_syscall_table == NULL)
        return;
    if (nr < 0 || nr >= __NR_syscalls) {
        pr_info("invalid nr: %d\n", nr);
        return;
    }

    mutex_lock(&hooked_entries_lock);

    syscall_fn_t orig = READ_ONCE(ksu_syscall_table[nr]);
    if (old)
        *old = orig;

    // Record for later restoration
    int i;
    bool found = false;
    for (i = 0; i < hooked_count; i++) {
        if (hooked_entries[i].nr == nr) {
            found = true;
            break;
        }
    }
    if (!found) {
        if (hooked_count < ARRAY_SIZE(hooked_entries)) {
            hooked_entries[hooked_count].nr = nr;
            hooked_entries[hooked_count].orig = orig;
            hooked_count++;
        } else {
            pr_warn("hooked_entries full, cannot track syscall %d for restoration\n", nr);
        }
    }

    patch_syscall_table(nr, fn);

    mutex_unlock(&hooked_entries_lock);
}

#ifdef CONFIG_COMPAT
// Direct syscall table patching: overwrite compat_syscall_table[nr] with fn,
// save original to *old, and record for restoration at module exit.
void ksu_compat_syscall_table_hook(int nr, syscall_fn_t fn, syscall_fn_t *old)
{
    if (ksu_compat_syscall_table == NULL)
        return;
    if (nr < 0 || nr >= __NR_compat_syscalls) {
        pr_info("invalid nr: %d\n", nr);
        return;
    }

    mutex_lock(&hooked_entries_lock);

    syscall_fn_t orig = READ_ONCE(ksu_compat_syscall_table[nr]);
    if (old)
        *old = orig;

    // Record for later restoration
    int i;
    bool found = false;
    for (i = 0; i < compat_hooked_count; i++) {
        if (hooked_compat_entries[i].nr == nr) {
            found = true;
            break;
        }
    }
    if (!found) {
        if (compat_hooked_count < ARRAY_SIZE(hooked_compat_entries)) {
            hooked_compat_entries[compat_hooked_count].nr = nr;
            hooked_compat_entries[compat_hooked_count].orig = orig;
            compat_hooked_count++;
        } else {
            pr_warn("hooked_compat_entries full, cannot track syscall %d for restoration\n", nr);
        }
    }

    patch_compat_syscall_table(nr, fn);

    mutex_unlock(&hooked_entries_lock);
}
#endif

// Restore syscall_table[nr] to its original value and remove from tracking list.
void ksu_syscall_table_unhook(int nr)
{
    int i;

    if (ksu_syscall_table == NULL)
        return;
    if (nr < 0 || nr >= __NR_syscalls)
        return;

    mutex_lock(&hooked_entries_lock);

    for (i = 0; i < hooked_count; i++) {
        if (hooked_entries[i].nr == nr) {
            patch_syscall_table(nr, hooked_entries[i].orig);
            // Remove entry by swapping with last
            hooked_entries[i] = hooked_entries[--hooked_count];
            mutex_unlock(&hooked_entries_lock);
            pr_info("unhooked syscall %d\n", nr);
            return;
        }
    }

    mutex_unlock(&hooked_entries_lock);
    pr_warn("syscall %d not found in hooked entries\n", nr);
}

#ifdef CONFIG_COMPAT
// Restore compat_syscall_table[nr] to its original value and remove from tracking list.
void ksu_compat_syscall_table_unhook(int nr)
{
    int i;
    if (ksu_compat_syscall_table == NULL)
        return;
    if (nr < 0 || nr >= __NR_compat_syscalls) {
        pr_info("invalid nr: %d\n", nr);
        return;
    }

    mutex_lock(&hooked_entries_lock);

    for (i = 0; i < compat_hooked_count; i++) {
        if (hooked_compat_entries[i].nr == nr) {
            patch_compat_syscall_table(nr, hooked_compat_entries[i].orig);
            // Remove entry by swapping with last
            hooked_compat_entries[i] = hooked_compat_entries[--compat_hooked_count];
            mutex_unlock(&hooked_entries_lock);
            pr_info("unhooked syscall %d\n", nr);
            return;
        }
    }

    mutex_unlock(&hooked_entries_lock);
    pr_warn("syscall %d not found in hooked entries\n", nr);
}
#endif

static int __init ksu_find_ni_syscall_slots(int *out_slots, int max_slots)
{
    unsigned long ni_syscall;
    int i, count = 0;

    if (!ksu_syscall_table || max_slots <= 0)
        return 0;

    ni_syscall = (unsigned long)ksu_resolve_symbol_for_functable_hook("__arm64_sys_ni_syscall");

    pr_info("sys_ni_syscall: 0x%lx\n", ni_syscall);

    if (!ni_syscall)
        return 0;

    for (i = 0; i < __NR_syscalls && count < max_slots; i++) {
        if ((unsigned long)ksu_syscall_table[i] == ni_syscall) {
            out_slots[count++] = i;
            pr_info("ni_syscall %d: %d\n", count, i);
        }
    }

    return count;
}

#ifdef CONFIG_COMPAT
static int __init ksu_compat_find_ni_syscall_slots(int *out_slots, int max_slots)
{
    unsigned long ni_syscall;
    int i, count = 0;

    if (!ksu_compat_syscall_table || max_slots <= 0)
        return 0;

    ni_syscall = (unsigned long)ksu_resolve_symbol_for_functable_hook("__arm64_sys_ni_syscall");

    pr_info("compat_sys_ni_syscall: 0x%lx\n", ni_syscall);

    if (!ni_syscall)
        return 0;

    for (i = 0; i < __NR_compat_syscalls && count < max_slots; i++) {
        if ((unsigned long)ksu_compat_syscall_table[i] == ni_syscall) {
            out_slots[count++] = i;
            pr_info("compat_sys_ni_syscall %d: %d\n", count, i);
        }
    }

    return count;
}
#endif

// Unified dispatcher: native calls read the original NR from x8; compat calls
// read it from arm32 r7 (pt_regs.regs[7]).
// Validates that syscallno matches our dispatcher slot (i.e. we redirected it),
// otherwise it's a spurious call — return -ENOSYS.
static long __nocfi ksu_syscall_dispatcher(const struct pt_regs *regs)
{
    int orig_nr;

#ifdef CONFIG_COMPAT
    if (is_compat_task())
        goto compat;
#endif

    if (regs->syscallno != ksu_dispatcher_nr)
        return -ENOSYS;

    orig_nr = (int)PT_REGS_ORIG_SYSCALL(regs);

    if (regs->syscallno == orig_nr)
        return -ENOSYS;

    // Restore registers to original state before dispatching
    ((struct pt_regs *)regs)->syscallno = orig_nr;
    PT_REGS_ORIG_SYSCALL((struct pt_regs *)regs) = orig_nr;

    if (likely(orig_nr >= 0 && orig_nr < __NR_syscalls)) {
        ksu_syscall_hook_fn fn = READ_ONCE(syscall_hooks[orig_nr]);
        if (likely(fn))
            return fn(orig_nr, regs);
    }

    return -ENOSYS;
compat:
#ifdef CONFIG_COMPAT
    if (regs->syscallno != ksu_compat_dispatcher_nr)
        return -ENOSYS;

    /* arm32 uses r7 for the syscall number; x8 is an ordinary argument. */
    orig_nr = (int)(u32)PT_REGS_ORIG_SYSCALL(regs);

    if (regs->syscallno == orig_nr)
        return -ENOSYS;

    // Restore registers to original state before dispatching
    ((struct pt_regs *)regs)->syscallno = orig_nr;

    if (likely(orig_nr >= 0 && orig_nr < __NR_compat_syscalls)) {
        ksu_syscall_hook_fn fn = READ_ONCE(compat_syscall_hooks[orig_nr]);
        if (likely(fn))
            return fn(orig_nr, regs);
    }

    return -ENOSYS;
#else
    return -ENOSYS;
#endif
}

// 注册只记录意图，真正改表推迟到 ksu_syscall_hook_finalize()：
// 只有那时才知道用 direct 还是 shared，也避免注册到一半表处于半改状态。
int ksu_register_syscall_hook(int nr, ksu_syscall_hook_fn fn)
{
    if (nr < 0 || nr >= __NR_syscalls)
        return -EINVAL;
    if (READ_ONCE(syscall_hooks[nr])) {
        pr_warn("syscall hook for nr=%d already registered, skip\n", nr);
        return -EEXIST;
    }
    WRITE_ONCE(syscall_hooks[nr], fn);
    set_bit(nr, syscall_hook_bits);
    pr_info("registered syscall hook for nr=%d\n", nr);
    return 0;
}

#ifdef CONFIG_COMPAT
// Register a handler into the dispatcher's routing table.
// Does not modify the compat syscall table — the dispatcher slot is shared by all hooks.
int ksu_register_compat_syscall_hook(int nr, ksu_syscall_hook_fn fn)
{
    if (nr < 0 || nr >= __NR_compat_syscalls)
        return -EINVAL;
    if (READ_ONCE(compat_syscall_hooks[nr])) {
        pr_warn("compat syscall hook for nr=%d already registered, skip\n", nr);
        return -EEXIST;
    }
    WRITE_ONCE(compat_syscall_hooks[nr], fn);
    set_bit(nr, compat_syscall_hook_bits);
    pr_info("registered compat syscall hook for nr=%d\n", nr);
    return 0;
}
#endif

// Remove a handler from the dispatcher's routing table.
// The syscall table is not touched — only the dispatcher stops routing this nr.
void ksu_unregister_syscall_hook(int nr)
{
    if (nr < 0 || nr >= __NR_syscalls)
        return;
    WRITE_ONCE(syscall_hooks[nr], NULL);
    clear_bit(nr, syscall_hook_bits);
    pr_info("unregistered syscall hook for nr=%d\n", nr);
}

#ifdef CONFIG_COMPAT
// Remove a handler from the dispatcher's routing table.
// The syscall table is not touched — only the dispatcher stops routing this nr.
void ksu_unregister_compat_syscall_hook(int nr)
{
    if (nr < 0 || nr >= __NR_compat_syscalls)
        return;
    WRITE_ONCE(compat_syscall_hooks[nr], NULL);
    clear_bit(nr, compat_syscall_hook_bits);
    pr_info("unregistered compat syscall hook for nr=%d\n", nr);
}
#endif

/*
 * 位图而不是指针数组：__NR_syscalls 个指针是 3.6KB，散在多个 cacheline；
 * 位图只有 64 字节，一个 cacheline 常驻 L1。sys_enter 每个 syscall 都要问
 * 一次这个问题，cache 命中率比指令数重要得多。
 */
/* 当前实际被 hook 的 syscall 数量，用于状态行诊断 */
int ksu_syscall_hook_count(void)
{
    return bitmap_weight(syscall_hook_bits, __NR_syscalls);
}

bool ksu_has_syscall_hook(int nr)
{
    if ((unsigned int)nr >= (unsigned int)__NR_syscalls)
        return false;
    return test_bit(nr, syscall_hook_bits);
}

#ifdef CONFIG_COMPAT
bool ksu_has_compat_syscall_hook(int nr)
{
    if ((unsigned int)nr >= (unsigned int)__NR_compat_syscalls)
        return false;
    return test_bit(nr, compat_syscall_hook_bits);
}
#endif

static int ksu_ni_slot = -1;
#ifdef CONFIG_COMPAT
static int ksu_compat_ni_slot = -1;
#endif

/* 把 direct 尝试过程中已经改掉的表项恢复回去，失败时用 */
static void ksu_restore_patched(void)
{
    int i;

    mutex_lock(&hooked_entries_lock);
    for (i = 0; i < hooked_count; i++) {
        int nr = hooked_entries[i].nr;
        syscall_fn_t orig = hooked_entries[i].orig;

        patch_syscall_table(nr, orig);
        if (nr >= 0 && nr < __NR_syscalls)
            WRITE_ONCE(syscall_orig[nr], NULL);
    }
    hooked_count = 0;
    mutex_unlock(&hooked_entries_lock);
}

/*
 * 逐条替换被 hook 的 syscall 表项。表项数量很少（十几个），
 * 代价是初始化时的几次 text patch，换来的是未 hook 的 syscall 零开销。
 */
static bool ksu_patch_direct(void)
{
    int nr, total = 0;

    if (!ksu_syscall_table)
        return false;

    for (nr = 0; nr < __NR_syscalls; nr++) {
        syscall_fn_t orig;

        if (!test_bit(nr, syscall_hook_bits))
            continue;
        if (nr == ksu_ni_slot) {
            /* 理论上不会：ni 槽位不会被注册，防御一下 */
            pr_warn("refuse to patch ni slot %d\n", nr);
            return false;
        }
        if (hooked_count >= (int)ARRAY_SIZE(hooked_entries)) {
            pr_warn("direct mode: too many hooked entries\n");
            return false;
        }

        orig = READ_ONCE(ksu_syscall_table[nr]);
        if ((unsigned long)orig == (unsigned long)ksu_syscall_trampoline)
            continue;
        if (patch_syscall_table(nr, (syscall_fn_t)ksu_syscall_trampoline))
            return false;

        WRITE_ONCE(syscall_orig[nr], orig);
        hooked_entries[hooked_count].nr = nr;
        hooked_entries[hooked_count].orig = orig;
        hooked_count++;
        total++;
    }

    pr_info("direct mode patched %d syscalls\n", total);
    return total > 0;
}

#ifdef CONFIG_COMPAT
static void ksu_restore_patched_compat(void)
{
    int i;

    mutex_lock(&hooked_entries_lock);
    for (i = 0; i < compat_hooked_count; i++) {
        int nr = hooked_compat_entries[i].nr;

        patch_compat_syscall_table(nr, hooked_compat_entries[i].orig);
        if (nr >= 0 && nr < __NR_compat_syscalls)
            WRITE_ONCE(compat_syscall_orig[nr], NULL);
    }
    compat_hooked_count = 0;
    mutex_unlock(&hooked_entries_lock);
}

static bool ksu_patch_direct_compat(void)
{
    int nr, total = 0;

    if (!ksu_compat_syscall_table)
        return false;

    for (nr = 0; nr < __NR_compat_syscalls; nr++) {
        syscall_fn_t orig;

        if (!test_bit(nr, compat_syscall_hook_bits))
            continue;
        if (nr == ksu_compat_ni_slot)
            return false;
        if (compat_hooked_count >= (int)ARRAY_SIZE(hooked_compat_entries)) {
            pr_warn("direct mode: too many compat hooked entries\n");
            return false;
        }

        orig = READ_ONCE(ksu_compat_syscall_table[nr]);
        if ((unsigned long)orig == (unsigned long)ksu_syscall_trampoline)
            continue;
        if (patch_compat_syscall_table(nr, (syscall_fn_t)ksu_syscall_trampoline))
            return false;

        WRITE_ONCE(compat_syscall_orig[nr], orig);
        hooked_compat_entries[compat_hooked_count].nr = nr;
        hooked_compat_entries[compat_hooked_count].orig = orig;
        compat_hooked_count++;
        total++;
    }

    pr_info("direct mode patched %d compat syscalls\n", total);
    return total > 0;
}
#endif

void __init ksu_syscall_hook_init(void)
{
    int ni_slot;

    memset(syscall_hooks, 0, sizeof(syscall_hooks));
    memset(syscall_orig, 0, sizeof(syscall_orig));
    bitmap_zero(syscall_hook_bits, __NR_syscalls);

    ksu_syscall_table = (syscall_fn_t *)ksu_resolve_symbol_for_functable_hook("sys_call_table");
    pr_info("sys_call_table=0x%lx", (unsigned long)ksu_syscall_table);

    if (ksu_syscall_table && ksu_find_ni_syscall_slots(&ni_slot, 1) >= 1) {
        ksu_ni_slot = ni_slot;
        pr_info("dispatcher fallback slot = %d\n", ni_slot);
    } else {
        pr_err("no ni_syscall slot for dispatcher\n");
    }

#ifdef CONFIG_COMPAT
    memset(compat_syscall_hooks, 0, sizeof(compat_syscall_hooks));
    memset(compat_syscall_orig, 0, sizeof(compat_syscall_orig));
    bitmap_zero(compat_syscall_hook_bits, __NR_compat_syscalls);

    ksu_compat_syscall_table = (syscall_fn_t *)ksu_resolve_symbol_for_functable_hook("compat_sys_call_table");
    pr_info("compat_sys_call_table=0x%lx", (unsigned long)ksu_compat_syscall_table);

    if (ksu_compat_syscall_table &&
        ksu_compat_find_ni_syscall_slots(&ni_slot, 1) >= 1) {
        ksu_compat_ni_slot = ni_slot;
        pr_info("compat dispatcher fallback slot = %d\n", ni_slot);
    }
#endif
}

/*
 * 注册阶段结束后调用：优先 direct，失败退回共享 dispatcher。
 * 返回后 ksu_syscall_hook_is_direct() 决定了是否需要 sys_enter tracepoint。
 */
void __init ksu_syscall_hook_finalize(void)
{
    if (ksu_patch_direct()) {
        ksu_direct_mode = true;
        goto out;
    }

    ksu_restore_patched();

    if (ksu_syscall_table && ksu_ni_slot >= 0) {
        ksu_dispatcher_nr = ksu_ni_slot;
        ksu_syscall_table_hook(ksu_dispatcher_nr, (syscall_fn_t)ksu_syscall_dispatcher, NULL);
        pr_info("shared dispatcher installed at slot %d\n", ksu_dispatcher_nr);
    }

out:
#ifdef CONFIG_COMPAT
    if (ksu_compat_syscall_table && ksu_compat_ni_slot >= 0) {
        if (ksu_patch_direct_compat()) {
            if (!ksu_direct_mode) {
                /* native 没走 direct，但 compat 走了：仍然需要 tracepoint 处理
                 * native 调用，所以把它们一起留在 shared 更可控。 */
                ksu_restore_patched_compat();
                ksu_compat_dispatcher_nr = ksu_compat_ni_slot;
                ksu_compat_syscall_table_hook(ksu_compat_dispatcher_nr,
                                              (syscall_fn_t)ksu_syscall_dispatcher, NULL);
            }
        } else {
            ksu_restore_patched_compat();
            ksu_compat_dispatcher_nr = ksu_compat_ni_slot;
            ksu_compat_syscall_table_hook(ksu_compat_dispatcher_nr,
                                          (syscall_fn_t)ksu_syscall_dispatcher, NULL);
        }
    }
#endif
    pr_info("syscall hook mode: %s\n", ksu_direct_mode ? "direct" : "shared");
}

bool ksu_syscall_hook_is_direct(void)
{
    return ksu_direct_mode;
}

void __exit ksu_syscall_hook_exit(void)
{
    int i;

    if (!ksu_syscall_table)
        goto reset_compat_syscall;

    // First, restore all patched syscall table entries while the dispatcher
    // and hook table are still intact, so in-flight syscalls see valid state.
    mutex_lock(&hooked_entries_lock);
    for (i = 0; i < hooked_count; i++) {
        int nr = hooked_entries[i].nr;
        syscall_fn_t orig = hooked_entries[i].orig;

        pr_info("restore syscall %d to 0x%lx\n", nr, (unsigned long)orig);
        if (ksu_patch_text(&ksu_syscall_table[nr], &orig, sizeof(orig), KSU_PATCH_TEXT_FLUSH_DCACHE)) {
            pr_err("restore syscall %d failed\n", nr);
        }
    }
    hooked_count = 0;
    mutex_unlock(&hooked_entries_lock);

reset_compat_syscall:
#ifdef CONFIG_COMPAT
    if (!ksu_compat_syscall_table)
        goto clear_state;

    // Second, restore all patched syscall table entries while the dispatcher for compat
    // and hook table are still intact, so in-flight syscalls see valid state.
    mutex_lock(&hooked_entries_lock);
    for (i = 0; i < compat_hooked_count; i++) {
        int nr = hooked_compat_entries[i].nr;
        syscall_fn_t orig = hooked_compat_entries[i].orig;

        pr_info("restore syscall %d to 0x%lx\n", nr, (unsigned long)orig);
        if (ksu_patch_text(&ksu_compat_syscall_table[nr], &orig, sizeof(orig), KSU_PATCH_TEXT_FLUSH_DCACHE)) {
            pr_err("restore compat syscall %d failed\n", nr);
        }
    }
    compat_hooked_count = 0;
    mutex_unlock(&hooked_entries_lock);
#endif
clear_state:
    // Now that the syscall table is restored, clear internal state.
    // At this point the tracepoint is already unregistered and synchronized
    // (done by ksu_syscall_hook_manager_exit before calling us), so no new
    // dispatches will occur.
    memset(syscall_hooks, 0, sizeof(syscall_hooks));
    memset(syscall_orig, 0, sizeof(syscall_orig));
    bitmap_zero(syscall_hook_bits, __NR_syscalls);
    ksu_direct_mode = false;
    ksu_dispatcher_nr = -1;
#ifdef CONFIG_COMPAT
    memset(compat_syscall_hooks, 0, sizeof(compat_syscall_hooks));
    memset(compat_syscall_orig, 0, sizeof(compat_syscall_orig));
    bitmap_zero(compat_syscall_hook_bits, __NR_compat_syscalls);
    ksu_compat_dispatcher_nr = -1;
#endif

    pr_info("all syscall hooks restored\n");
}

#endif /* __aarch64__ */
