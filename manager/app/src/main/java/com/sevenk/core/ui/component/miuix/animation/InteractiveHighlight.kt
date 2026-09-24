package com.sevenk.core.ui.component.miuix.animation

import android.annotation.SuppressLint
import android.graphics.RuntimeShader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastCoerceIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.sevenk.core.ui.component.miuix.modifier.inspectDragGestures
import org.intellij.lang.annotations.Language
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported

@SuppressLint("NewApi")
class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset }
) {

    private val pressProgressAnimationSpec =
        spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec =
        spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    val offset: Offset get() = positionAnimation.value - startPosition

    /**
     * 🟠（v2.14）`android.graphics.RuntimeShader` 是 **Android 13 (API 33)** 才有的类。
     *
     * 本类头上那个 `@SuppressLint("NewApi")` 只压住了 lint，**压不住运行期**：
     * 在 Android 12 上构造 RuntimeShader 会抛 `NoClassDefFoundError` /
     * `RuntimeException`，表现就是"一开【悬浮底栏 / 交互高亮】就闪退"
     * （默认关着，所以一直没被发现；一开就中）。
     *
     * 现在照抄 `ui/component/liquid/Lens.kt:19` 的做法：先问 `isRuntimeShaderSupported()`，
     * 不支持就**不构造**、也不画 shader 那一层 —— 上面那层纯色高亮照旧生效（优雅降级）。
     */
    private val shaderSupported = isRuntimeShaderSupported()

    @Language("AGSL")
    private val shader: RuntimeShader? =
        if (!shaderSupported) {
            null
        } else {
            RuntimeShader(
                """
    uniform float2 size;
    layout(color) uniform half4 color;
    uniform float radius;
    uniform float2 position;
    
    half4 main(float2 coord) {
        float dist = distance(coord, position);
        float intensity = smoothstep(radius, radius * 0.5, dist);
        return color * intensity;
    }"""
            )
        }

    val modifier: Modifier =
        Modifier.drawWithContent {
            val progress = pressProgressAnimation.value
            if (progress > 0f) {
                drawRect(
                    Color.White.copy(0.06f * progress),
                    blendMode = BlendMode.Plus
                )
                // API < 33（或 shader 不可用）→ shader 为 null，这一层直接跳过
                val sh = shader
                if (sh != null) {
                    sh.apply {
                        val position = position(size, positionAnimation.value)
                        setFloatUniform("size", size.width, size.height)
                        setColorUniform("color", Color.White.copy(0.12f * progress).toArgb())
                        setFloatUniform("radius", size.minDimension * 1.2f)
                        setFloatUniform(
                            "position",
                            position.x.fastCoerceIn(0f, size.width),
                            position.y.fastCoerceIn(0f, size.height)
                        )
                    }
                    drawRect(
                        ShaderBrush(sh),
                        blendMode = BlendMode.Plus
                    )
                }
            }

            drawContent()
        }

    val gestureModifier: Modifier =
        Modifier.pointerInput(animationScope) {
            inspectDragGestures(
                onDragStart = { down ->
                    startPosition = down.position
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                        launch { positionAnimation.snapTo(startPosition) }
                    }
                },
                onDragEnd = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
                onDragCancel = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                }
            ) { change, _ ->
                animationScope.launch { positionAnimation.snapTo(change.position) }
            }
        }
}