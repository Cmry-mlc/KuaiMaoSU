package com.kuaimaosu.manager.ui.screen.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kuaimaosu.manager.R
import com.kuaimaosu.manager.ui.theme.KuaiMaoColors
import com.kuaimaosu.manager.ui.theme.KuaiMaoDimens
import com.kuaimaosu.manager.ui.theme.ThemeConfig
import com.kuaimaosu.manager.ui.theme.isInDarkTheme
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 入场时长；比 SPLASH_MIN_DURATION_MS 略短，避免还没收尾就被淡出。 */
private const val INTRO_MS = 1500

/** 外环转一圈的时长。 */
private const val RING_PERIOD_MS = 3200

/** 高光扫过徽标的周期。 */
private const val SHEEN_PERIOD_MS = 2400

/** 底部进度条一个来回的周期。 */
private const val BAR_PERIOD_MS = 1400

/**
 * 冷启动首屏：光环扫描。
 *
 * 上一版是水墨：墨云用多层低透明度径向渐变叠出来，叠在浅色底上就是一团发灰的雾，
 * 屏幕上还有四散的墨点，观感确实「脏」。
 *
 * 这一版改成**几何光**，所有元素都有明确边界：
 *
 *  1. 底：上下两段极浅的竖向渐变，只有一层几乎看不出来的中心光晕；
 *  2. 环：两段反向旋转的细弧（扫掠渐变，头亮尾隐），外圈一圈刻度点；
 *  3. 徽标：黑色圆角底板，一枚斜向高光周期性扫过（玻璃质感），落位时一圈细环扩散；
 *  4. 字：逐字上浮，字距保持不变；
 *  5. 底：一条来回滑动的细光条 + 状态文案。
 *
 * 所有动效都是**线**与**点**，不用大面积半透明色块，所以任何主题下都不会发灰。
 */
