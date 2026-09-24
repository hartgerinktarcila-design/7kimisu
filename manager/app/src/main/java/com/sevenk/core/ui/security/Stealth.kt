package com.sevenk.core.ui.security

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.platform.LocalContext
import com.sevenk.core.Natives

/**
 * 隐身模式 = **只伪装界面**,不动桌面图标。
 *
 * 开启后:内核在 GET_INFO 时不再上报 MANAGER 标志 -> [Natives.isManager] 变 false,
 * 管理器整个界面自动变成"未安装 / 点击安装",超级用户、模块、设置里的 root 项
 * 全部消失。内核真实权限不变,所以本 App 还能自己把这个开关关掉。
 *
 * 关掉之后界面立即恢复成正常的管理器。
 *
 * 关于"隐藏桌面图标"(v0.8~v0.13 的二级隐身):
 * 已废弃。部分定制桌面在入口组件被禁用后不会刷新应用列表,会留下一个
 * 点不动的死图标(点它只会跳到"应用详情"),体验很差,还有把用户锁在
 * 外面的风险。现在改为启动/开机时**主动把桌面入口恢复成启用**,
 * 顺便治好历史上被藏起来的图标。
 */
object Stealth {

    private const val LAUNCHER_ALIAS = "com.sevenk.core.ui.LauncherAlias"

    /**
     * 内核 UAPI 里**隐身功能**是哪一版加进来的。
     *
     * 依据:`uapi/supercall.h` 的版本注释 `5: add stealth get/set`,
     * 同文件 `KERNEL_SU_UAPI_VERSION = 5`。
     * 用来把"内核太旧、根本没这个功能"和"内核有、但本 App 没被认主"分开
     * (审计 P0-2 要求拨号密令的三种文案说真话)。
     */
    private const val UAPI_WITH_STEALTH = 5

    /**
     * 🟢 v2.7：磁盘上那份**持久化**隐身标志（内核写，掉电/卸载重装都不丢）。
     *
     * 必须与内核侧 `kernel/manager/stealth.c` 的 `KSU_STEALTH_PATH` **逐字符一致**；
     * 内核在 POST_FS_DATA 时就是按它加载的（`ksu_stealth_load`）。
     * 用途：内核 ioctl 读不出来（[State.UNKNOWN]）时的兜底信号 —— 见
     * `ui/util/KsuCli.kt::getPersistedStealthFlag` 与 `InstallScreen.kt` 里那段
     * 「隐身时藏起直接安装」的 `when`。
     */
    const val STEALTH_FLAG_PATH = "/data/adb/sevenk/stealth"

    /** 内核里的开关:1 = 开启,0 = 关闭,其它(-1) = 内核不支持 / 读取失败 / 没被认主 */
    fun kernelState(): Int = runCatching { Natives.stealthState() }.getOrDefault(-1)

    /**
     * 隐身的**三态**(v2.1,审计 P0-2)。
     *
     * 以前只有 [isEnabled] 一个布尔(`kernelState() == 1`),把
     * 「明确读到 0(关着)」和「读失败 / 内核不支持」**压成了同一个 false**。
     * 后果很危险:一次 ioctl 读失败 → 界面以为"隐身没开" → 把
     * 「直接安装」「越狱」这些**只有 root 管理器才有的东西**露出来 → 隐身当场穿帮。
     */
    enum class State {
        /** 明确读到 1:隐身开着 */
        ON,

        /** 明确读到 0:隐身关着 */
        OFF,

        /** 读失败 / 内核没这个功能 / 本 App 没被认主 —— **不知道**,一律按"可能开着"办 */
        UNKNOWN,
    }

    /**
     * 读三态。
     *
     * ⚠️ **安全方向**(用户 2026-09-21 审计 P0-2 定的准则):
     * 拿不准时**按"可能开着"处理** —— 宁可不露馅。
     * "以为没隐身 → 露出 root 特征"比"以为隐身了 → 用户多点一下"严重得多。
     */
    fun state(): State = when (kernelState()) {
        1 -> State.ON
        0 -> State.OFF
        else -> State.UNKNOWN
    }

    /**
     * 界面上要不要**藏起 root 特征**(安装页的「直接安装」、越狱按钮、SELinux 卡…)。
     *
     * =「不是明确读到 0」。所以:开着 → 藏;读失败/不支持/没被认主 → **也藏**。
     * 只有"内核明确说隐身是关的"才敢把 root 特征露出来。
     */
    fun looksStealthy(): Boolean = state() != State.OFF

    /** ⚠️ 语义是"读不到就当没开",**不要**用它决定该不该藏 root 特征,用 [looksStealthy] */
    fun isEnabled(): Boolean = kernelState() == 1

    /**
     * 内核到底**有没有**隐身功能(和"认不认主"无关)。
     *
     * 判据是 UAPI 版本:>= [UAPI_WITH_STEALTH] 就说明内核是带隐身那一代。
     * `stealth_get()` 失败时靠它区分两种失败原因(见 [StealthReceiver.prepareRestore]):
     *   · 内核 UAPI < 5 → 「内核太旧,不支持隐身模式」;
     *   · 内核 UAPI >= 5 → 「本 App 现在没被内核认主」。
     * 读不到 UAPI(原生库没加载等)按"没有"处理 —— 给出更保守的那句文案。
     */
    fun kernelHasStealthFeature(): Boolean =
        runCatching { Natives.kernelUAPIVersion >= UAPI_WITH_STEALTH }.getOrDefault(false)

