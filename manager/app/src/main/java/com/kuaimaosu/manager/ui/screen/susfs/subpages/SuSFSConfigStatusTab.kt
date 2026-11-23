package com.kuaimaosu.manager.ui.screen.susfs.subpages

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.RestartAlt
import androidx.compose.material.icons.twotone.Restore
import androidx.compose.material.icons.twotone.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.kuaimaosu.manager.R
import com.kuaimaosu.manager.domain.model.SuSFSStatusInfo
import com.kuaimaosu.manager.ui.component.KuaiMaoCard
import com.kuaimaosu.manager.ui.component.KuaiMaoRow
import com.kuaimaosu.manager.ui.component.KuaiMaoSwitch
import com.kuaimaosu.manager.ui.component.WarningCard
import com.kuaimaosu.manager.ui.component.settings.SegmentedColumn
import com.kuaimaosu.manager.ui.component.settings.SettingsJumpPageWidget
import com.kuaimaosu.manager.ui.screen.susfs.RegisterSuSFSRefresh
import com.kuaimaosu.manager.ui.screen.susfs.SuSFSRefreshRegistrar
import com.kuaimaosu.manager.ui.theme.KuaiMaoColors
import com.kuaimaosu.manager.ui.theme.KuaiMaoDimens
import com.kuaimaosu.manager.ui.util.LocalSnackbarHost
import com.kuaimaosu.manager.ui.util.showReplacingSnackbar
import com.kuaimaosu.manager.ui.viewmodel.SuSFSUiAction
import com.kuaimaosu.manager.ui.viewmodel.SuSFSViewModel
import com.kuaimaosu.manager.ui.viewmodel.awaitSuSFSBoolean
import com.kuaimaosu.manager.ui.viewmodel.awaitSuSFSStatusInfo
import org.koin.compose.viewmodel.koinViewModel