@Composable
fun KuaiMaoSplash(
    modifier: Modifier = Modifier,
    stateText: String = "",
) {
    val themeConfig: ThemeConfig = koinInject()
    val dark = isInDarkTheme(themeConfig.forceDarkMode)

    // 底色：夜间纯黑到 #0B0B0D，白天 #F7F8FA 到 #ECEDF0。
    // 只在明暗之间动，不加彩色底，避免和用户自选主题色打架。
    val bgTop = if (dark) Color(0xFF0C0C0E) else Color(0xFFF8F9FB)
    val bgBottom = if (dark) Color(0xFF000000) else Color(0xFFEBECEF)
    val ink = if (dark) Color(0xFFF5F5F7) else Color(0xFF111114)
    val subInk = if (dark) Color(0xFF9A9AA2) else Color(0xFF6E6E75)
    // 光环取当前主题的强调色：默认是石墨灰，换彩色主题时整圈跟着变。
    val accent = KuaiMaoColors.brand

    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        intro.animateTo(1f, tween(durationMillis = INTRO_MS, easing = LinearOutSlowInEasing))
    }

    val loop = rememberInfiniteTransition(label = "splashLoop")
    val spin by loop.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = RING_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "splashSpin",
    )
    val sheen by loop.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SHEEN_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "splashSheen",
    )
    val breathe by loop.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "splashBreathe",
    )
    val bar by loop.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = BAR_PERIOD_MS, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "splashBar",
    )
    // 粒子用一个很慢的全局相位，避免每颗粒子各自起一个 transition。
    val drift by loop.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "splashDrift",
    )

    val title = themeConfig.homeTitle.ifBlank { "KuaiMaoSU" }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(bgTop, bgBottom))),
    ) {
        // ---- 中心光晕 + 刻度点 -----------------------------------------
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height * 0.44f
            val haloRadius = size.minDimension * (0.62f + 0.03f * breathe)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = if (dark) 0.10f else 0.07f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = haloRadius,
                ),
                radius = haloRadius,
                center = Offset(cx, cy),
            )
        }

        // ---- 上浮的微粒：只有 1~2dp，浓度 6%~16%，不聚团 ---------------
        Canvas(modifier = Modifier.fillMaxSize()) {
            val count = 18
            for (i in 0 until count) {
                val seed = (i * 0.6180339f) % 1f
                val speed = 0.05f + 0.06f * ((i % 5) / 4f)
                val t = (drift * speed * 4f + seed) % 1f
                val y = size.height * (1.02f - t * 1.06f)
                val x = size.width * (((seed * 7.31f) + 0.13f * sin((t + seed) * 6.283f)) % 1f)
                // 上下两端淡出，中段最亮，视觉上不会在半空「凭空出现」。
                val edge = 1f - abs(t - 0.5f) * 2f
                val alpha = (0.05f + 0.11f * edge) * intro.value
                drawCircle(
                    color = accent.copy(alpha = alpha),
                    radius = (1.0f + (i % 3) * 0.55f).dp.toPx(),
                    center = Offset(x, y),
                )
            }
        }

        // ---- 徽标 + 标题：整组居中，避免绝对偏移把字压到徽标上 ----------
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(196.dp),
                contentAlignment = Alignment.Center,
            ) {
                SplashRings(
                    spin = spin,
                    accent = accent,
                    appear = intro.value,
                )

                // 落位那一下：一圈细环从底板边缘扩散出去，只扩散一次。
                Canvas(modifier = Modifier.size(196.dp)) {
                    val p = ((intro.value - 0.18f) / 0.62f).coerceIn(0f, 1f)
                    if (p <= 0f || p >= 1f) return@Canvas
                    val r = size.minDimension * (0.30f + 0.20f * p)
                    drawCircle(
                        color = accent.copy(alpha = 0.22f * (1f - p)),
                        radius = r,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }

                LogoPlate(
                    sheen = sheen,
                    appear = intro.value,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.Center) {
                title.forEachIndexed { index, ch ->
                    val start = 0.30f + index * 0.042f
                    Text(
                        text = ch.toString(),
                        color = ink,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.graphicsLayer {
                            val p = ((intro.value - start) / 0.26f).coerceIn(0f, 1f)
                            alpha = p
                            translationY = (1f - p) * 16.dp.toPx()
                        },
                    )
                    if (index != title.lastIndex) Spacer(modifier = Modifier.width(1.2.dp))
                }
            }
            Spacer(modifier = Modifier.height(9.dp))
            Text(
                text = "KERNEL ROOT WORKBENCH",
                color = subInk,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.4.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = ((intro.value - 0.62f) / 0.30f).coerceIn(0f, 1f)
                },
            )
        }

        // ---- 底部：状态 + 来回滑动的一条光 -----------------------------
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 58.dp)
                .graphicsLayer { alpha = ((intro.value - 0.55f) / 0.35f).coerceIn(0f, 1f) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stateText,
                color = subInk,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (dark) 0.14f else 0.10f)),
            ) {
                Box(
                    modifier = Modifier
                        .width(34.dp)
                        .height(2.dp)
                        .graphicsLayer {
                            // 0~1 映射到 -34dp ~ 96dp，两端各留一点余量
                            translationX = (-34f + 130f * bar).dp.toPx()
                        }
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    accent.copy(alpha = 0.85f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
            }
        }
    }
}

/**
 * 两段反向旋转的细弧 + 一圈刻度。
 *
 * 弧用扫掠渐变：头部实、尾部淡，所以看起来是「在转」，而不是一段静态的圆弧。
 */
