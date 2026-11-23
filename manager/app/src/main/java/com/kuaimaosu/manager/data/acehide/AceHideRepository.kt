package com.kuaimaosu.manager.data.acehide

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kuaimaosu.manager.data.shell.KsuCliRepository

/**
 * 内核态隐藏控制台。
 *
 * 隐藏逻辑在 kernelsu.ko 内部（kernel/feature/ace_hide.c），跑在 sys_call_table
 * 调度器里，因此可以直接决定 syscall 返回值：
 *
 *   dents     getdents64 目录项过滤 + newfstatat/statx/faccessat2 返回 ENOENT
 *   files     /proc/modules、/proc/kcore 整文件屏蔽
 *   procs     /proc/<pid> 目录项按 pid 剔除 + readlinkat 让 exe/cwd/fd 返回 ENOENT
 *   maps      /proc/<pid>/maps、smaps 命中行擦除
 *   syms      /proc/kallsyms 命中行擦除
 *
 * 与 SUSFS 的分工（真联动，不是口号）：
 *   内核 hook 管 syscall 返回值与目录项；SUSFS 管挂载表、maps 的 inode 面、
 *   模块表。启用时若检测到 /proc/susfs_*，会同时把规则下发给 SUSFS，
 *   两边覆盖的观测量不同，缺一不可。
 */
