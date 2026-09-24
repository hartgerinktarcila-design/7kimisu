package com.sevenk.core.ui.component

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sevenk.core.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 「巨魔雨」特效:蓝巨魔图标翻滚着往下落。
 *
 * 图标用内置资源 R.drawable.troll_face(后台线程解一次)。同样受【重力系统】驱动:
 *   · 重力加速 + 空气阻力 → 加速到终速
 *   · 跟随手机倾斜,朝低的一侧流
 *   · 出屏环绕,保证连续
 *   · 每个图标还带 2D 自转 + 轻微"翻面"(scaleX 用 cos 摆动)→ 像在 3D 翻滚
 *
 * @param sizeScale  图标大小倍率
 * @param speedScale 下落速度倍率
 * @param tiltEnabled 是否跟随手机倾斜
 */
@Composable
fun TrollRainOverlay(
    modifier: Modifier = Modifier,
    itemCount: Int = 36,
    sizeScale: Float = 1f,
    speedScale: Float = 1f,
    tiltEnabled: Boolean = true,
    gravityMode: Int = 0,
) {
    val context = LocalContext.current
    // 图标在后台线程解码,避免开启瞬间卡一下
    val spriteState = produceState<ImageBitmap?>(initialValue = null) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                BitmapFactory.decodeResource(context.resources, R.drawable.troll_face)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val sprite = spriteState.value ?: return

    val items = remember(itemCount) { makeItems(itemCount) }
    val depths = remember(items) { FloatArray(items.size) { items[it].depth } }

    val field = rememberGravityField(
        depths = depths,
        speedScale = speedScale,
        tiltEnabled = tiltEnabled,
        gravityMode = gravityMode,
        baseAccel = 1400f,
        dragForFar = 4f,
        dragForNear = 1.6f,
    )

    val sizeScaleState = rememberUpdatedState(sizeScale)

    Canvas(modifier = modifier.fillMaxSize()) {
        field.w = size.width
        field.h = size.height
        field.ensureReady()
        val t = field.time.floatValue
        val sz = sizeScaleState.value
        val cull = 0.15f * (if (size.width > size.height) size.width else size.height)

        for (i in items.indices) {
            val f = items[i]
            val x = field.px[i]
            val y = field.py[i]
            if (x < -cull || x > size.width + cull || y < -cull || y > size.height + cull) continue

            val px = (f.sizeDp * sz).dp.toPx()
            val sp = px.roundToInt().coerceAtLeast(1)
            val deg = f.rot + f.rotSpeed * t
            // 轻微"翻面"(0.1~1.0):像在 3D 里翻,又不会翻到看不见
            val flip = 0.55f + 0.45f * cos(t * f.flipSpeed + f.flipPhase)

            withTransform({
                rotate(degrees = deg, pivot = Offset(x, y))
                scale(scaleX = flip, scaleY = 1f, pivot = Offset(x, y))
            }) {
                drawImage(
                    image = sprite,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(sprite.width, sprite.height),
                    dstOffset = IntOffset((x - px / 2f).roundToInt(), (y - px / 2f).roundToInt()),
                    dstSize = IntSize(sp, sp),
                    alpha = f.alpha,
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}

private class Item(
    val depth: Float,
    val sizeDp: Float,
    val alpha: Float,
    val rot: Float,
    val rotSpeed: Float,
    val flipSpeed: Float,
    val flipPhase: Float,
)

private fun makeItems(count: Int): List<Item> {
    val r = Random(0x7A11)
    return List(count) {
        val depth = r.nextFloat()                       // 0=远 1=近
        Item(
            depth = depth,
            sizeDp = 14f + depth * 26f,                 // 14dp(远) ~ 40dp(近)
            alpha = 0.60f + depth * 0.40f,
            rot = (r.nextFloat() - 0.5f) * 70f,
            rotSpeed = (r.nextFloat() - 0.5f) * 50f,    // -25~25 度/秒
            flipSpeed = 0.5f + r.nextFloat() * 1.6f,
            flipPhase = r.nextFloat() * (2f * PI.toFloat()),
        )
    }
}