@Composable
private fun SplashRings(
    spin: Float,
    accent: Color,
    appear: Float,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = size.minDimension * 0.44f
        val center = Offset(size.width / 2f, size.height / 2f)
        val alpha = appear.coerceIn(0f, 1f)

        // 刻度：48 个 1dp 短划，整体很淡，只提供「仪表」感。
        val ticks = 48
        for (i in 0 until ticks) {
            val a = 6.2831855f * i / ticks
            val long = i % 4 == 0
            val inner = radius * (if (long) 0.985f else 1.0f)
            val outer = radius * 1.045f
            drawLine(
                color = accent.copy(alpha = (if (long) 0.16f else 0.08f) * alpha),
                start = Offset(center.x + cos(a) * inner, center.y + sin(a) * inner),
                end = Offset(center.x + cos(a) * outer, center.y + sin(a) * outer),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // 主弧：120°，头部亮尾部落到透明
        val arcBrush = Brush.sweepGradient(
            0.00f to Color.Transparent,
            0.10f to accent.copy(alpha = 0.10f * alpha),
            0.30f to accent.copy(alpha = 0.72f * alpha),
            0.42f to Color.Transparent,
            1.00f to Color.Transparent,
            center = center,
        )
        rotate(degrees = spin, pivot = center) {
            drawArc(
                brush = arcBrush,
                startAngle = -90f,
                sweepAngle = 128f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        // 副弧：更细、反向、更淡
        rotate(degrees = -spin * 1.45f, pivot = center) {
            drawArc(
                color = accent.copy(alpha = 0.26f * alpha),
                startAngle = 40f,
                sweepAngle = 54f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        // 主弧头部再点一颗小点，转动时更容易被眼睛捕捉到
        val head = Math.toRadians((spin - 90.0))
        drawCircle(
            color = accent.copy(alpha = 0.55f * alpha),
            radius = 2.1f.dp.toPx(),
            center = Offset(
                center.x + cos(head.toFloat()) * radius,
                center.y + sin(head.toFloat()) * radius,
            ),
        )
    }
}

/**
 * 徽标底板：黑色圆角方块 + 周期性斜向高光。
 *
 * 高光用 `SrcAtop` 与底板合成，所以只会落在圆角矩形内部，
 * 不会出现一条斜光穿到屏幕上的情况。
 */
@Composable
private fun LogoPlate(
    sheen: Float,
    appear: Float,
) {
    val shape = RoundedCornerShape(28.dp)
    val scale = 0.88f + 0.12f * appear
    Box(
        modifier = Modifier
            .size(118.dp)
            .graphicsLayer {
                alpha = appear.coerceIn(0f, 1f)
                scaleX = scale
                scaleY = scale
            }
            .shadow(18.dp, shape, clip = false)
            .clip(shape)
            .background(Color(0xFF0B0B0D))
            .border(1.dp, Color.White.copy(alpha = 0.07f), shape)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // 高光带：从左外侧扫到右外侧，带宽约 0.9 个板宽
                val band = size.width * 0.85f
                val x = -band + (size.width + band * 2.2f) * sheen
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.16f),
                            Color.White.copy(alpha = 0.26f),
                            Color.Transparent,
                        ),
                        start = Offset(x - band, 0f),
                        end = Offset(x + band, size.height * 0.9f),
                    ),
                    blendMode = BlendMode.SrcAtop,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_kuaimao_mark),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(0.64f),
        )
        // 底板右下角一枚极淡的角标点，让黑色方块不至于死板
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 14.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f)),
        )
    }
}

/**
 * KuaiMaoSU 品牌标记：白色 K 与猫头（与桌面图标同一个图形）。
 *
 * @param plate 是否自带黑色圆角底板。标题栏外面已经有深色头像底，
 *              不需要再叠一层；浅色背景上需要底板才能看清白 K。
 * @param progress 0→1 的入场进度，做淡入与轻微放大。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun KuaiMaoMark(
    progress: Float,
    modifier: Modifier = Modifier,
    plate: Boolean = false,
) {
    val t = progress.coerceIn(0f, 1f)
    val scale = 0.88f + 0.12f * t
    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = 0.30f + 0.70f * t
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (plate) {
                    Modifier
                        .clip(KuaiMaoDimens.rCard)
                        .background(Color.Black)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_kuaimao_mark),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(if (plate) 0.78f else 1f),
        )
    }
}
