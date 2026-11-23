/*
 * ace_hide_test.c — 在 host 上验证 ace_hide.c 的两个核心算法
 *
 * 为什么要跑这个：kernel 侧编过只证明语法与符号对，不证明过滤逻辑对。
 * 这里把 ace_hide_filter_dirent64 与 ace_hide_name_matches 的算法原样搬出来，
 * 用真实构造的 linux_dirent64 缓冲做边界测试。
 *
 * 编译: cc -O1 -Wall -o /tmp/ace_hide_test ace_hide_test.c
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

typedef uint64_t u64;
typedef int64_t s64;

struct ace_dirent64 {
	u64 d_ino;
	s64 d_off;
	unsigned short d_reclen;
	unsigned char d_type;
	char d_name[];
};

/* ---- 与内核实现一致的匹配逻辑 ---- */
static const char *kw[] = { "ksu", "susfs", "kernelsu", "kpm", "ksud" };
#define NKW (int)(sizeof(kw) / sizeof(kw[0]))

/* ---- 与内核实现一致的行擦除逻辑 ---- */
static bool name_matches(const char *name, int len);

static bool scrub_lines(char *buf, long len)
{
	long i, start = 0;
	bool dirty = false;

	for (i = 0; i <= len; i++) {
		if (i == len || buf[i] == '\n') {
			int l = (int)(i - start);
			if (l > 0 && name_matches(buf + start, l)) {
				memset(buf + start, ' ', l);
				dirty = true;
			}
			start = i + 1;
		}
	}
	return dirty;
}

/* ---- 与内核实现一致的 /proc/<pid> 解析（self 用 -1 代表当前进程） ---- */
static bool proc_pid(const char *path, int *out)
{
	const char *p;
	char *end;
	unsigned long pid;

	if (!path || strncmp(path, "/proc/", 6))
		return false;
	p = path + 6;
	if (!strncmp(p, "self/", 5)) {
		*out = -1;
		return true;
	}
	if (*p < '0' || *p > '9')
		return false;
	pid = strtoul(p, &end, 10);
	if (end == p || (*end != '/' && *end != '\0'))
		return false;
	if (pid == 0 || pid > 2147483647UL)
		return false;
	*out = (int)pid;
	return true;
}

static bool substr_ci(const char *hay, int haylen, const char *needle)
{
	int nlen = (int)strlen(needle), i, j;
	if (nlen == 0 || nlen > haylen)
		return false;
	for (i = 0; i + nlen <= haylen; i++) {
		for (j = 0; j < nlen; j++) {
			char a = hay[i + j], b = needle[j];
			if (a >= 'A' && a <= 'Z') a += 'a' - 'A';
			if (b >= 'A' && b <= 'Z') b += 'a' - 'A';
			if (a != b) break;
		}
		if (j == nlen) return true;
	}
	return false;
}

static bool name_matches(const char *name, int len)
{
	if (!name || len <= 0) return false;
	if (len == (int)strlen("kuaimao_hide") && !memcmp(name, "kuaimao_hide", len)) return false;
	for (int i = 0; i < NKW; i++)
		if (substr_ci(name, len, kw[i])) return true;
	return false;
}

/* 返回新长度；total 表示写入量。模拟内核里"读回-压缩-写回"的过程 */
static long filter_dirents(void *buf, long total)
{
	struct ace_dirent64 *src;
	long off = 0, newlen = 0;
	int namelen;

	while (off < total) {
		src = (struct ace_dirent64 *)((char *)buf + off);
		if (src->d_reclen < offsetof(struct ace_dirent64, d_name) + 1 ||
		    off + src->d_reclen > total)
			return total; /* 结构异常：原样返回 */

		namelen = src->d_reclen - (int)offsetof(struct ace_dirent64, d_name);
		namelen = (int)strnlen(src->d_name, namelen);

		if (name_matches(src->d_name, namelen)) {
			/* drop */
		} else {
			if (newlen != off)
				memmove((char *)buf + newlen, src, src->d_reclen);
			newlen += src->d_reclen;
		}
		off += src->d_reclen;
	}
	return newlen;
}

/* ---- 测试脚手架 ---- */
static int failures;

static long append(void *buf, long off, const char *name, u64 ino)
{
	struct ace_dirent64 *d = (struct ace_dirent64 *)((char *)buf + off);
	int nlen = (int)strlen(name) + 1;
	int rec = (8 + 8 + 2 + 1 + nlen + 7) & ~7; /* 8 字节对齐，与内核 filldir 行为一致 */

	memset(d, 0, rec);
	d->d_ino = ino;
	d->d_off = off + rec;
	d->d_reclen = (unsigned short)rec;
	d->d_type = 4;
	memcpy(d->d_name, name, nlen);
	return off + rec;
}

static int count_entries(const void *buf, long total)
{
	const struct ace_dirent64 *d;
	long off = 0;
	int n = 0;
	while (off < total) {
		d = (const struct ace_dirent64 *)((const char *)buf + off);
		n++;
		off += d->d_reclen;
	}
	return n;
}

static int has(const void *buf, long total, const char *name)
{
	const struct ace_dirent64 *d;
	long off = 0;
	while (off < total) {
		d = (const struct ace_dirent64 *)((const char *)buf + off);
		if (!strcmp(d->d_name, name)) return 1;
		off += d->d_reclen;
	}
	return 0;
}

static void expect(int cond, const char *what)
{
	printf("  [%s] %s\n", cond ? "PASS" : "FAIL", what);
	if (!cond) failures++;
}

