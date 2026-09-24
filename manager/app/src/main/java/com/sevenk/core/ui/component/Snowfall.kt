package com.sevenk.core.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * 下雪特效(纯 Compose + Android Canvas 自绘,不依赖任何第三方库)。
 *
 * 雪花是真正的六角雪花(预渲染 6 种贴图),并且受【重力系统】驱动:
 *   · 重力加速 + 空气阻力 → 加速到终速(远的小而慢,近的大而快)
 *   · 跟随手机倾斜(见 GravityField),朝低的一侧流
 *   · 出屏环绕,保证连续降雨
 *   · 【3D 翻滚】每个雪花带 2D 自转 + 双轴翻面:
 *       横轴 scaleX = cos(主相位) —— 像巨魔雨那样的“翻面”
 *       副轴 scaleY = 小幅 cos(副相位) —— 让翻滚不只在同一平面上,更像在空中翻
 *       侧身(接近 90°)时亮度略降 → 立体感更强
 *
 * @param sizeScale  雪花大小倍率
 * @param speedScale 下落速度倍率(直接乘在重力加速度上)
 * @param tiltEnabled 是否跟随手机倾斜
 */
@Composable
fun SnowfallOverlay(
    modifier: Modifier = Modifier,
    flakeCount: Int = 130,
    sizeScale: Float = 1f,
    speedScale: Float = 1f,
    tiltEnabled: Boolean = true,
    gravityMode: Int = 0,
) {
    val sprites = remember { makeSnowflakeSprites() }
    val flakes = remember(flakeCount) { makeFlakes(flakeCount, sprites.size) }
    val depths = remember(flakes) { FloatArray(flakes.size) { flakes[it].depth } }

    val field = rememberGravityField(
        depths = depths,
        speedScale = speedScale,
        tiltEnabled = tiltEnabled,
        gravityMode = gravityMode,
        baseAccel = 900f,
        dragForFar = 6f,
        dragForNear = 3f,
    )

    val sizeScaleState = rememberUpdatedState(sizeScale)

    Canvas(modifier = modifier.fillMaxSize()) {
        field.w = size.width
        field.h = size.height
        field.ensureReady()
        val t = field.time.floatValue
        val sz = sizeScaleState.value
        val cull = 0.15f * (if (size.width > size.height) size.width else size.height)

        for (i in flakes.indices) {
            val f = flakes[i]
            val x = field.px[i]
            val y = field.py[i]
            if (x < -cull || x > size.width + cull || y < -cull || y > size.height + cull) continue

            val px = (f.sizeDp * sz).dp.toPx()
            val img = sprites[f.sprite]
            val sizePx = px.roundToInt().coerceAtLeast(1)
            val deg = f.rot + f.rotSpeed * t

            // 3D 翻滚(和巨魔雨同思路,但多一轴):
            //   横轴翻面 0.28~1.00 —— 侧到接近 90° 时被“压扁”,像绕竖轴转
            //   副轴 0.72~1.00   —— 轻微绕横轴转,两轴叠加就不像一张纸片在翻
            val spin = f.flipPhase + f.flipSpeed * t
            val flipX = 0.28f + 0.72f * cos(spin)
            val flipY = 0.72f + 0.28f * cos(f.tiltPhase + f.tiltSpeed * t)
            // 侧身时亮度略降,增强立体感
            val shade = (f.alpha * (0.74f + 0.26f * flipX)).coerceIn(0f, 1f)

            withTransform({
                rotate(degrees = deg, pivot = Offset(x, y))
                scale(scaleX = flipX, scaleY = flipY, pivot = Offset(x, y))
            }) {
                drawImage(
                    image = img,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset((x - px / 2f).roundToInt(), (y - px / 2f).roundToInt()),
                    dstSize = IntSize(sizePx, sizePx),
                    alpha = shade,
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}

private class Flake(
    val sprite: Int,
    val depth: Float,
    val sizeDp: Float,
    val alpha: Float,
    val rot: Float,
    val rotSpeed: Float,
    /** 横轴翻面速度 / 副轴摆动速度(弧度/秒) */
    val flipSpeed: Float,
    val flipPhase: Float,
    val tiltSpeed: Float,
    val tiltPhase: Float,
)

private fun makeFlakes(count: Int, spriteCount: Int): List<Flake> {
    val r = Random(20260914L)
    return List(count) {
        val depth = r.nextFloat()                       // 0=远 1=近
        Flake(
            sprite = r.nextInt(spriteCount),
            depth = depth,
            sizeDp = 5f + depth * 16f,                  // 5dp(远) ~ 21dp(近)
            alpha = 0.35f + depth * 0.60f,
            rot = r.nextFloat() * 360f,
            rotSpeed = (r.nextFloat() - 0.5f) * 40f,    // -20~20 度/秒
            // 小雪花翻得快一点,大雪花慢一点(和真实飘落的感觉接近)
            flipSpeed = 0.9f + r.nextFloat() * 2.2f - depth * 0.7f,
            flipPhase = r.nextFloat() * (2f * PI.toFloat()),
            tiltSpeed = 0.5f + r.nextFloat() * 1.6f,
            tiltPhase = r.nextFloat() * (2f * PI.toFloat()),
        )
    }
}

// ─────────────────────────── 雪花贴图 ───────────────────────────

private const val SPRITE_PX = 96

private fun makeSnowflakeSprites(): List<ImageBitmap> = List(6) { renderSnowflake(it) }

/** 画一朵六角雪花:6 条主枝 + 每枝若干分叉。变体之间分叉角度/数量不同。 */
private fun renderSnowflake(variant: Int): ImageBitmap {
    val bmp = Bitmap.createBitmap(SPRITE_PX, SPRITE_PX, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = SPRITE_PX * 0.048f
    }

    val cx = SPRITE_PX / 2f
    val cy = SPRITE_PX / 2f
    val r = SPRITE_PX * 0.44f
    val branchAngle = when (variant % 3) {
        0 -> 50.0
        1 -> 58.0
        else -> 66.0
    }
    val fractions = if (variant < 3) listOf(0.50f, 0.80f) else listOf(0.40f, 0.66f, 0.88f)
    val branchScale = if (variant < 3) (if (variant == 1) 1.25f else 1.0f) else 0.9f

    for (a in 0 until 6) {
        val ang = Math.toRadians((a * 60).toDouble())
        val dx = cos(ang).toFloat()
        val dy = sin(ang).toFloat()
        val ex = cx + dx * r
        val ey = cy + dy * r

        canvas.drawLine(cx, cy, ex, ey, paint)

        for (frac in fractions) {
            val bx = cx + dx * r * frac
            val by = cy + dy * r * frac
            val blen = r * (0.30f - frac * 0.12f) * branchScale
            for (side in intArrayOf(-1, 1)) {
                val bang = ang + side * Math.toRadians(branchAngle)
                canvas.drawLine(
                    bx, by,
                    bx + cos(bang).toFloat() * blen,
                    by + sin(bang).toFloat() * blen,
                    paint,
                )
            }
        }
    }

    return bmp.asImageBitmap()
}
