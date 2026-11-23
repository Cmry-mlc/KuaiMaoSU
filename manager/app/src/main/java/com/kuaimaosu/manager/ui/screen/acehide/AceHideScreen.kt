package com.kuaimaosu.manager.ui.screen.acehide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Bolt
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material.icons.twotone.Security
import androidx.compose.material.icons.twotone.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.kuaimaosu.manager.BuildConfig
import com.kuaimaosu.manager.data.acehide.AceHideRepository
import com.kuaimaosu.manager.ui.component.KuaiMaoRow
import com.kuaimaosu.manager.ui.component.KuaiMaoRowDivider
import com.kuaimaosu.manager.ui.component.KuaiMaoSwitch
import com.kuaimaosu.manager.ui.component.rememberLoadingDialog
import com.kuaimaosu.manager.ui.screen.main.KuaiMaoSettingsScaffold
import com.kuaimaosu.manager.ui.screen.susfs.component.SusfsGroupCard
import com.kuaimaosu.manager.ui.screen.susfs.component.SusfsHero
import com.kuaimaosu.manager.ui.screen.susfs.component.SusfsMetric
import com.kuaimaosu.manager.ui.screen.susfs.component.SusfsMonoPanel
import com.kuaimaosu.manager.ui.screen.susfs.component.SusfsPillButton
import com.kuaimaosu.manager.ui.screen.susfs.component.SusfsSectionLabel
import com.kuaimaosu.manager.ui.theme.KuaiMaoColors
import com.kuaimaosu.manager.ui.theme.KuaiMaoTones
import com.kuaimaosu.manager.ui.util.LocalSnackbarHost
import com.kuaimaosu.manager.ui.util.showReplacingSnackbar
import org.koin.compose.koinInject