int main(void)
{
	static char buf[4096];
	long total;

	puts("== 1. 混合目录：只删命中项，其余保留 ==");
	total = 0;
	total = append(buf, total, ".", 1);
	total = append(buf, total, "..", 2);
	total = append(buf, total, "ksu", 1001);
	total = append(buf, total, "modules", 1002);
	total = append(buf, total, "ksud", 1003);
	total = append(buf, total, "app", 1004);
	long before = total;
	long after = filter_dirents(buf, total);
	expect(after < before, "总长度变小");
	expect(!has(buf, after, "ksu"), "ksu 被移除");
	expect(!has(buf, after, "ksud"), "ksud 被移除");
	expect(has(buf, after, "modules"), "modules 保留");
	expect(has(buf, after, "app"), "app 保留");
	expect(count_entries(buf, after) == 4, "剩余条目数 = 4");
	{
		/* 校验压缩后每个 d_reclen 仍然自洽 */
		long off = 0;
		int ok = 1;
		while (off < after) {
			struct ace_dirent64 *d = (struct ace_dirent64 *)(buf + off);
			if (d->d_reclen == 0 || off + d->d_reclen > after) { ok = 0; break; }
			off += d->d_reclen;
		}
		expect(ok, "压缩后链表结构自洽");
	}

	puts("== 2. 全部命中：返回 0 ==");
	total = 0;
	total = append(buf, total, "ksu", 1);
	total = append(buf, total, "susfs", 2);
	total = append(buf, total, "kernelsu", 3);
	expect(filter_dirents(buf, total) == 0, "返回 0（目录读空）");

	puts("== 3. 全不命中：长度不变 ==");
	total = 0;
	total = append(buf, total, "system", 1);
	total = append(buf, total, "vendor", 2);
	expect(filter_dirents(buf, total) == total, "长度不变");

	puts("== 4. 末尾条目被删：剩余长度正确 ==");
	total = 0;
	total = append(buf, total, "app", 1);
	total = append(buf, total, "ksu", 2);
	after = filter_dirents(buf, total);
	expect(after == total - 24, "删掉 24 字节对齐项后长度正确");
	expect(count_entries(buf, after) == 1, "只剩 1 条");

	puts("== 5. 结构损坏：原样返回，不吐半截 ==");
	memset(buf, 0, 64);
	struct ace_dirent64 *bad = (struct ace_dirent64 *)buf;
	bad->d_reclen = 0; /* 非法 */
	expect(filter_dirents(buf, 64) == 64, "d_reclen=0 时原样返回");

	puts("== 6. 大小写不敏感 + 控制节点豁免 ==");
	expect(name_matches("KernelSU", 8), "KernelSU 命中（大小写不敏感）");
	expect(name_matches("KSU", 3), "KSU 命中");
	expect(!name_matches("kuaimao_hide", 12), "控制节点自身不被匹配");
	expect(name_matches("my_susfs_data", 13), "子串命中");
	expect(!name_matches("system", 6), "普通名字不命中");

	puts("== 7. ino 保留：过滤不改变剩余项 dentry 编号 ==");
	total = 0;
	total = append(buf, total, "ksu", 4097);
	total = append(buf, total, "app", 4098);
	after = filter_dirents(buf, total);
	{
		struct ace_dirent64 *d = (struct ace_dirent64 *)buf;
		expect(d->d_ino == 4098, "app 的 ino 仍是 4098（不发生错位）");
	}

	puts("== 8. maps / kallsyms 行擦除：长度不变、行数不变、关键字消失 ==");
	{
		char m[] = "7f00-7f10 r-xp 00000000 fd:00 123 /data/adb/ksu/lib64/libksud.so\n"
			   "7f10-7f20 r-xp 00000000 fd:00 124 /system/lib64/libc.so\n";
		long n = (long)strlen(m);
		bool dirty = scrub_lines(m, n);
		expect(dirty, "有行被擦除");
		expect((long)strlen(m) == n, "总长度不变（read 偏移不错乱）");
		expect(strchr(m, '\n') != NULL, "换行仍保留（行数不变）");
		expect(!strstr(m, "ksu"), "关键字 ksu 已不可见");
		expect(strstr(m, "/system/lib64/libc.so") != NULL, "无关行原样保留");
		expect(m[0] == ' ', "命中行以空格覆盖");
	}
	{
		char k[] = "ffff8000 T __arm64_sys_ksu_hide\nffff8010 T ksu_susfs_init\n"
			   "ffff8020 T do_sys_openat2\n";
		long n = (long)strlen(k);
		expect(scrub_lines(k, n), "kallsyms 有行被擦除");
		expect(!strstr(k, "ksu"), "符号名里的 ksu 已不可见");
		expect(strstr(k, "do_sys_openat2") != NULL, "普通符号保留");
		expect((long)strlen(k) == n, "长度不变");
	}

	puts("== 9. /proc/<pid> 路径解析 ==");
	{
		int pid = 0;
		expect(proc_pid("/proc/123/exe", &pid) && pid == 123, "/proc/123/exe -> 123");
		expect(proc_pid("/proc/4521", &pid) && pid == 4521, "/proc/4521 -> 4521");
		expect(!proc_pid("/proc/abc/exe", &pid), "非数字 pid 拒绝");
		expect(!proc_pid("/proc/0/exe", &pid), "pid 0 拒绝");
		expect(!proc_pid("/data/adb/ksu", &pid), "非 /proc 路径拒绝");
	}

	printf("\n%s（%d 项失败）\n", failures ? "存在失败" : "全部通过", failures);
	return failures ? 1 : 0;
}
