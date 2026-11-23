package com.kuaimaosu.manager.ui.screen.susfs.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Android
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kuaimaosu.manager.R
import com.kuaimaosu.manager.domain.model.InstalledPackageInfo
import com.kuaimaosu.manager.ui.component.PackageIcon
import com.kuaimaosu.manager.ui.theme.KuaiMaoColors

/**
 * App 选择对话框：列出已安装应用（含 apk 路径），供 SUSFS 路径隐藏挑选。
 *
 * 只负责"选哪个 app"，选中后通过 [onPick] 把该 app 的 apk 路径回传，
 * 由调用方填进输入框、走既有的添加确认流程。这里不直接下发 sus_path。
 *
 * 稳定性：
 * - 按 packageName 去重，避免多用户/工作资料下同名包产生重复 key 导致 LazyColumn 崩溃。
 * - 默认隐藏系统应用，既减少列表体积，也避开大量 system 包的 apkPath 缺失问题。
 */
@Composable
fun AppPickerDialog(
    apps: List<InstalledPackageInfo>,
    onPick: (apkPath: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    // 按包名去重：同名包（多用户/工作资料）只保留一条，优先取有 apkPath 的。
    // 这一步同时保证 LazyColumn 的 key 唯一，修掉"滚动到系统应用就闪退"。
    val deduped = remember(apps) {
        apps
            .groupBy { it.packageName }
            .map { (_, group) -> group.firstOrNull { it.apkPath.isNotEmpty() } ?: group.first() }
    }

    val filtered = remember(query, deduped, showSystem) {
        val q = query.trim()
        val base = if (showSystem) deduped else deduped.filterNot { it.isSystem }
        val matched = if (q.isEmpty()) {
            base
        } else {
            base.filter {
                it.appLabel.contains(q, ignoreCase = true) ||
                    it.packageName.contains(q, ignoreCase = true)
            }
        }
        matched.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appLabel })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.susfs_pick_app_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.susfs_pick_app_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    FilterChip(
                        selected = showSystem,
                        onClick = { showSystem = !showSystem },
                        label = { Text(stringResource(R.string.susfs_pick_app_show_system)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.TwoTone.Android,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                    Text(
                        text = stringResource(R.string.susfs_pick_app_count, filtered.size),
                        fontSize = 11.sp,
                        color = KuaiMaoColors.textMuted,
                    )
                }
                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.susfs_pick_app_empty),
                            fontSize = 12.sp,
                            color = KuaiMaoColors.textMuted,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(filtered, key = { it.packageName + ":" + it.uid }) { app ->
                            AppPickerRow(
                                app = app,
                                enabled = app.apkPath.isNotEmpty(),
                                onClick = {
                                    if (app.apkPath.isNotEmpty()) onPick(app.apkPath)
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.susfs_entry_cancel))
            }
        },
    )
}

@Composable
private fun AppPickerRow(
    app: InstalledPackageInfo,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val rowShape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(if (enabled) KuaiMaoColors.brandSoft else KuaiMaoColors.textMuted.copy(alpha = 0.08f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (app.packageName.isNotEmpty()) {
            PackageIcon(
                packageName = app.packageName,
                contentDescription = null,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp)),
            )
        } else {
            Icon(
                imageVector = Icons.TwoTone.Android,
                contentDescription = null,
                tint = KuaiMaoColors.brand,
                modifier = Modifier.size(34.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                text = app.appLabel.ifEmpty { app.packageName },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) KuaiMaoColors.textBody else KuaiMaoColors.textMuted,
                maxLines = 1,
            )
            Text(
                text = app.apkPath.ifEmpty { app.packageName },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = KuaiMaoColors.textMuted,
                maxLines = 1,
            )
        }
    }
}
