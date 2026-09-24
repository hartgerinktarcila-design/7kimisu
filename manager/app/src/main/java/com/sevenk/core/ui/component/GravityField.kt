package com.sevenk.core.ui.component

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 重力方向(屏幕坐标系):默认竖直向下 (0, 1)。
 * 由传感器在后台更新;帧循环每帧读它,所以不触发重组。
 */
class GravityHolder {
    @Volatile
    var x: Float = 0f

    @Volatile
    var y: Float = 1f
}

/**
 * 注册重力传感器(优先 TYPE_GRAVITY,没有就退回加速度计),把重力方向换算到
 * 【当前屏幕坐标系】并做低通滤波。
 *
 * 坐标换算:重力感应读数 a(设备坐标)指向"上",所以"下" = -a;
 * 再按屏幕旋转把设备轴映射到屏幕轴。
 *
 * @param tiltEnabled false 时不做感应,恒为竖直向下。
 */
@Composable
fun rememberGravityHolder(tiltEnabled: Boolean, gravityMode: Int): GravityHolder {
    val context = LocalContext.current
    val view = LocalView.current
    val holder = remember { GravityHolder() }

    DisposableEffect(tiltEnabled, gravityMode, context) {
        if (!tiltEnabled) {
            holder.x = 0f
            holder.y = 1f
            return@DisposableEffect onDispose { }
        }

        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sm == null || sensor == null) {
            return@DisposableEffect onDispose { }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val a = event.values
                if (a.size < 2) return
                // 设备轴 -> 屏幕轴(随屏幕旋转)
                val ax = a[0]
                val ay = a[1]
                val rotation = view.display?.rotation ?: Surface.ROTATION_0
                var gx: Float
                var gy: Float
                // 依据官方 remapCoordinateSystem:参数 X/Y 指的是
                // “新坐标系的哪个轴与原 X/Y 轴重合”。于是:
                //   ROTATION_90  -> 新Y(屏幕上)=原X, 新X(屏幕右)=原-Y
                //   ROTATION_270 -> 新Y(屏幕上)=原-X, 新X(屏幕右)=原+Y
                // (读数 a 指向"上",所以重力"下" = -a)
                when (rotation) {
                    Surface.ROTATION_90 -> { gx = ay; gy = ax }
                    Surface.ROTATION_180 -> { gx = ax; gy = -ay }
                    Surface.ROTATION_270 -> { gx = -ay; gy = -ax }
                    else -> { gx = -ax; gy = ay }
                }
                // 机型校准:移植 ROM 的传感器轴向可能是反的/镜像的
                when (gravityMode) {
                    1 -> gx = -gx                 // 左右翻转
                    2 -> gy = -gy                 // 上下翻转
                    3 -> { gx = -gx; gy = -gy }   // 反转 180°
                }
                val len = sqrt(gx * gx + gy * gy)
                // 手机平放时平面内分量为 0 → 保持上次方向(相当于"竖直向下")
                if (len > 0.35f) {
                    val nx = gx / len
                    val ny = gy / len
                    // 低通滤波,避免抖动。
                    // 若新方向和当前方向相反(比如倒过来),插值会卡在中点,
                    // 所以这种直接跳过去。
                    if (holder.x * nx + holder.y * ny < 0f) {
                        holder.x = nx
                        holder.y = ny
                    } else {
                        var sx = holder.x * 0.82f + nx * 0.18f
                        var sy = holder.y * 0.82f + ny * 0.18f
                        val l2 = sqrt(sx * sx + sy * sy).coerceAtLeast(0.0001f)
                        holder.x = sx / l2
                        holder.y = sy / l2
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }
    return holder
}

/**
 * 一小片粒子的重力场:位置/速度都是像素。
 *
 * 物理:每个粒子受重力加速度 a = g * G 作用,并有与速度成正比的空气阻力
 * (稳定的一阶阻尼),于是会加速到"终速" G/drag。不同粒子 drag 不同 →
 * 远近快慢不同。粒子出屏后从对面环绕进来,保证雨不断流。
 */
class GravityField(val count: Int) {
    val px = FloatArray(count)
    val py = FloatArray(count)
    val vx = FloatArray(count)
    val vy = FloatArray(count)
    val drag = FloatArray(count)

    /** 累计动画时间(秒);draw 阶段读它 → 只重绘不重组 */
    val time = mutableFloatStateOf(0f)

    internal var w = 0f
    internal var h = 0f
    private var initialized = false

    internal fun scatter(r: Random) {
        if (w <= 0f || h <= 0f) return
        for (i in 0 until count) {
            px[i] = r.nextFloat() * w
            py[i] = r.nextFloat() * h
            vx[i] = (r.nextFloat() - 0.5f) * 40f
            vy[i] = r.nextFloat() * 40f
        }
        initialized = true
    }

    internal fun ensureReady() {
        if (!initialized) scatter(Random(0x0DDBA11))
    }
}

/**
 * 创建并驱动一个重力场。
 *
 * @param depths        每个粒子的"远近"(0=远 1=近),决定阻力(近的阻力小 → 落得快)
 * @param speedScale    用户的速度倍率(直接乘在重力加速度上)
 * @param tiltEnabled   是否跟随手机倾斜
 * @param baseAccel     标准重力加速度(px/s²)
 * @param dragForFar    最远粒子的阻力
 * @param dragForNear   最近粒子的阻力
 */
@Composable
fun rememberGravityField(
    depths: FloatArray,
    speedScale: Float,
    tiltEnabled: Boolean,
    gravityMode: Int,
    baseAccel: Float,
    dragForFar: Float,
    dragForNear: Float,
): GravityField {
    val gravity = rememberGravityHolder(tiltEnabled, gravityMode)
    val field = remember(depths) {
        GravityField(depths.size).also { f ->
            for (i in depths.indices) {
                f.drag[i] = dragForFar - depths[i] * (dragForFar - dragForNear)
            }
        }
    }
    val speedState = rememberUpdatedState(speedScale)
    val accelState = rememberUpdatedState(baseAccel)

    LaunchedEffect(field) {
        var last = 0L
        var t = 0f
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    t += dt
                    step(field, dt, gravity, speedState.value, accelState.value)
                }
                last = now
                field.time.floatValue = t
            }
        }
    }
    return field
}

private fun step(
    field: GravityField,
    dt: Float,
    gravity: GravityHolder,
    speedScale: Float,
    baseAccel: Float,
) {
    val w = field.w
    val h = field.h
    if (w <= 0f || h <= 0f) return
    field.ensureReady()

    val g = baseAccel * speedScale
    val ax = gravity.x * g
    val ay = gravity.y * g

    // 出屏一圈再回来,保证连续
    val margin = 0.15f * (if (w > h) w else h)
    val wx = w + 2f * margin
    val wy = h + 2f * margin

    for (i in 0 until field.count) {
        val damp = 1f / (1f + field.drag[i] * dt)
        field.vx[i] = (field.vx[i] + ax * dt) * damp
        field.vy[i] = (field.vy[i] + ay * dt) * damp

        var x = field.px[i] + field.vx[i] * dt
        var y = field.py[i] + field.vy[i] * dt

        if (x < -margin) x += wx else if (x > w + margin) x -= wx
        if (y < -margin) y += wy else if (y > h + margin) y -= wy

        field.px[i] = x
        field.py[i] = y
    }
}
