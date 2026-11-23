package com.kuaimaosu.manager.ui.screen.susfs.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kuaimaosu.manager.ui.theme.KuaiMaoColors

/**
 * SUSFS 配置页头部：返回按钮 + 状态胶囊 + 大标题 + 胶囊标签栏。
 *
 * 原来用的是 Material3 的 LargeFlexibleTopAppBar 和 PrimaryScrollableTabRow：
 * 大标题会随滚动折叠、标签下有一条细指示线。那套是 Material 的默认观感，
 * 放在这个以「卡片 + 胶囊 + 大字重」为主的界面里显得很生硬。
 *
 * 这里换成：
 *  - 左上白色圆形返回钮（与首页图标按钮同一套阴影与圆角）；
 *  - 右上状态胶囊，圆点会呼吸——运行中为绿，未启用为灰，读取中为半透明；
 *  - 标题单独一行（30sp Black），下面一行 11sp 的说明；
 *  - 标签改成实心胶囊，选中为品牌色底，未选中为浅灰底，横向可滚。
 *
 * @param status null 表示还在读取，不显示确定状态
 */
@Composable
fun SuSFSHeader(
    title: String,
    subtitle: String,
    tabs: List<String>,
    selectedTab: Int,
    status: Boolean?,
    onSelectTab: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    statusOnLabel: String = "已启用",
    statusOffLabel: String = "未启用",
    statusLoadingLabel: String = "读取中",
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackCircle(onClick = onBack)
            Spacer(modifier = Modifier.weight(1f))
            StatusPill(
                status = status,
                onLabel = statusOnLabel,
                offLabel = statusOffLabel,
                loadingLabel = statusLoadingLabel,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = title,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.8).sp,
                color = KuaiMaoColors.text,
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = KuaiMaoColors.textMuted,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            tabs.forEachIndexed { index, label ->
                TabPill(
                    label = label,
                    selected = index == selectedTab,
                    onClick = { onSelectTab(index) },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun BackCircle(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .shadow(
                elevation = 6.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color(0x1218263C),
                spotColor = Color(0x1618263C),
            )
            .clip(CircleShape)
            .background(KuaiMaoColors.canvasCard)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.TwoTone.ArrowBack,
            contentDescription = null,
            tint = KuaiMaoColors.textSec,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun StatusPill(
    status: Boolean?,
    onLabel: String,
    offLabel: String,
    loadingLabel: String,
) {
    val dotColor = when (status) {
        true -> KuaiMaoColors.success
        false -> KuaiMaoColors.textMuted
        null -> KuaiMaoColors.textDisabled
    }
    val container = when (status) {
        true -> KuaiMaoColors.success.copy(alpha = 0.10f)
        false -> KuaiMaoColors.canvasSubtle
        null -> KuaiMaoColors.canvasSubtle
    }
    val label = when (status) {
        true -> onLabel
        false -> offLabel
        null -> loadingLabel
    }

    // 呼吸：只在启用时跳，未启用保持静止，避免整页看起来都在闪。
    val transition = rememberInfiniteTransition(label = "susfsStatus")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "susfsStatusPulse",
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(container)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .graphicsLayer {
                    alpha = if (status == true) 0.45f + 0.55f * pulse else 1f
                    scaleX = if (status == true) 0.9f + 0.2f * pulse else 1f
                    scaleY = scaleX
                }
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Black,
            color = if (status == true) KuaiMaoColors.success else KuaiMaoColors.textSec,
        )
    }
}

@Composable
private fun TabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 夜间强调色接近纯白：白底白字看不见，选中态改用深灰胶囊。
    val brightAccent = KuaiMaoColors.brand.luminance() > 0.72f
    val selectedBg = if (brightAccent) Color(0xFF3A3A3F) else KuaiMaoColors.brand
    val bg by animateColorAsState(
        targetValue = if (selected) selectedBg else KuaiMaoColors.canvasSubtle,
        animationSpec = tween(durationMillis = 180),
        label = "susfsTabBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) Color.White else KuaiMaoColors.textSec,
        animationSpec = tween(durationMillis = 180),
        label = "susfsTabFg",
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.Black else FontWeight.Bold,
            color = fg,
            maxLines = 1,
        )
    }
}