/**
 * 内核态隐藏控制台：规则下发给 kernelsu.ko 内的 LSM 钩子 + syscall 兜底。
 *
 *  - dents  getdents64 目录项过滤 + stat 族返回 ENOENT（多关键字）
 *  - files  /proc/modules、/proc/kcore 整文件屏蔽
 *  - procs  /proc/<pid> 整棵子树隐身
 *  - maps   maps / smaps 命中行等长擦除
 *  - syms   kallsyms 命中符号行擦除
 *
 * 版式与 SUSFS 各页统一：深色 Hero（状态 + 命中数 + 模块数 + 版本）、
 * 分组卡（拦截面 / 关键字）、胶囊动作条、等宽日志面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AceHideScreen(bottomPadding: Dp = 0.dp) {
    val repository = koinInject<AceHideRepository>()
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current
    val loadingDialog = rememberLoadingDialog()

    var state by remember { mutableStateOf(AceHideRepository.AceState()) }
    var keywords by rememberSaveable { mutableStateOf(AceHideRepository.DEFAULT_KEYWORDS) }
    var dents by rememberSaveable { mutableStateOf(true) }
    var procfs by rememberSaveable { mutableStateOf(true) }
    var procs by rememberSaveable { mutableStateOf(true) }
    var maps by rememberSaveable { mutableStateOf(true) }
    var syms by rememberSaveable { mutableStateOf(true) }
    var proof by remember { mutableStateOf("") }
    var checks by remember { mutableStateOf(listOf<AceHideRepository.CheckItem>()) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        state = repository.status()
        keywords = state.keywords
        dents = state.dentsOn
        procfs = state.filesOn
        procs = state.procsOn
        maps = state.mapsOn
        syms = state.symsOn
    }

    LaunchedEffect(Unit) { refresh() }

    fun snack(msg: String) = scope.launch { snackbarHost.showReplacingSnackbar(msg) }

    fun runStart() {
        if (busy) return
        if (keywords.isBlank()) {
            snack("请先输入关键字")
            return
        }
        busy = true
        scope.launch {
            loadingDialog.show()
            val result = repository.start(keywords, dents, procfs, procs, maps, syms)
            loadingDialog.hide()
            busy = false
            proof = result.getOrElse { it.message ?: "启用失败" }
            refresh()
            snack(result.getOrElse { it.message ?: "启用失败" }.lineSequence().firstOrNull().orEmpty())
        }
    }

    fun runStop() {
        if (busy) return
        busy = true
        scope.launch {
            loadingDialog.show()
            val result = repository.stop()
            loadingDialog.hide()
            busy = false
            proof = ""
            checks = emptyList()
            refresh()
            snack(result.getOrElse { it.message ?: "停止失败" }.lineSequence().firstOrNull().orEmpty())
        }
    }

    fun runCheck() {
        if (busy) return
        busy = true
        scope.launch {
            loadingDialog.show()
            checks = repository.selfCheck()
            loadingDialog.hide()
            busy = false
            val pass = checks.count { it.verdict == AceHideRepository.Verdict.PASS }
            snack("检测矩阵：$pass/${checks.size} 项已阻断")
        }
    }

    val enabledModules = listOf(dents, procfs, procs, maps, syms).count { it }
    val totalHits = state.hitsDents + state.hitsStat + state.hitsOpen + state.hitsProc +
        state.hitsLink + state.hitsMaps + state.hitsSyms
    val accent = if (state.running) Color(0xFF4FD6A0) else Color(0xFF8A8A92)
    val verOk = state.ver >= AceHideRepository.EXPECTED_VER

    KuaiMaoSettingsScaffold(
        title = "内核隐藏",
        subtitle = "AceGuard · LSM + Syscall · v${BuildConfig.VERSION_NAME}",
        bottomPadding = bottomPadding,
    ) {
        item {
            SusfsHero(
                eyebrow = "ACEGUARD",
                stateLabel = when {
                    !state.supported -> "模块未就绪"
                    state.running -> "屏蔽中"
                    else -> "未启用"
                },
                accent = accent,
                badge = when {
                    !state.supported -> "无控制节点"
                    state.susfsAvailable -> "SUSFS 联动 ${state.susfsRules}"
                    else -> "SUSFS 未接入"
                },
                metrics = listOf(
                    SusfsMetric("HITS", totalHits.toString(), totalHits > 0),
                    SusfsMetric("MODULES", "$enabledModules / 5", enabledModules == 5),
                    SusfsMetric(
                        "MODE",
                        when (state.mode) {
                            "direct" -> "direct"
                            "shared" -> "shared"
                            else -> "—"
                        },
                        state.mode == "direct",
                    ),
                ),
            )
        }

        // 内核模块与 APK 是两套东西：装新 APK 不会替换已在跑的 ko。
        // 版本对不上时功能一定失效，所以单独放一张卡，别让用户猜。
        if (state.supported && !verOk) {
            item {
                SusfsGroupCard(modifier = Modifier.padding(top = 12.dp)) {
                    KuaiMaoRow(
                        icon = Icons.TwoTone.Security,
                        title = "内核模块版本不匹配",
                        description = "APK v${BuildConfig.VERSION_NAME} 需要 ver=${AceHideRepository.EXPECTED_VER}，" +
                            "当前内核跑的是 ver=${state.ver}。重启手机后再点管理器首页的「一键提权」，" +
                            "会加载本 APK 内嵌的新模块。",
                        iconTint = KuaiMaoColors.amber,
                        iconContainer = KuaiMaoColors.amber.copy(alpha = 0.12f),
                    )
                }
            }
        }

        item {
            SusfsSectionLabel(
                title = "拦截面",
                note = if (state.running) "运行中不可改" else "点开关选择要挡的观测点",
            )
        }

        item {
            SusfsGroupCard {
                KuaiMaoRow(
                    icon = Icons.TwoTone.VisibilityOff,
                    title = "目录项隐藏",
                    description = "getdents64 过滤 + stat 族返回 ENOENT，ls / find / ps 都看不到",
                    trailing = {
                        KuaiMaoSwitch(
                            checked = dents,
                            enabled = !state.running && !busy,
                            onCheckedChange = { dents = it },
                        )
                    },
                )
                KuaiMaoRowDivider()
                KuaiMaoRow(
                    icon = Icons.TwoTone.Security,
                    title = "/proc 表屏蔽",
                    description = "/proc/modules、/proc/kcore 直接不可读，模块表为空",
                    trailing = {
                        KuaiMaoSwitch(
                            checked = procfs,
                            enabled = !state.running && !busy,
                            onCheckedChange = { procfs = it },
                        )
                    },
                )
                KuaiMaoRowDivider()
                KuaiMaoRow(
                    icon = Icons.TwoTone.Bolt,
                    title = "进程隐身",
                    description = "/proc/<pid> 整棵子树剔除：comm / status / cmdline / exe / fd 全 ENOENT",
                    trailing = {
                        KuaiMaoSwitch(
                            checked = procs,
                            enabled = !state.running && !busy,
                            onCheckedChange = { procs = it },
                        )
                    },
                )
                KuaiMaoRowDivider()
                KuaiMaoRow(
                    icon = Icons.TwoTone.VisibilityOff,
                    title = "内存映射擦除",
                    description = "maps / smaps 命中行等长擦成空白，长度不变、路径不可读",
                    trailing = {
                        KuaiMaoSwitch(
                            checked = maps,
                            enabled = !state.running && !busy,
                            onCheckedChange = { maps = it },
                        )
                    },
                )
                KuaiMaoRowDivider()
                KuaiMaoRow(
                    icon = Icons.TwoTone.Security,
                    title = "符号表擦除",
                    description = "kallsyms 里含关键字的符号行整行清空，按名字搜不到",
                    trailing = {
                        KuaiMaoSwitch(
                            checked = syms,
                            enabled = !state.running && !busy,
                            onCheckedChange = { syms = it },
                        )
                    },
                )
            }
        }

        item {
            SusfsSectionLabel(title = "匹配关键字", note = "前缀匹配")
        }
        item {
            SusfsGroupCard {
                Column(modifier = Modifier.padding(vertical = 13.dp)) {
                    OutlinedTextField(
                        value = keywords,
                        onValueChange = { keywords = it },
                        singleLine = true,
                        enabled = !state.running && !busy,
                        label = { Text("逗号分隔，最多 4 个") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "填 ksu 会同时挡住 ksud、ksu_backup。建议 ksu,kernelsu,susfs",
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = KuaiMaoColors.textMuted,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                SusfsPillButton(
                    text = if (state.running) "运行中" else "启用隐藏",
                    icon = Icons.TwoTone.VisibilityOff,
                    primary = true,
                    enabled = !state.running && !busy && (dents || procfs || procs || maps || syms),
                    onClick = ::runStart,
                    modifier = Modifier.weight(1.35f),
                )
                SusfsPillButton(
                    text = "停止",
                    primary = false,
                    enabled = state.running && !busy,
                    onClick = ::runStop,
                    modifier = Modifier.weight(1f),
                )
                SusfsPillButton(
                    text = "刷新",
                    icon = Icons.TwoTone.Refresh,
                    primary = false,
                    enabled = !busy,
                    onClick = { scope.launch { refresh() } },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (state.supported && !state.susfsAvailable) {
            item {
                SusfsSectionLabel(title = "联动", note = "需要挂载表隐藏时再开")
            }
            item {
                SusfsGroupCard {
                    KuaiMaoRow(
                        icon = Icons.TwoTone.Bolt,
                        title = "SUSFS 未接入",
                        description = "挂载表、map_files、模块条目由 SUSFS 负责。点「加载」用 ksud 内嵌的模块拉起，并设为开机自启。",
                        trailing = {
                            SusfsPillButton(
                                text = "加载",
                                primary = false,
                                enabled = !busy,
                                onClick = {
                                    if (busy) return@SusfsPillButton
                                    busy = true
                                    scope.launch {
                                        loadingDialog.show()
                                        val r = repository.enableSusfs()
                                        loadingDialog.hide()
                                        busy = false
                                        refresh()
                                        snack(
                                            r.getOrElse { it.message ?: "加载失败" }
                                                .lineSequence().firstOrNull().orEmpty(),
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }

        if (proof.isNotBlank()) {
            item {
                SusfsMonoPanel(
                    title = "自证 · 内核实测",
                    text = proof,
                    modifier = Modifier.padding(top = 16.dp),
                    badge = if (proof.contains("已隐藏")) "GONE" else "CHECK",
                    badgeColor = if (proof.contains("已隐藏")) Color(0xFF4FD6A0) else KuaiMaoColors.amber,
                )
            }
        }

        item {
            SusfsSectionLabel(
                title = "检测项矩阵",
                note = if (checks.isEmpty()) "未运行" else "${checks.count { it.verdict == AceHideRepository.Verdict.PASS }}/${checks.size}",
            )
        }
        item {
            SusfsGroupCard {
                Column(modifier = Modifier.padding(vertical = 13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "逐项跑真实检测命令，未覆盖项会写明原因",
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = KuaiMaoColors.textMuted,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        SusfsPillButton(
                            text = "实测",
                            primary = false,
                            enabled = state.running && !busy,
                            onClick = ::runCheck,
                        )
                    }
                    if (checks.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        checks.forEachIndexed { index, item ->
                            if (index > 0) {
                                Spacer(Modifier.height(8.dp))
                            }
                            Row(verticalAlignment = Alignment.Top) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(100.dp))
                                        .background(
                                            when (item.verdict) {
                                                AceHideRepository.Verdict.PASS ->
                                                    KuaiMaoTones.green.container

                                                AceHideRepository.Verdict.FAIL ->
                                                    KuaiMaoColors.amber.copy(alpha = 0.16f)

                                                AceHideRepository.Verdict.UNCOVERED ->
                                                    KuaiMaoColors.canvasSubtle
                                            },
                                        )
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        text = when (item.verdict) {
                                            AceHideRepository.Verdict.PASS -> "PASS"
                                            AceHideRepository.Verdict.FAIL -> "FAIL"
                                            AceHideRepository.Verdict.UNCOVERED -> "未覆盖"
                                        },
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Black,
                                        color = when (item.verdict) {
                                            AceHideRepository.Verdict.PASS -> KuaiMaoTones.green.content
                                            AceHideRepository.Verdict.FAIL -> KuaiMaoColors.amber
                                            AceHideRepository.Verdict.UNCOVERED -> KuaiMaoColors.textMuted
                                        },
                                    )
                                }
                                Spacer(Modifier.width(9.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.name,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = KuaiMaoColors.text,
                                    )
                                    Text(
                                        item.detail,
                                        fontSize = 10.sp,
                                        lineHeight = 14.sp,
                                        color = KuaiMaoColors.textMuted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            SusfsMonoPanel(
                title = "内核状态",
                text = state.log.ifBlank { "（暂无回执，启用后可见）" },
                modifier = Modifier.padding(top = 16.dp),
                badge = buildString {
                    append("ver=${state.ver}/${AceHideRepository.EXPECTED_VER}")
                    if (state.hooks > 0) append(" · ${state.hooks} hook")
                },
                badgeColor = if (verOk) Color(0xFF4FD6A0) else KuaiMaoColors.amber,
            )
        }

        item {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "规则跑在 kernelsu.ko 的 LSM 钩子上，syscall 层兜底，所以 stat 也返回 ENOENT，" +
                    "不会出现「ls 看不到但 stat 成功」。包名查询、属性服务走 Binder，syscall 层管不到，矩阵里标未覆盖。",
                fontSize = 10.sp,
                lineHeight = 15.sp,
                color = KuaiMaoColors.textMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
