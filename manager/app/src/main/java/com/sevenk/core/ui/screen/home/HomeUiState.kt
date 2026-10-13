package com.sevenk.core.ui.screen.home

import androidx.compose.runtime.Immutable
import com.sevenk.core.KernelVersion
import com.sevenk.core.ui.util.module.LatestVersionInfo

@Immutable
data class HomeUiState(
    val kernelVersion: KernelVersion,
    val ksuVersion: Int?,
    val managerUAPIVersion: Int,
    val kernelUAPIVersion: Int?,
    val lkmMode: Boolean?,
    val isLkmBundled: Boolean,
    val isManager: Boolean,
    /**
     * `isManager == false` 的**第二种**原因:内核还没把本 App 认成管理器。
     *
     * `isManager` 为 false 有两种情况,处理方式相反,以前混在一起都显示「点击安装」,
     * 会把用户骗去重刷 LKM(实际白折腾、还有风险):
     *   ① **隐身模式** —— 内核其实仍然认我们(内核的 is_manager() 为真,只是不上报 MANAGER 标志),
     *      `Natives.stealthState()` 能读到 1。界面显示「未安装」是伪装的一部分,正常。
     *   ② **内核没认主** —— 内核里存的 manager appid 与本 App 的 UID 对不上
     *      (**卸载重装会换 UID**,这是主要触发原因)。此时 stealthState() 读不到(返回 -1)。
     *      只要**重启一次设备**,内核开机时的 throne_tracker 会重新扫描 /data/app 认主
     *      —— **不用重刷内核**。
     */
    val managerNotRecognized: Boolean = false,
    val isManagerPrBuild: Boolean,
    val isKernelPrBuild: Boolean,
    val requiresNewKernel: Boolean,
    val requiresNewManager: Boolean,
    val isRootAvailable: Boolean,
    val isSafeMode: Boolean,
    val isLateLoadMode: Boolean,
    val checkUpdateEnabled: Boolean,
    val latestVersionInfo: LatestVersionInfo,
    val currentManagerVersionCode: Long,
    val systemInfo: SystemInfo,
) {
    val isSELinuxPermissive: Boolean
        get() = systemInfo.selinuxStatus == "Permissive"

    val showGkiWarning: Boolean
        get() = ksuVersion != null && lkmMode == false

    // ⚠️ 2026-09-16 关闭「内嵌 LKM 有可用更新」提示。
    //
    // 上游判定是 `ksuVersion != currentManagerVersionCode`，成立的前提是：
    //   管理器 versionCode 与内嵌 .ko 的 KSU_VERSION 来自同一次内核构建 → 两者相等。
    // v0.13.65 为了修「新版装不上」把 versionCode 改成按版本名推导(0.13.65 → 130650)，
    // 而内嵌 .ko 的 KSU_VERSION 实测仍是 32649（8 个 .ko 全部反汇编核对过：
    //   android16-6.12 为 `mov w11, #0x7f89`，android12-5.10 在 .rodata 里）
    // → 两个数永远不等 → 提示必然误报，且会引导用户去重刷/重装 LKM。
    //
    // 关闭是安全的：这不是更新 LKM 的唯一入口，安装页仍可手动选装 LKM。
    // 若将来要恢复，不要再用 currentManagerVersionCode 比较，应改为比较
    // 「本 APK 内嵌的那份 .ko 的版本号」，且用 `>` 而非 `!=`——语义才是「有可用更新」，
    // 也免疫 bundled 比 running 更旧（浅克隆下 KSU_VERSION 会变小）的情况。
    val showLkmUpdate: Boolean
        get() = false

    val showCustomLkmBadge: Boolean
        get() = lkmMode == true && !isLkmBundled

    val showRootWarning: Boolean
        get() = ksuVersion != null && !isRootAvailable

    val showManagerPrBuildWarning: Boolean
        get() = isManager && isManagerPrBuild

    val showKernelPrBuildWarning: Boolean
        get() = isManager && !isManagerPrBuild && isKernelPrBuild

    val hasUpdate: Boolean
        get() = latestVersionInfo.versionCode > currentManagerVersionCode
}

/**
 * **隐身模式**下的首页伪装:把 [HomeUiState] 归一化成「未安装」那一支。
 *
 * 隐身开着时,首页跟「没刷内核/没认主的机器」看起来一模一样 —— 状态卡显示
 * 「未安装 / 点击安装」(原版 KernelSU 的标准样子),点一下进安装页,
 * 安装页本身对没 root 的设备就只给「选文件修补」这条路,不露馅。
 *
 * (2026-10-14 调整:早期版本曾伪装成「内核未就绪」并弹说明窗 —— 那个写法会把
 *  没 root 的用户挡在修补入口外,已按用户要求撤回原版行为。)
 *
 * 做法是**在 UiState 层面归一化**(Miuix / Material 两套界面都自动生效),
 * 不新画任何 UI —— 少一套 UI 就少一个将来会漏馅的地方:
 *   - [managerNotRecognized] = true + [isRootAvailable] = false;
 *   - 清掉所有"其实认主了 / 其实有 root / 其实是 LKM"的字段,
 *     否则会漏出绿卡、「安装 / 更新内核」卡、更新卡、GKI 警告卡。
 *
 * ⚠️ **不动 [isManager]**:那是内核给的**真实**值(隐身时本来就是 false),
 *    底栏 / 导航 / 闸门 / 数据迁移都靠它,这里一个字都不许改。
 * ⚠️ 这里只做**内容侧**归一化,**外观侧一个字都不动** ——
 *    壁纸 / 透明度 / 动画 / 字体 / 配色 / 扁平化在隐身时全部照常(见 `security/Stealth.kt` 顶部注释)。
 */
fun HomeUiState.asStealthKernelNotReady(): HomeUiState = copy(
    ksuVersion = null,
    kernelUAPIVersion = null,
    lkmMode = null,
    isLkmBundled = false,
    managerNotRecognized = true,
    isManagerPrBuild = false,
    isKernelPrBuild = false,
    requiresNewKernel = false,
    requiresNewManager = false,
    isRootAvailable = false,
    isSafeMode = false,
    isLateLoadMode = false,
    // 隐身时不检查更新:更新卡会写版本号 + 下载链接,还多一次联网(伪装期越安静越好)
    checkUpdateEnabled = false,
    latestVersionInfo = LatestVersionInfo(),
)

@Immutable
data class HomeActions(
    val onInstallClick: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onJailbreakClick: () -> Unit = {},
)