/**
 * SUSFS · 状态页。
 *
 * 原来是三行「标题 + 描述」的设置行，版本、变体、特性平铺在一起，
 * 看不出「这套东西现在到底跑没跑」。现在顶部先给一张深色状态卡：
 * 左侧是运行状态大字，右侧是状态点，下方一排是版本 / 变体 / 特性数，
 * 卡片本身是深炭渐变，和首页那张内核状态卡同一套语言。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatusTab(
    nestedScrollConnection: NestedScrollConnection,
    innerPadding: PaddingValues,
    onRegisterRefresh: SuSFSRefreshRegistrar,
    configEnabled: Boolean,
    configEnabledLoaded: Boolean,
    onConfigEnabledChange: (Boolean) -> Unit,
    onConfigRestored: () -> Unit,
) {
    val configHelper = koinViewModel<SuSFSViewModel>()
    var statusInfo by remember { mutableStateOf(SuSFSStatusInfo("", "", "")) }

    RegisterSuSFSRefresh(onRegisterRefresh) { _, forceRefresh ->
        statusInfo = awaitSuSFSStatusInfo(configHelper, forceRefresh)
            ?: SuSFSStatusInfo("", "", "")
    }

    val featureCount = remember(statusInfo.enabledFeatures) {
        statusInfo.enabledFeatures
            .split(',', ';', '|', ' ', '\n')
            .count { it.isNotBlank() }
    }

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
            StatusHero(
                enabled = configEnabled,
                loaded = configEnabledLoaded,
                version = statusInfo.version,
                variant = statusInfo.variant,
                featureCount = featureCount,
            )
        }

        item {
            AnimatedVisibility(visible = configEnabledLoaded && !configEnabled) {
                WarningCard(
                    modifier = Modifier.padding(top = 12.dp),
                    message = stringResource(R.string.susfs_config_disable_warning),
                )
            }
        }

        item {
            KuaiMaoCard(modifier = Modifier.padding(top = 12.dp)) {
                KuaiMaoRow(
                    icon = Icons.TwoTone.Save,
                    title = stringResource(R.string.susfs_enable_config),
                    description = stringResource(R.string.susfs_enable_config_summary),
                    trailing = {
                        KuaiMaoSwitch(
                            checked = configEnabled,
                            enabled = configEnabledLoaded,
                            onCheckedChange = onConfigEnabledChange,
                        )
                    },
                )
            }
        }

        item {
            KuaiMaoCard(modifier = Modifier.padding(top = 12.dp)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.susfs_status_enabled_features),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = KuaiMaoColors.textBody,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = featureCount.toString(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = KuaiMaoColors.textMuted,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = statusInfo.enabledFeatures.ifBlank {
                            stringResource(R.string.susfs_status_no_data)
                        },
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 16.sp,
                        color = KuaiMaoColors.textMuted,
                    )
                }
            }
        }

        item {
            BackupRestoreSection(onConfigRestored = onConfigRestored)
        }

        item {
            Spacer(Modifier.height(innerPadding.calculateBottomPadding()))
        }
    }
}

@Composable
private fun StatusHero(
    enabled: Boolean,
    loaded: Boolean,
    version: String,
    variant: String,
    featureCount: Int,
) {
    val accent = when {
        !loaded -> Color(0xFF8A8A92)
        enabled -> Color(0xFF4FD6A0)
        else -> Color(0xFF8A8A92)
    }
    val stateLabel = when {
        !loaded -> stringResource(R.string.susfs_state_loading)
        enabled -> stringResource(R.string.susfs_state_on)
        else -> stringResource(R.string.susfs_state_off)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 14.dp,
                shape = KuaiMaoDimens.rHero,
                clip = false,
                ambientColor = Color(0x33101828),
                spotColor = Color(0x3D101828),
            )
            .clip(KuaiMaoDimens.rHero)
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF17191E), Color(0xFF24272E), Color(0xFF1B1E24)),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            ),
    ) {
        // 右上角一层冷光，避免整块死黑；浓度很低，不会脏。
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.88f, -size.height * 0.25f)
            val radius = size.width * 0.62f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x335E6BFF), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SUSFS",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.4.sp,
                        color = Color.White.copy(alpha = 0.45f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stateLabel,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.6).sp,
                        color = Color.White,
                    )
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(accent),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = variant.ifBlank { "—" },
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White.copy(alpha = 0.80f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.07f)),
            )
            Spacer(Modifier.height(13.dp))

            Row(verticalAlignment = Alignment.Top) {
                HeroMetric(
                    label = stringResource(R.string.susfs_status_version),
                    value = version.ifBlank { "—" },
                    modifier = Modifier.weight(1f),
                )
                HeroMetric(
                    label = stringResource(R.string.susfs_status_variant),
                    value = variant.ifBlank { "—" },
                    modifier = Modifier.weight(1f),
                )
                HeroMetric(
                    label = stringResource(R.string.susfs_status_enabled_features),
                    value = featureCount.toString(),
                    modifier = Modifier.weight(0.7f),
                )
            }
        }
    }
}

@Composable
private fun HeroMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            fontSize = 8.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.2.sp,
            color = Color.White.copy(alpha = 0.40f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = value,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreSection(
    onConfigRestored: () -> Unit,
) {
    val configHelper = koinViewModel<SuSFSViewModel>()
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()

    var showImportConfirm by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val exportTitle = stringResource(R.string.susfs_backup_export)
    val exportDesc = stringResource(R.string.susfs_backup_description)
    val importTitle = stringResource(R.string.susfs_backup_import)
    val importDesc = stringResource(R.string.susfs_restore_description)
    val exportSuccessMsg = stringResource(R.string.susfs_backup_export_success)
    val exportFailedMsg = stringResource(R.string.susfs_backup_export_failed)
    val importSuccessMsg = stringResource(R.string.susfs_backup_import_success)
    val importFailedMsg = stringResource(R.string.susfs_backup_import_failed)
    val restoreDefaultTitle = stringResource(R.string.susfs_backup_restore_default)
    val restoreDefaultDesc = stringResource(R.string.susfs_backup_restore_default_desc)
    val confirmTitle = stringResource(R.string.susfs_backup_import_confirm_title)
    val confirmMsg = stringResource(R.string.susfs_backup_import_confirm_message)
    val importLabel = stringResource(R.string.susfs_backup_import_label)
    val cancelLabel = stringResource(R.string.susfs_entry_cancel)
    val operationSuccessMsg = stringResource(R.string.susfs_operation_success)
    val operationFailedMsg = stringResource(R.string.susfs_operation_failed)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = awaitSuSFSBoolean(configHelper) { reply ->
                SuSFSUiAction.ExportConfig(uri.toString(), reply)
            }
            scope.launch {
                snackbarHost.showReplacingSnackbar(if (ok) exportSuccessMsg else exportFailedMsg)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingImportUri = uri
        showImportConfirm = true
    }

    SegmentedColumn(
        modifier = Modifier.padding(top = 12.dp),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
    ) {
        item {
            SettingsJumpPageWidget(
                icon = Icons.TwoTone.Save,
                title = exportTitle,
                description = exportDesc,
                onClick = {
                    exportLauncher.launch("susfs_backup.json")
                },
            )
        }
        item {
            SettingsJumpPageWidget(
                icon = Icons.TwoTone.Restore,
                title = importTitle,
                description = importDesc,
                onClick = {
                    importLauncher.launch(arrayOf("application/json"))
                },
            )
        }
        item {
            RestoreDefaultRow(
                title = restoreDefaultTitle,
                description = restoreDefaultDesc,
                snackbarHost = snackbarHost,
                operationSuccessMsg = operationSuccessMsg,
                operationFailedMsg = operationFailedMsg,
                onConfigRestored = onConfigRestored,
            )
        }
    }

    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = {
                showImportConfirm = false
                pendingImportUri = null
            },
            title = { Text(confirmTitle) },
            text = { Text(confirmMsg) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = pendingImportUri
                        showImportConfirm = false
                        pendingImportUri = null
                        if (uri != null) {
                            scope.launch {
                                val ok = awaitSuSFSBoolean(configHelper) { reply ->
                                    SuSFSUiAction.ImportConfig(uri.toString(), reply)
                                }
                                if (ok) {
                                    onConfigRestored()
                                }
                                scope.launch {
                                    snackbarHost.showReplacingSnackbar(if (ok) importSuccessMsg else importFailedMsg)
                                }
                            }
                        }
                    },
                ) {
                    Text(importLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportConfirm = false
                    pendingImportUri = null
                }) {
                    Text(cancelLabel)
                }
            },
        )
    }
}

@Composable
private fun RestoreDefaultRow(
    title: String,
    description: String,
    snackbarHost: SnackbarHostState,
    operationSuccessMsg: String,
    operationFailedMsg: String,
    onConfigRestored: () -> Unit,
) {
    val configHelper = koinViewModel<SuSFSViewModel>()
    val scope = rememberCoroutineScope()
    SettingsJumpPageWidget(
        icon = Icons.TwoTone.RestartAlt,
        title = title,
        description = description,
        onClick = {
            scope.launch {
                val ok = awaitSuSFSBoolean(configHelper) { reply ->
                    SuSFSUiAction.RestoreDefault(reply)
                }
                if (ok) {
                    onConfigRestored()
                }
                snackbarHost.showReplacingSnackbar(if (ok) operationSuccessMsg else operationFailedMsg)
            }
        },
    )
}
