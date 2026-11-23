package com.kuaimaosu.manager.ui.screen.susfs.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kuaimaosu.manager.ui.component.KuaiMaoRow
import com.kuaimaosu.manager.ui.theme.KuaiMaoColors
import com.kuaimaosu.manager.ui.theme.KuaiMaoDimens

/**
 * SUSFS / AceGuard 共用的一套小部件。
 *
 * 之前各页各写各的：状态页是深色 Hero，路径页是概览卡 + 条目卡，
 * 而「一般」「伪造元数据」「文件重定向」「挂载」还停在 Material 设置行。
 * 同一屏里三种语言，看起来像拼出来的。
 *
 * 这里把「分组标题 + 分组卡 + 深色 Hero + 添加块 + 胶囊按钮」抽出来，
 * 各页只负责内容和逻辑，版式统一。
 */

/** Hero 里的一个指标 */
data class SusfsMetric(
    val label: String,
    val value: String,
    /** 数值用品牌色强调（比如「已启用 5」） */
    val emphasize: Boolean = false,
)

/** 分组小标题：一行大写字母 + 右侧可选注记 */
@Composable
fun SusfsSectionLabel(
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.6.sp,
            color = KuaiMaoColors.textMuted,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!note.isNullOrBlank()) {
            Text(
                text = note,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = KuaiMaoColors.textDisabled,
                maxLines = 1,
            )
        }
    }
}

/** 分组卡：白底、大圆角、无内边距，行自己决定留白 */
@Composable
fun SusfsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 6.dp,
                shape = KuaiMaoDimens.rCard,
                clip = false,
                ambientColor = Color(0x0F18263C),
                spotColor = Color(0x1418263C),
            )
            .clip(KuaiMaoDimens.rCard)
            .background(KuaiMaoColors.canvasCard)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        content = content,
    )
}

/**
 * 深色 Hero：左上角 eyebrow + 大字状态，右上角胶囊徽标，
 * 中间一条细分隔线，底部一排指标。
 *
 * 与状态页那张保持一致：线性深炭渐变 + 右上角一层极淡冷光。
 */
@Composable
fun SusfsHero(
    eyebrow: String,
    stateLabel: String,
    accent: Color,
    badge: String,
    metrics: List<SusfsMetric>,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 138.dp)
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
        /*
         * 装饰只画在卡片内部。
         * 上一版用一个半径 0.62×宽、圆心在卡片外的径向渐变，圆被圆角裁出一道
         * 生硬的弧线，看起来像图案从卡片里溢出去。这里改成三个收敛在角落的
         * 柔光：半径小、透明度低、圆心留在边界内，配 clipToBounds 兜底。
         */
        Canvas(modifier = Modifier.fillMaxSize().clipToBounds()) {
            val topRight = Offset(size.width * 0.94f, size.height * 0.08f)
            val topRightRadius = size.minDimension * 0.78f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x2E6C7BFF), Color(0x0A6C7BFF), Color.Transparent),
                    center = topRight,
                    radius = topRightRadius,
                ),
                radius = topRightRadius,
                center = topRight,
            )

            val bottomLeft = Offset(size.width * 0.04f, size.height * 0.98f)
            val bottomLeftRadius = size.minDimension * 0.52f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x12FFFFFF), Color.Transparent),
                    center = bottomLeft,
                    radius = bottomLeftRadius,
                ),
                radius = bottomLeftRadius,
                center = bottomLeft,
            )
        }

        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = eyebrow,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.4.sp,
                        color = Color.White.copy(alpha = 0.45f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                if (badge.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(accent),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = badge,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White.copy(alpha = 0.80f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (metrics.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.07f)),
                )
                Spacer(Modifier.height(13.dp))
                Row(verticalAlignment = Alignment.Top) {
                    metrics.forEachIndexed { index, metric ->
                        Column(
                            modifier = Modifier.weight(if (index == 0) 1.1f else 1f),
                        ) {
                            Text(
                                text = metric.label,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.1.sp,
                                color = Color.White.copy(alpha = 0.40f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(
                                text = metric.value,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                color = if (metric.emphasize) accent else Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            footer?.invoke()
        }
    }
}

/** 虚线描边添加块：比「列表第一行」清楚，也不会和真条目混淆 */
@Composable
fun SusfsAddTile(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(KuaiMaoColors.canvasSubtle.copy(alpha = if (enabled) 0.55f else 0.3f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = KuaiMaoColors.textSec,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            color = KuaiMaoColors.textSec,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 胶囊按钮：primary 为实心品牌色，否则为浅底描边。
 * 高度 46dp，一屏里最多两个，不要做成三连按钮。
 */
@Composable
fun SusfsPillButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when {
        !primary -> KuaiMaoColors.canvasSubtle
        else -> KuaiMaoColors.brand
    }
    val fg = if (primary) Color.White else KuaiMaoColors.textSec
    Row(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) fg else fg.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Black,
            color = if (enabled) fg else fg.copy(alpha = 0.5f),
            maxLines = 1,
        )
    }
}

/** 等宽面板：自证输出、内核状态这类「原样日志」，深色底更耐看 */
@Composable
fun SusfsMonoPanel(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    badgeColor: Color = Color(0xFF4FD6A0),
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF14161A))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.4.sp,
                color = Color.White.copy(alpha = 0.86f),
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (!badge.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(badgeColor.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(badgeColor),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = badge,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Black,
                        color = badgeColor,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = text,
            fontSize = 10.sp,
            lineHeight = 14.5.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = Color.White.copy(alpha = 0.62f),
        )
    }
}

/**
 * 单条规则行：自带卡片底 + 右侧箭头。
 *
 * 比起把条目塞进 Material 的 SettingsJumpPageWidget，这里让路径/目标值
 * 占据主视线，图标只做一个识别锚点。
 */
@Composable
fun SusfsEntryRow(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    description: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(KuaiMaoColors.canvasCard)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KuaiMaoRow(
            icon = icon,
            title = title,
            description = description,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.TwoTone.ChevronRight,
            contentDescription = null,
            tint = KuaiMaoColors.textDisabled,
            modifier = Modifier.size(18.dp),
        )
    }
}