    /**
     * 内核到底**是不是 7kimisu 的**（不看功能新旧）—— 只看"内核认不认这个 App 当管理器"。
     *
     * 🟡4（v2.4）用途：拨号密令关隐身失败时，把两种失败原因分开说：
     *   · [kernelHasStealthFeature] 为 false **且**本函数为 false → 内核**不是 7kimisu 的**
     *     （官方 KernelSU / 别的 root 方案）。以前这里一律说成「内核太旧」，是**误导**；
     *   · [kernelHasStealthFeature] 为 false 但本函数为 true → 内核是我们的，只是**太旧**。
     *
     * 为什么在这条分支上 `isManager` 是可信的（不会被隐身压掉）：
     *   隐身功能本身就是 UAPI 5 才有的；能走到"没有隐身功能"这条分支，说明内核
     *   **报不出 UAPI 5**，也就不可能正开着隐身，于是内核不会去隐藏 MANAGER 标志。
     *
     * 拿不准（JNI 没加载 / ioctl 失败）一律按 false —— 宁可说"内核不是 7kimisu
     * （请重启或卸载另一个管理器）"，那也是安全且正确的下一步。
     */
    fun kernelLooksOurs(): Boolean = runCatching { Natives.isManager }.getOrDefault(false)

    fun setEnabled(enabled: Boolean): Boolean =
        runCatching { Natives.stealthSet(enabled) }.getOrDefault(false)

    /**
     * 设置页用:开/关隐身并**把成败说出来**(v2.1,审计 P0-2)。
     *
     * 以前调用点直接 `Stealth.setEnabled(true)` 就接着重建界面 ——
     * 开关失败(内核太旧 / 没被认主)时**一个字都不说**,用户以为开了,
     * 其实界面根本没隐身;更糟的是界面还照常显示成"开着"的样子。
     *
     * @return null = 成功;非 null = 给用户看的原因(调用方必须弹出来)
     */
    fun setEnabledReporting(enabled: Boolean): String? {
        if (setEnabled(enabled)) return null
        val action = if (enabled) "开启" else "关闭"
        // 🟡4（v2.4）：与拨号密令那边**同一套四态判据** ——
        // 「内核不是 7kimisu」和「内核是我们的但太旧」以前会被混成同一句"内核太旧"。
        return when {
            kernelHasStealthFeature() ->
                "${action}隐身失败:本 App 现在没被内核认主。请重启设备,或卸载另一个 7kimisu 管理器后再试"
            kernelLooksOurs() -> "内核太旧,不支持隐身模式"
            else -> "内核不是 7kimisu(当前 root 由别的方案提供):请重启设备,或卸载另一个 root 管理器后再试"
        }
    }

    /**
     * 确保桌面图标在。每次启动、开机都调一次 ——
     * 既保证正常状态下图标一定存在,也顺手把旧版本藏起来的图标找回来。
     */
    fun ensureLauncherVisible(context: Context) {
        Thread {
            runCatching {
                val pm = context.packageManager
                val component = ComponentName(context.packageName, LAUNCHER_ALIAS)
                if (pm.getComponentEnabledSetting(component) !=
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                ) {
                    pm.setComponentEnabledSetting(
                        component,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
        }.start()
    }
}

/*
 * ⚠️ 2026-09-21(v0.13.157):这里原来有个 `StealthLook.isPlain()`("隐身期间素颜"),**已删除**。
 *
 * 用户给的判据(原话):
 *   「我指的是背景图片、动画公告这些,这些都是一个**没有 root 的用户**点进来才有的界面」
 * —— 隐身时要等于「一个没有 root 的用户打开这个 App」的**完整体验**:
 *     · 没有 root 的人也看得到 → 壁纸 / 透明度 / 动画 / 字体 / 扁平化 / 主题配色、公告弹窗、
 *       「关于 7kimisu / 支持开发 / 了解 KernelSU」三张卡、点「内核未就绪」弹出的原说明;
 *     · 只有有 root 的人才看得到 → 安装页里的「直接安装(推荐)」「安装到未使用槽位」。
 *
 * 所以"素颜"是**做反了**:个性化和 root 无关,一个没 root 的用户照样有壁纸、有动画。
 * 隐身时**只该改"显示什么内容",不该改"长什么样"**。
 * v0.13.157 起,隐身与正常的外观**完全同一条代码路径**:全工程不再有任何
 * 「隐身 → 不画壁纸 / 关透明 / 关特效 / 关扁平 / 换默认配色」的判断
 * (原来 8 处 `StealthLook.isPlain()` 调用点已逐条撤掉)。
 *
 * 判定隐身与否仍然只看 [Stealth.isEnabled](内容侧用),它内部 runCatching,读不到 = false。
 */

/**
 * 「重建界面」的推荐姿势:先清 ViewModelStore,再 recreate。
 *
 * 为什么要清 ViewModel:ViewModel 会跨 Activity.recreate() 存活(官方设计),
 * 而 MainActivityViewModel / HomeViewModel 的状态都是构造时算一次的 ——
 * 不清的话,界面会拿【旧 VM 的旧状态】重新渲染。
 * 典型症状:关掉隐身/切换界面风格后,界面还是旧样子,要等某次 refresh 才对。
 */
fun restartUiFresh(context: Context) {
    val activity = context.findActivity()
    (activity as? androidx.lifecycle.ViewModelStoreOwner)?.let {
        runCatching { it.viewModelStore.clear() }
    }
    activity?.recreate()
}

/**
 * 从任意 Context 里挖出真正的 Activity。
 * Compose 的 LocalContext 有时是 ContextThemeWrapper 而不是 Activity,
 * `context as? Activity` 会直接失败(recreate 静默不执行),所以必须解包。
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

