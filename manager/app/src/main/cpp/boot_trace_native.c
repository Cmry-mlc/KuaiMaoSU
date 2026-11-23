// 启动崩溃信号抓取（只读诊断，不改变任何功能逻辑）。
//
// libguava.so 被加载时通过 constructor 自动安装 SIGSEGV/SIGABRT/SIGBUS/SIGFPE/
// SIGILL 的信号处理器。Java 层的 Thread.UncaughtExceptionHandler 抓不到 native
// 崩溃，所以这里在信号发生时把信号名和 pid/tid 追加写到 boot_trace.txt，
// 与 Java 侧 BootTrace.kt 写同一个文件。这样"没有崩溃日志"时也能从
// boot_trace.txt 的最后一行看到是 native 崩溃及死在哪个信号。
//
// 路径固定为外部私有目录；com.kuaimaosu.manager 是本应用的包名。
#define _GNU_SOURCE
#include <fcntl.h>
#include <signal.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/types.h>

#ifndef BOOT_TRACE_PATH
#define BOOT_TRACE_PATH "/sdcard/Android/data/com.kuaimaosu.manager/files/boot_trace.txt"
#endif

static const int CAUGHT_SIGNALS[] = {
    SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP
};
#define CAUGHT_COUNT (sizeof(CAUGHT_SIGNALS) / sizeof(CAUGHT_SIGNALS[0]))

static struct sigaction g_old_actions[CAUGHT_COUNT];

static const char *signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV (native段错误)";
        case SIGABRT: return "SIGABRT (native abort)";
        case SIGBUS: return "SIGBUS";
        case SIGFPE: return "SIGFPE";
        case SIGILL: return "SIGILL";
        case SIGTRAP: return "SIGTRAP";
        default: return "UNKNOWN";
    }
}

static void append_ascii(int fd, const char *s) {
    while (*s) {
        ssize_t n = write(fd, s, 1);
        (void) n;
        s++;
    }
}

static void append_num(int fd, long v) {
    char buf[32];
    int len = 0;
    int neg = v < 0;
    unsigned long uv = neg ? (unsigned long)(-v) : (unsigned long)v;
    if (uv == 0) buf[len++] = '0';
    while (uv > 0) { buf[len++] = (char)('0' + (uv % 10)); uv /= 10; }
    if (neg) buf[len++] = '-';
    for (int i = 0; i < len; i++) {
        ssize_t n = write(fd, &buf[len - 1 - i], 1);
        (void) n;
    }
}

static void crash_handler(int sig) {
    int fd = open(BOOT_TRACE_PATH, O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (fd >= 0) {
        append_ascii(fd, "\n!!! NATIVE CRASH: ");
        append_ascii(fd, signal_name(sig));
        append_ascii(fd, " sig=");
        append_num(fd, sig);
        append_ascii(fd, " pid=");
        append_num(fd, (long) getpid());
        append_ascii(fd, " tid=");
        append_num(fd, (long) gettid());
        append_ascii(fd, " !!!\n");
        close(fd);
    }
    // 恢复默认处理，让进程按系统默认方式终止（产生 tombstone）
    for (size_t i = 0; i < CAUGHT_COUNT; i++) {
        sigaction(CAUGHT_SIGNALS[i], &g_old_actions[i], NULL);
    }
    raise(sig);
}

__attribute__((constructor))
static void install_native_crash_handler(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = crash_handler;
    sigemptyset(&sa.sa_mask);
    for (size_t i = 0; i < CAUGHT_COUNT; i++) {
        sigaction(CAUGHT_SIGNALS[i], &sa, &g_old_actions[i]);
    }
}