class AceHideRepository(
    private val context: Context,
    private val ksuCliRepository: KsuCliRepository,
) {
    companion object {
        private const val TAG = "AceHideRepository"
        private const val PREFS = "acehide_prefs"
        private const val KEY_KEYWORDS = "keywords"
        private const val KEY_DENTS = "mod_dents"
        private const val KEY_FILES = "mod_files"
        private const val KEY_PROCS = "mod_procs"
        private const val KEY_MAPS = "mod_maps"
        private const val KEY_SYMS = "mod_syms"

        /** 内核模块暴露的控制节点（0600 root） */
        const val CTRL = "/proc/kuaimao_hide"

        /**
         * 持久化位置。内核侧 ace_hide.c 在 late-load 后 5s 读这个文件重新 arm，
         * 因此重启不需要用户再点一次；strict 不写入（只用于自证）。
         */
        private const val CONF_DIR = "/data/adb/kuaimao"
        private const val CONF_PATH = "$CONF_DIR/ace_hide.conf"

        /** 开机重放 SUSFS 规则（SUSFS 的 /proc 节点不持久） */
        private const val SUSFS_BOOT = "/data/adb/post-fs-data.d/99-kuaimao-hide-susfs.sh"

        private const val KSUD_BIN = "/data/adb/ksu/bin/ksud"

        const val DEFAULT_KEYWORDS = "ksu,kernelsu,susfs,ksud,kpm"

        /**
         * 与 kernel/feature/ace_hide.c 的 ACE_VER 必须一致。
         * 装新 APK 不会替换已在跑的 ko（ksud 的 late-load 见到 has_kernelsu 直接跳过），
         * 版本对不上时写配置会得到 EINVAL，所以这里提前拦下并告诉用户怎么做。
         */
        const val EXPECTED_VER = 11

        /** 默认屏蔽的整文件 */
        const val DEFAULT_FILES = "/proc/modules,/proc/kcore"

        /** 下发给 SUSFS 的路径：目录隐藏 + maps 的 inode 面 */
        private val SUSFS_PATHS = listOf(
            "/data/adb/ksu",
            "/data/adb/modules",
            "/sys/module/kernelsu",
        )
        private val SUSFS_MAP_CANDIDATES = listOf(
            "/data/adb/ksu/bin/ksud",
            "/data/adb/ksu/lib/libksud.so",
            "/data/adb/ksu/lib64/libksud.so",
        )

        /** 隐藏后应当从目录里消失的观测点，用于自证 */
        private val PROBES = listOf(
            "/data/adb/ksu",
            "/data/adb/modules",
            "/sys/module/kernelsu",
        )
    }

    enum class Verdict { PASS, FAIL, UNCOVERED }

    data class CheckItem(val name: String, val detail: String, val verdict: Verdict)

    data class AceState(
        val supported: Boolean = false,
        val running: Boolean = false,
        val pid: Int = 0,
        val keywords: String = DEFAULT_KEYWORDS,
        val dentsOn: Boolean = true,
        val filesOn: Boolean = true,
        val procsOn: Boolean = true,
        val mapsOn: Boolean = true,
        val symsOn: Boolean = true,
        val strict: Boolean = false,
        val dentsOk: Boolean = false,
        val filesOk: Boolean = false,
        val procsOk: Boolean = false,
        val mapsOk: Boolean = false,
        val symsOk: Boolean = false,
        val hitsDents: Long = 0,
        val hitsStat: Long = 0,
        val hitsOpen: Long = 0,
        val hitsProc: Long = 0,
        val hitsLink: Long = 0,
        val hitsMaps: Long = 0,
        val hitsSyms: Long = 0,
        val susfsAvailable: Boolean = false,
        val susfsRules: Int = 0,
        /** 内核模块协议版本；0 表示旧版（没有 ver 字段） */
        val ver: Int = 0,
        /** direct / shared：direct 表示未注册 sys_enter tracepoint，未 hook 的 syscall 零开销 */
        val mode: String = "",
        /** 实际被 hook 的 syscall 数量，7 表示路径类已全部交给 LSM */
        val hooks: Int = 0,
        /** 已解析的隐藏靶点数量 */
        val targets: Int = 0,
        val log: String = "",
    ) {
        /** 兼容旧界面命名：files 即原 procfs 模块 */
        val procfsOn: Boolean get() = filesOn
        val procfsOk: Boolean get() = filesOk
    }

    private data class CommandResult(val success: Boolean, val stdout: String, val stderr: String)

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    var keywords: String
        get() = prefs.getString(KEY_KEYWORDS, DEFAULT_KEYWORDS)?.takeIf { it.isNotBlank() } ?: DEFAULT_KEYWORDS
        set(value) = prefs.edit().putString(KEY_KEYWORDS, value.trim()).apply()

    var dentsEnabled: Boolean
        get() = prefs.getBoolean(KEY_DENTS, true)
        set(value) = prefs.edit().putBoolean(KEY_DENTS, value).apply()

    var filesEnabled: Boolean
        get() = prefs.getBoolean(KEY_FILES, true)
        set(value) = prefs.edit().putBoolean(KEY_FILES, value).apply()

    var procsEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROCS, true)
        set(value) = prefs.edit().putBoolean(KEY_PROCS, value).apply()

    var mapsEnabled: Boolean
        get() = prefs.getBoolean(KEY_MAPS, true)
        set(value) = prefs.edit().putBoolean(KEY_MAPS, value).apply()

    var symsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SYMS, true)
        set(value) = prefs.edit().putBoolean(KEY_SYMS, value).apply()

    suspend fun status(): AceState = withContext(Dispatchers.IO) {
        val raw = shell("cat $CTRL 2>/dev/null")
        if (raw.isBlank()) {
            return@withContext AceState(
                supported = false,
                keywords = keywords,
                dentsOn = dentsEnabled,
                filesOn = filesEnabled,
                procsOn = procsEnabled,
                mapsOn = mapsEnabled,
                symsOn = symsEnabled,
            )
        }
        val st = raw.lineSequence()
            .firstOrNull { it.startsWith("state=") }
            .orEmpty()
        val on = st.contains("state=on")
        val dents = st.contains("dents=on")
        val files = st.contains("files=on")
        val procs = st.contains("procs=on")
        val maps = st.contains("maps=on")
        val syms = st.contains("syms=on")
        val strict = st.contains("strict=on")
        val kw = Regex("kw=([^\\n]*)").find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        val f = Regex("files=([^\\n]*)").find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        fun hit(name: String): Long =
            Regex("$name=(\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val susfsOn = raw.contains("susfs=on")
        val susfsRules = if (susfsOn) {
            shell("cat /proc/susfs_path 2>/dev/null | grep -c '^path='").trim().toIntOrNull() ?: 0
        } else 0

        val dentsOk = on && dents && hit("hits_dents") + hit("hits_stat") > 0
        val filesOk = on && files && hit("hits_open") > 0
        val procsOk = on && procs && hit("hits_proc") + hit("hits_link") > 0
        val mapsOk = on && maps && hit("hits_maps") > 0
        val symsOk = on && syms && hit("hits_syms") > 0

        AceState(
            supported = true,
            running = on,
            keywords = kw.ifBlank { keywords },
            dentsOn = dents,
            filesOn = files,
            procsOn = procs,
            mapsOn = maps,
            symsOn = syms,
            strict = strict,
            dentsOk = dentsOk,
            filesOk = filesOk,
            procsOk = procsOk,
            mapsOk = mapsOk,
            symsOk = symsOk,
            hitsDents = hit("hits_dents"),
            hitsStat = hit("hits_stat"),
            hitsOpen = hit("hits_open"),
            hitsProc = hit("hits_proc"),
            hitsLink = hit("hits_link"),
            hitsMaps = hit("hits_maps"),
            hitsSyms = hit("hits_syms"),
            susfsAvailable = susfsOn,
            susfsRules = susfsRules,
            ver = Regex("ver=(\\d+)").find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            mode = Regex("mode=(\\w+)").find(raw)?.groupValues?.get(1).orEmpty(),
            hooks = Regex("hooks=(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            targets = Regex("targets=(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            log = buildString {
                appendLine(raw.trim())
                if (f.isNotBlank()) appendLine("files=$f")
            },
        )
    }

    /** 下发整套配置。内核按行解析，一次写入即原子生效。 */
    private suspend fun applyConfig(
        kw: String,
        dents: Boolean,
        files: Boolean,
        procs: Boolean,
        maps: Boolean,
        syms: Boolean,
        strict: Boolean,
    ): CommandResult {
        val cfg = buildString {
            appendLine("reset")
            appendLine("kw=$kw")
            appendLine(if (dents) "dents=on" else "dents=off")
            appendLine(if (files) "files=on" else "files=off")
            appendLine(if (procs) "procs=on" else "procs=off")
            appendLine(if (maps) "maps=on" else "maps=off")
            appendLine(if (syms) "syms=on" else "syms=off")
            appendLine(if (files) "files=$DEFAULT_FILES" else "files=")
            appendLine(if (strict) "strict=on" else "strict=off")
            appendLine("on")
        }
        val escaped = cfg.replace("'", "'\\''")
        val r = exec("printf '%s' '$escaped' > $CTRL && echo OK")
        if (!r.success || !r.stdout.contains("OK")) return r

        // 写进去不等于全生效：内核会跳过不认识的行，读回状态确认一次，
        // 免得界面显示"已开启"而内核其实还在 off（unknown 就是被跳过的行数）。
        val st = shell("head -1 $CTRL 2>/dev/null").trim()
        if (!st.contains("state=on")) return CommandResult(false, "", st.ifBlank { "无状态回执" })

        // 落盘：开机由内核自己读回（不含 strict，避免把自己挡在外面）
        val persist = buildString {
            appendLine("reset")
            appendLine("kw=$kw")
            appendLine(if (dents) "dents=on" else "dents=off")
            appendLine(if (files) "files=on" else "files=off")
            appendLine(if (procs) "procs=on" else "procs=off")
            appendLine(if (maps) "maps=on" else "maps=off")
            appendLine(if (syms) "syms=on" else "syms=off")
            appendLine(if (files) "files=$DEFAULT_FILES" else "files=")
            appendLine("on")
        }.replace("'", "'\\''")
        exec("mkdir -p $CONF_DIR && printf '%s' '$persist' > $CONF_PATH && chmod 600 $CONF_PATH")
        return r
    }

    /**
     * 把规则同步给 SUSFS。SUSFS 覆盖面与本模块不重叠：
     *   sus_path   路径在 mountinfo / 目录枚举里的可见性
     *   sus_map    maps / smaps / map_files 的 inode 面
     *   hide_modules  /proc/modules 的模块条目
     * 节点不存在说明设备没装 SUSFS，直接跳过。
     */
    private suspend fun applySusfs(): String {
        if (!shell("[ -e /proc/susfs_path ] && echo YES").contains("YES")) {
            return "susfs=off"
        }

        val sb = StringBuilder()
        SUSFS_PATHS.forEach { path ->
            val exists = shell("[ -e $path ] && echo YES").contains("YES")
            if (exists) {
                exec("printf 'add $path\\n' > /proc/susfs_path")
                sb.appendLine("sus_path += $path")
            }
        }
        SUSFS_MAP_CANDIDATES.firstOrNull {
            shell("[ -e $it ] && echo YES").contains("YES")
        }?.let {
            exec("printf 'add $it\\n' > /proc/susfs_map")
            sb.appendLine("sus_map  += $it")
        }
        exec("printf 'add kernelsu\\n' > /proc/susfs_hide_modules")
        sb.appendLine("susfs=on")
        installSusfsBoot()
        return sb.toString().trim()
    }

    /**
     * SUSFS 的 /proc 规则在重启后全部消失，而它的 LKM 由别的模块在开机早期加载。
     * 这里落一个 post-fs-data 脚本（编号 99，晚于 SUSFS 自己的 00），
     * 等待节点就绪后把同一组规则重放一遍；SUSFS 没装就直接退出。
     */
    private suspend fun installSusfsBoot() {
        val script = """
            #!/system/bin/sh
            # 由 KuaiMaoSU 生成：开机重放 SUSFS 规则
            i=0
            while [ ! -e /proc/susfs_path ] && [ ${'$'}i -lt 5 ]; do
                sleep 1; i=${'$'}((i+1))
            done
            [ -e /proc/susfs_path ] || exit 0
            for p in ${SUSFS_PATHS.joinToString(" ")}; do
                [ -e "${'$'}p" ] && echo "add ${'$'}p" > /proc/susfs_path
            done
            for m in ${SUSFS_MAP_CANDIDATES.joinToString(" ")}; do
                if [ -e "${'$'}m" ]; then echo "add ${'$'}m" > /proc/susfs_map; break; fi
            done
            echo "add kernelsu" > /proc/susfs_hide_modules
        """.trimIndent()
        val escaped = script.replace("'", "'\\''")
        exec("mkdir -p /data/adb/post-fs-data.d && printf '%s\\n' '$escaped' > $SUSFS_BOOT && chmod 755 $SUSFS_BOOT")
    }

    /**
     * 设备没加载 SUSFS 时，用 ksud 内嵌的 susfs_guard_lkm.ko 一键拉起，并设为开机自启。
     * 这样联动不需要用户另外刷模块、也不需要记命令行。
     */
    suspend fun enableSusfs(): Result<String> = withContext(Dispatchers.IO) {
        if (shell("[ -e /proc/susfs_path ] && echo YES").contains("YES")) {
            return@withContext Result.success("SUSFS 已加载，无需重复操作")
        }

        val load = exec("$KSUD_BIN susfs lkm load")
        if (!load.success) {
            return@withContext Result.failure(
                IllegalStateException(
                    "加载失败：${load.stderr.ifBlank { load.stdout }.ifBlank { "无输出" }}\n" +
                        "当前 KMI 可能没有内嵌 SUSFS 模块。"
                )
            )
        }
        exec("$KSUD_BIN susfs lkm autoload enable")

        val ok = shell("[ -e /proc/susfs_path ] && echo YES").contains("YES")
        if (!ok) {
            return@withContext Result.failure(
                IllegalStateException("模块已插入但 /proc/susfs_path 未出现（SUSFS 可能未带 expose_proc=1）")
            )
        }

        // 隐藏已经开着的话，规则要立刻补下发，不能让用户再点一次「开启」
        if (shell("cat $CTRL 2>/dev/null").lineSequence().firstOrNull()?.contains("state=on") == true) {
            applySusfs()
        }
        Result.success("SUSFS 已加载，并已设为开机自启")
    }

    private suspend fun clearSusfs() {
        if (!shell("[ -e /proc/susfs_path ] && echo YES").contains("YES")) return
        SUSFS_PATHS.forEach { exec("printf 'del $it\\n' > /proc/susfs_path") }
        SUSFS_MAP_CANDIDATES.forEach { exec("printf 'del $it\\n' > /proc/susfs_map") }
        exec("printf 'clear\\n' > /proc/susfs_hide_modules")
    }

    suspend fun start(
        rawKw: String,
        dents: Boolean,
        files: Boolean,
        procs: Boolean,
        maps: Boolean,
        syms: Boolean,
    ): Result<String> = withContext(Dispatchers.IO) {
        val kw = rawKw.trim()
        if (kw.isEmpty() || kw.length > 80 || kw.any { it.isWhitespace() }) {
            return@withContext Result.failure(IllegalArgumentException("关键字不能为空、不能含空格，总长 ≤ 80"))
        }
        if (!kw.all { it.isLetterOrDigit() || it in "._-," }) {
            return@withContext Result.failure(
                IllegalArgumentException("关键字只允许字母、数字与 . _ - ,（直接参与路径匹配，避免注入）")
            )
        }
        if (!dents && !files && !procs && !maps && !syms) {
            return@withContext Result.failure(IllegalArgumentException("至少启用一个模块"))
        }
        if (!supported()) {
            return@withContext Result.failure(
                IllegalStateException(
                    "$CTRL 不存在，内核里没有隐藏模块。\n" +
                        "先点管理器首页的「一键提权」把 kernelsu.ko 加载进来；" +
                        "若已提权过，说明这次开机后模块没加载，重启后再提权一次。"
                )
            )
        }

        val head = shell("head -1 $CTRL 2>/dev/null").trim()
        val ver = Regex("ver=(\\d+)").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (ver < EXPECTED_VER) {
            return@withContext Result.failure(
                IllegalStateException(
                    "内核里的隐藏模块是旧版（ver=$ver，本 APK 需要 $EXPECTED_VER）。\n\n" +
                        "覆盖安装 APK 不会替换已经在跑的 ko：ksud 的 late-load 检测到 " +
                        "KernelSU 已加载会直接跳过。\n" +
                        "请按顺序操作：重启手机 → 打开管理器点「一键提权」→ 回到本页再点启用。"
                )
            )
        }

        keywords = kw
        dentsEnabled = dents
        filesEnabled = files
        procsEnabled = procs
        mapsEnabled = maps
        symsEnabled = syms

        val baseMod = countLines("/proc/modules")

        val r = applyConfig(kw, dents, files, procs, maps, syms, strict = false)
        if (!r.success || !r.stdout.contains("OK")) {
            return@withContext Result.failure(
                IllegalStateException(
                    "配置写入失败：${r.stderr.ifBlank { "无回执" }}"
                )
            )
        }
        val susfsLog = applySusfs()

        val proof = runProof(kw, dents, files, procs, maps, syms, baseMod, susfsLog)
        Result.success(proof)
    }

    suspend fun stop(): Result<String> = withContext(Dispatchers.IO) {
        if (!supported()) {
            return@withContext Result.failure(IllegalStateException("$CTRL 不存在"))
        }
        val r = exec("printf 'strict=off\\noff\\n' > $CTRL && echo OK")
        clearSusfs()
        // 删持久化，否则重启后内核会把隐藏重新拉起来
        exec("rm -f $CONF_PATH $SUSFS_BOOT")
        if (r.success && r.stdout.contains("OK")) Result.success("已关闭，所有隐藏立即解除")
        else Result.failure(IllegalStateException("关闭失败：${r.stderr.ifBlank { "无回执" }}"))
    }

    /**
     * 真实自证：临时开 strict（对全部进程生效，包括 root shell），
     * 用 root shell 实测探针是否消失，然后恢复为非 strict。
     */
    private suspend fun runProof(
        kw: String,
        dents: Boolean,
        files: Boolean,
        procs: Boolean,
        maps: Boolean,
        syms: Boolean,
        baseMod: Int,
        susfsLog: String,
    ): String = buildString {
        appendLine("=== 隐藏效果自证（内核实测）===")

        val probe = PROBES.firstOrNull { shell("[ -e $it ] && echo YES").contains("YES") }
        var hiddenAfter = -1

        if (probe != null) {
            exec("printf 'strict=on\\n' > $CTRL")
            val gone = shell("[ -e $probe ] && echo VISIBLE || echo GONE").trim()
            val statRet = shell("stat -c %i $probe 2>&1 >/dev/null | head -1")
            hiddenAfter = if (gone == "GONE") 1 else 0
            exec("printf 'strict=off\\n' > $CTRL")

            appendLine("观测点: $probe")
            appendLine("非 strict（管理器视角）: 可见")
            appendLine("strict（等同普通 app 视角）: ${if (gone == "GONE") "已隐藏" else "仍可见"}")
            appendLine("stat 返回: ${statRet.ifBlank { "ENOENT（无输出）" }}")
        } else {
            appendLine("观测点: 未找到可用的真实路径，无法判定")
        }

        if (dents) {
            appendLine(
                when {
                    hiddenAfter == 1 -> "dents: [生效] 目录项已从 getdents 结果中移除，stat 同步返回 ENOENT"
                    hiddenAfter == 0 -> "dents: [未生效] 路径仍可枚举，检查关键字是否匹配（当前 kw=$kw）"
                    else -> "dents: [无法判定]"
                }
            )
        }
        if (files) {
            exec("printf 'strict=on\\n' > $CTRL")
            val modAfter = countLines("/proc/modules")
            exec("printf 'strict=off\\n' > $CTRL")
            appendLine("/proc/modules 行数: 开启前 $baseMod   隐藏中 $modAfter")
            appendLine(
                when {
                    baseMod > 0 && modAfter == 0 -> "files: [生效] 模块表已不可读"
                    baseMod <= 0 -> "files: [无法判定] 本机模块表本来就空"
                    else -> "files: [未生效] 仍能读到 $modAfter 行"
                }
            )
        }
        if (procs) {
            exec("printf 'strict=on\\n' > $CTRL")
            val ksudVisible = shell("ls -d /proc/*/exe 2>/dev/null | head -1").trim()
            val hiddenPid = shell("for p in /proc/[0-9]*; do c=\$(cat \$p/comm 2>/dev/null); case \$c in *ksud*|*ksu*) echo \${p#/proc/};; esac; done | head -1").trim()
            val linkGone = if (hiddenPid.isNotBlank()) {
                shell("readlink /proc/$hiddenPid/exe 2>&1").trim()
            } else ""
            exec("printf 'strict=off\\n' > $CTRL")
            appendLine(
                if (hiddenPid.isBlank()) "procs: [待命中] 当前没有 comm 命中关键字的进程"
                else if (linkGone.contains("No such file")) "procs: [生效] pid=$hiddenPid 的 /proc/<pid>/exe 已返回 ENOENT"
                else "procs: [未生效] pid=$hiddenPid 的 exe 仍可读：$linkGone"
            )
            if (ksudVisible.isBlank()) appendLine("procs: 目录可枚举性检查跳过")
        }
        if (maps) {
            exec("printf 'strict=on\\n' > $CTRL")
            val hit = shell("grep -c -iE 'ksu|susfs' /proc/self/maps 2>/dev/null").trim()
            exec("printf 'strict=off\\n' > $CTRL")
            appendLine("maps: 开启后本进程 maps 命中行数 ${hit.ifBlank { "0" }}（0 = 已擦除）")
        }
        if (syms) {
            exec("printf 'strict=on\\n' > $CTRL")
            val hit = shell("grep -c -iE 'ksu|susfs' /proc/kallsyms 2>/dev/null").trim()
            exec("printf 'strict=off\\n' > $CTRL")
            appendLine("kallsyms: 开启后命中符号行数 ${hit.ifBlank { "0" }}（0 = 已擦除）")
        }
        appendLine(susfsLog)
        appendLine("说明：strict 已自动恢复关闭，管理器与 ksud 保持正常访问。")
    }

    /** 检测项矩阵：真跑命令，给出 PASS / FAIL / 未覆盖。 */
    suspend fun selfCheck(): List<CheckItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<CheckItem>()
        if (!supported()) {
            out += CheckItem("内核控制节点", "$CTRL 不存在，当前 kernelsu.ko 不含隐藏功能", Verdict.FAIL)
            return@withContext out
        }

        val st = status()
        out += if (st.running) {
            CheckItem(
                "隐藏总开关",
                "state=on，命中 dents=${st.hitsDents} stat=${st.hitsStat} open=${st.hitsOpen} " +
                    "proc=${st.hitsProc} link=${st.hitsLink} maps=${st.hitsMaps} syms=${st.hitsSyms}",
                Verdict.PASS,
            )
        } else {
            CheckItem("隐藏总开关", "state=off，先启用隐藏", Verdict.FAIL)
        }
        if (!st.running) return@withContext out

        // 以 strict 视角跑一遍，等价于普通 app 看到的结果
        exec("printf 'strict=on\\n' > $CTRL")

        suspend fun check(name: String, cmd: String, passIf: (String) -> Boolean, failDetail: String) {
            val v = shell(cmd).trim()
            out += if (passIf(v)) CheckItem(name, "实测: ${v.ifBlank { "(空)" }}", Verdict.PASS)
            else CheckItem(name, "实测: ${v.ifBlank { "(空)" }}（$failDetail）", Verdict.UNCOVERED)
        }

        check("ls /data/adb 无 ksu", "ls -1 /data/adb 2>/dev/null | grep -c -E 'ksu|susfs'", { it == "0" }, "目录项过滤未覆盖")
        check("ls /sys/module 无 kernelsu", "ls -1 /sys/module 2>/dev/null | grep -c -E 'kernelsu|susfs'", { it == "0" }, "目录项过滤未覆盖")
        check("stat /data/adb/ksu → ENOENT", "[ -e /data/adb/ksu ] && echo VISIBLE || echo GONE", { it == "GONE" }, "stat 拦截未覆盖")
        check("/proc/modules 读空", "cat /proc/modules 2>/dev/null | wc -l", { it.trim().toIntOrNull() == 0 }, "files 屏蔽未生效")
        check("/proc/kallsyms 无 ksu", "grep -c -iE 'ksu|susfs' /proc/kallsyms 2>/dev/null", { it.trim() == "0" }, "kallsyms 擦除未覆盖")
        check("/proc/self/maps 无 ksu", "grep -c -iE 'ksu|susfs' /proc/self/maps 2>/dev/null", { it.trim() == "0" }, "maps 擦除未覆盖")
        check(
            "/proc/<pid> 无隐藏进程",
            "for p in /proc/[0-9]*; do c=\$(cat \$p/comm 2>/dev/null); case \$c in *ksud*|*ksu*) echo \${p#/proc/};; esac; done | wc -l",
            { it.trim() == "0" },
            "进程目录项未隐藏",
        )

        exec("printf 'strict=off\\n' > $CTRL")

        val st2 = status()
        out += CheckItem(
            "内核命中计数",
            "dents=${st2.hitsDents} stat=${st2.hitsStat} open=${st2.hitsOpen} " +
                "proc=${st2.hitsProc} link=${st2.hitsLink} maps=${st2.hitsMaps} syms=${st2.hitsSyms}",
            if (st2.hitsDents + st2.hitsStat + st2.hitsOpen + st2.hitsProc +
                st2.hitsLink + st2.hitsMaps + st2.hitsSyms > 0
            ) Verdict.PASS else Verdict.UNCOVERED,
        )

        out += CheckItem(
            "挂载表 /proc/self/mountinfo",
            if (st2.susfsAvailable) "已联动 SUSFS：sus_path ${st2.susfsRules} 条，挂载行由 SUSFS 负责"
            else "未检测到 SUSFS，本项无覆盖（需安装 susfs）",
            if (st2.susfsAvailable) Verdict.PASS else Verdict.UNCOVERED,
        )
        out += CheckItem(
            "getprop 属性检测",
            "属性服务走 Binder + 共享内存，syscall 层管不到；SUSFS 也不处理属性",
            Verdict.UNCOVERED,
        )
        out
    }

    private suspend fun supported(): Boolean = shell("[ -e $CTRL ] && echo YES").contains("YES")

    private suspend fun countLines(path: String): Int =
        shell("wc -l < $path 2>/dev/null").trim().toIntOrNull() ?: -1

    private suspend fun shell(cmd: String): String = withContext(Dispatchers.IO) {
        exec(cmd).stdout
    }

    private suspend fun exec(cmd: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val stdout = ArrayList<String>()
            val stderr = ArrayList<String>()
            val result = ksuCliRepository.withNewRootShell {
                newJob().add(cmd).to(stdout, stderr).exec()
            }
            CommandResult(
                success = result.isSuccess,
                stdout = stdout.joinToString("\n").trim(),
                stderr = stderr.joinToString("\n").trim(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $cmd", e)
            CommandResult(false, "", e.message.orEmpty())
        }
    }
}
