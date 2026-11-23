package com.kuaimaosu.manager.ui.screen.susfs.subpages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Android
import androidx.compose.material.icons.twotone.ChevronRight
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.Loop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.kuaimaosu.manager.R
import com.kuaimaosu.manager.data.packageinfo.InstalledPackageRepository
import com.kuaimaosu.manager.domain.model.SusPathItem
import com.kuaimaosu.manager.ui.component.KuaiMaoCard
import com.kuaimaosu.manager.ui.component.KuaiMaoRowDivider
import com.kuaimaosu.manager.ui.component.settings.SettingsJumpPageWidget
import com.kuaimaosu.manager.ui.component.settings.SettingsTextFieldWidget
import com.kuaimaosu.manager.ui.screen.susfs.RegisterSuSFSRefresh
import com.kuaimaosu.manager.ui.screen.susfs.SuSFSRefreshRegistrar
import com.kuaimaosu.manager.ui.screen.susfs.component.AppPickerDialog
import com.kuaimaosu.manager.ui.screen.susfs.component.EntryDetailDialog
import com.kuaimaosu.manager.ui.screen.susfs.component.ManualAddDialog
import com.kuaimaosu.manager.ui.screen.susfs.component.toImportedEntryLines
import com.kuaimaosu.manager.ui.theme.KuaiMaoColors
import com.kuaimaosu.manager.ui.util.LocalSnackbarHost
import com.kuaimaosu.manager.ui.util.showReplacingSnackbar
import com.kuaimaosu.manager.ui.viewmodel.SuSFSUiAction
import com.kuaimaosu.manager.ui.viewmodel.SuSFSViewModel
import com.kuaimaosu.manager.ui.viewmodel.awaitSuSFSBoolean
import com.kuaimaosu.manager.ui.viewmodel.awaitSuSFSConfig
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * SUSFS · 路径隐藏页。
 *
 * 原来的排版是「一段说明卡 + 一串 Material 设置行」：说明卡占掉小半屏、
 * 每行只有一个路径字符串和一个右箭头，看不出「有几条、几条是循环」，
 * 添加入口混在列表第一行，和普通条目长得一模一样。
 *
 * 现在是：
 *  - 顶部一张概览卡：总数大字 + 循环/普通比例条 + 右上角实心「添加」胶囊；
 *  - 条目收进一张卡里，每行左侧是图标块（循环走琥珀色），路径用等宽字，
 *    下面一行是类型徽标 + SUS_PATH 标记；
 *  - 空列表不再是「一个灰盒子」，而是一个引导区，直接给「添加第一条路径」；
 *  - 说明文字降为页脚的一行小字，不再抢视线。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SusPathTab(
    nestedScrollConnection: NestedScrollConnection,
    innerPadding: PaddingValues,
    onRegisterRefresh: SuSFSRefreshRegistrar,
) {
    val configHelper = koinViewModel<SuSFSViewModel>()
    val installedRepo = koinInject<InstalledPackageRepository>()
    val installedApps by installedRepo.packages.collectAsStateWithLifecycle()
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<Set<SusPathItem>>(emptySet()) }
    var isLoading by remember { mutableStateOf(false) }

    var showManualAdd by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<SusPathItem?>(null) }

    var selectedSubtype by remember { mutableStateOf("") }
    val manualPath = remember { TextFieldState() }

    val subtypePath = stringResource(R.string.susfs_path_subtype_path)
    val subtypeLoop = stringResource(R.string.susfs_path_subtype_loop)
    val subtypes = listOf(subtypePath, subtypeLoop)

    RegisterSuSFSRefresh(onRegisterRefresh) { config, _ ->
        entries = config.sus_path
    }

    LaunchedEffect(showManualAdd) {
        if (showManualAdd) {
            if (selectedSubtype.isEmpty()) selectedSubtype = subtypePath
        } else {
            manualPath.clearText()
        }
    }

    // 打开应用选择器前刷新已安装应用列表（含 apk 路径）。
    LaunchedEffect(showAppPicker) {
        if (showAppPicker) installedRepo.refresh()
    }

    val manualAddTitle = stringResource(R.string.susfs_entry_manual_add)
    val detailTitle = stringResource(R.string.susfs_entry_detail)
    val pathLabel = stringResource(R.string.susfs_entry_path_label)
    val isLoopLabel = stringResource(R.string.susfs_path_is_loop)
    val isNotLoopLabel = stringResource(R.string.susfs_path_is_not_loop)
    val operationFailedMsg = stringResource(R.string.susfs_operation_failed)
    val susfsEntryImportSuccess = stringResource(R.string.susfs_entry_import_success)

    val entryList = entries.toList()
    val loopCount = entryList.count { it.is_loop }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        item {
            Spacer(Modifier.height(innerPadding.calculateTopPadding()))
        }

        item {
            PathSummaryCard(
                total = entryList.size,
                loopCount = loopCount,
                onAdd = { showManualAdd = true },
            )
        }

        if (entryList.isEmpty()) {
            item {
                PathEmptyState(onAdd = { showManualAdd = true })
            }
        } else {
            item {
                KuaiMaoCard(
                    modifier = Modifier.padding(top = 12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                ) {
                    entryList.forEachIndexed { index, item ->
                        PathEntryRow(
                            item = item,
                            loopLabel = isLoopLabel,
                            normalLabel = isNotLoopLabel,
                            onClick = { detailItem = item },
                        )
                        if (index != entryList.lastIndex) {
                            KuaiMaoRowDivider()
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.sus_loop_paths_description_text),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 15.sp,
                color = KuaiMaoColors.textMuted,
                modifier = Modifier.padding(vertical = 14.dp),
            )
        }

        item {
            Spacer(Modifier.height(innerPadding.calculateBottomPadding()))
        }
    }

    ManualAddDialog(
        showDialog = showManualAdd,
        title = manualAddTitle,
        subtypes = subtypes,
        selectedSubtype = selectedSubtype,
        onSubtypeChange = { selectedSubtype = it },
        onDismiss = { showManualAdd = false },
        showImportFromFile = true,
        onImportFromFile = { importedPath -> manualPath.setTextAndPlaceCursorAtEnd(importedPath) },
        onConfirm = {
            val paths = manualPath.text.toString().toImportedEntryLines()
            if (paths.isEmpty()) return@ManualAddDialog
            scope.launch {
                isLoading = true
                var snackbarMessage: String? = null
                var successCount = 0
                var failCount = 0
                paths.forEach { path ->
                    val ok = awaitSuSFSBoolean(configHelper) { reply ->
                        SuSFSUiAction.AddSusPath(
                            path = path,
                            loop = selectedSubtype == subtypeLoop,
                            reply = reply,
                        )
                    }
                    if (ok) {
                        successCount++
                    } else {
                        failCount++
                    }
                }
                if (successCount > 0) {
                    entries = awaitSuSFSConfig(configHelper) { reply ->
                        SuSFSUiAction.Refresh(reply)
                    }?.sus_path.orEmpty()
                }
                if (paths.size == 1) {
                    if (successCount > 0) {
                        showManualAdd = false
                    } else {
                        snackbarMessage = operationFailedMsg
                    }
                } else {
                    snackbarMessage = susfsEntryImportSuccess.format(successCount, failCount)
                    if (failCount == 0) {
                        showManualAdd = false
                    }
                }
                isLoading = false
                snackbarMessage?.let { snackbarHost.showReplacingSnackbar(it) }
            }
        },
        formContent = {
            item {
                SettingsTextFieldWidget(
                    state = manualPath,
                    title = pathLabel,
                    useLabelAsPlaceholder = true,
                    enabled = !isLoading,
                    lineLimits = TextFieldLineLimits.MultiLine(
                        minHeightInLines = 4,
                        maxHeightInLines = 8,
                    ),
                    renderBackgroundBlur = false,
                )
            }
            item {
                SettingsJumpPageWidget(
                    icon = Icons.TwoTone.Android,
                    title = stringResource(R.string.susfs_pick_app_title),
                    description = stringResource(R.string.susfs_pick_app_desc),
                    renderBackgroundBlur = false,
                    onClick = { showAppPicker = true },
                )
            }
        },
    )

    if (showAppPicker) {
        AppPickerDialog(
            apps = installedApps,
            onPick = { apkPath ->
                manualPath.setTextAndPlaceCursorAtEnd(apkPath)
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false },
        )
    }

    detailItem?.let { item ->
        EntryDetailDialog(
            showDialog = true,
            title = detailTitle,
            fields = listOf(
                pathLabel to item.path,
                isLoopLabel to if (item.is_loop) isLoopLabel else isNotLoopLabel,
            ),
            onDismiss = { detailItem = null },
            onDelete = {
                scope.launch {
                    isLoading = true
                    val ok = awaitSuSFSBoolean(configHelper) { reply ->
                        SuSFSUiAction.RemoveSusPath(item.path, reply)
                    }
                    if (ok) {
                        entries = awaitSuSFSConfig(configHelper) { reply ->
                            SuSFSUiAction.Refresh(reply)
                        }?.sus_path.orEmpty()
                        detailItem = null
                    } else {
                        isLoading = false
                        scope.launch {
                            snackbarHost.showReplacingSnackbar(operationFailedMsg)
                        }
                    }
                    isLoading = false
                }
            },
        )
    }
}

/** 概览卡：总数、循环占比、添加入口 */
@Composable
private fun PathSummaryCard(
    total: Int,
    loopCount: Int,
    onAdd: () -> Unit,
) {
    KuaiMaoCard(
        modifier = Modifier.padding(top = 4.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.susfs_path_hero_title),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.8.sp,
                    color = KuaiMaoColors.textMuted,
                )
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = total.toString(),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-1.2).sp,
                        color = KuaiMaoColors.text,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.susfs_path_hero_count),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = KuaiMaoColors.textMuted,
                        modifier = Modifier.padding(bottom = 7.dp),
                    )
                }
            }
            AddPill(onClick = onAdd)
        }

        Spacer(Modifier.height(14.dp))

        // 比例条：循环段用琥珀色，普通段用品牌色；没有条目时只剩一条淡轨道。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(KuaiMaoColors.canvasSubtle),
        ) {
            if (total > 0) {
                val loopWeight = loopCount.toFloat() / total
                if (loopWeight > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(loopWeight)
                            .fillMaxSize()
                            .background(KuaiMaoColors.amber),
                    )
                }
                if (loopWeight < 1f) {
                    Box(
                        modifier = Modifier
                            .weight(1f - loopWeight)
                            .fillMaxSize()
                            .background(KuaiMaoColors.brand),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot(color = KuaiMaoColors.brand, text = stringResource(R.string.susfs_path_badge_normal))
            LegendDot(
                color = KuaiMaoColors.amber,
                text = stringResource(R.string.susfs_path_hero_loop, loopCount),
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            color = KuaiMaoColors.textMuted,
        )
    }
}

@Composable
private fun AddPill(onClick: () -> Unit) {
    val bright = KuaiMaoColors.brand.luminance() > 0.72f
    val bg = if (bright) Color(0xFF3A3A3F) else KuaiMaoColors.brand
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(
                Brush.linearGradient(
                    listOf(bg, if (bright) Color(0xFF232326) else KuaiMaoColors.brandDeep),
                ),
            )
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 15.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.TwoTone.Add,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = stringResource(R.string.susfs_entry_manual_add),
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** 单条路径：图标块 + 等宽路径 + 类型徽标 */
@Composable
private fun PathEntryRow(
    item: SusPathItem,
    loopLabel: String,
    normalLabel: String,
    onClick: () -> Unit,
) {
    val tone = if (item.is_loop) KuaiMaoColors.amber else KuaiMaoColors.brand
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(tone.copy(alpha = if (item.is_loop) 0.14f else 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (item.is_loop) Icons.TwoTone.Loop else Icons.TwoTone.Folder,
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.path,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = KuaiMaoColors.textBody,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(
                    text = if (item.is_loop) loopLabel else normalLabel,
                    color = tone,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "SUS_PATH",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.1.sp,
                    color = KuaiMaoColors.textDisabled,
                )
            }
        }
        Icon(
            imageVector = Icons.TwoTone.ChevronRight,
            contentDescription = null,
            tint = KuaiMaoColors.chevron,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun TypeBadge(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 8.5.sp,
        fontWeight = FontWeight.Black,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** 空列表引导 */
@Composable
private fun PathEmptyState(onAdd: () -> Unit) {
    KuaiMaoCard(
        modifier = Modifier.padding(top = 12.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 26.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(KuaiMaoColors.canvasSubtle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Folder,
                    contentDescription = null,
                    tint = KuaiMaoColors.textMuted,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.susfs_path_empty_title),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = KuaiMaoColors.textBody,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.susfs_path_empty_desc),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 16.sp,
                color = KuaiMaoColors.textMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(KuaiMaoColors.canvasSubtle)
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Add,
                    contentDescription = null,
                    tint = KuaiMaoColors.textLabel,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.susfs_path_add_first),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Black,
                    color = KuaiMaoColors.textLabel,
                )
            }
        }
    }
}
