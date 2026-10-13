package com.sevenk.core.ui.screen.home

import android.os.Build
import android.system.Os
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sevenk.core.BuildConfig
import com.sevenk.core.KernelVersion
import com.sevenk.core.Natives
import com.sevenk.core.getKernelVersion
import com.sevenk.core.ksuApp
import com.sevenk.core.ui.util.getSELinuxStatusRaw
import com.sevenk.core.ui.util.resolveDeviceName
import com.sevenk.core.ui.util.rootAvailable

@Immutable
data class ManagerVersion(val versionName: String, val versionCode: Long)

@Immutable
data class SystemInfo(
    val kernelVersion: String,
    val managerVersion: String,
    val deviceModel: String,
    val fingerprint: String,
    val selinuxStatus: String,
    val seccompStatus: Int,
)

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
     *   ① 隐身模式 —— 内核其实仍认我们，只是不上报 MANAGER 标志，stealthState() 能读到 1；
     *   ② 内核没认主 —— stealthState() 读不到(-1)，重启一次设备即可（不必重刷内核）。
     */
    val managerNotRecognized: Boolean = false,
    val isManagerPrBuild: Boolean,
    val isKernelPrBuild: Boolean,
    val requiresNewKernel: Boolean,
    val isRootAvailable: Boolean,
    val isSafeMode: Boolean,
    val isLateLoadMode: Boolean,
    /** 隐身三态的原始值（1 开 / 0 关 / -1 读不到）。仅在未归一化（隐身关）时有意义。 */
    val stealthState: Int,
    val systemInfo: SystemInfo,
) {
    val showGkiWarning: Boolean
        get() = ksuVersion != null && lkmMode == false

    val showRootWarning: Boolean
        get() = ksuVersion != null && !isRootAvailable
}

/**
 * **隐身模式**下的首页伪装:把 [HomeUiState] 归一化成「内核未就绪」那一支 ——
 * 跟「新 App 装在没刷内核的机器上」看起来一模一样（managerNotRecognized = true、
 * 拿不到 root），只有一句「内核未就绪 / 点一下看怎么处理」+ 机型信息。
 *
 * ⚠️ 不动 [isManager]:那是内核给的真实值（隐身时本来就是 false），底栏/导航靠它。
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
    isRootAvailable = false,
    isSafeMode = false,
    isLateLoadMode = false,
    // 伪装界面的信息卡也会展示隐身状态 —— 抹成"读不到"，
    // 与真·未安装设备（没被认主时 stealthState() 本就返回 -1）逐字一致。
    stealthState = -1,
)

fun getManagerVersion(): ManagerVersion {
    val packageInfo = runCatching {
        ksuApp.packageManager.getPackageInfo(ksuApp.packageName, 0)
    }.getOrNull()
    val versionCode = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L
    return ManagerVersion(
        versionName = packageInfo?.versionName ?: "未知",
        versionCode = versionCode,
    )
}

class HomeViewModel : ViewModel() {

    // 首帧只算不碰 shell 的部分（纯 JNI + 本地信息）；
    // root / SELinux 探测挪到 IO 线程，算完再刷一次 UI。
    private val _uiState = MutableStateFlow(buildState(probeRoot = false))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) { buildStateOrNull(probeRoot = true) }
                ?: return@launch
            _uiState.update { state }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) { buildStateOrNull(probeRoot = true) }
                ?: return@launch
            _uiState.update { state }
        }
    }

    /** [buildState] 的安全外壳:失败返回 null(调用方保持原值),不把异常抛给协程。 */
    private fun buildStateOrNull(probeRoot: Boolean): HomeUiState? = try {
        buildState(probeRoot)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "构建首页状态失败,保持原值: ", e)
        null
    }

    private fun buildState(probeRoot: Boolean): HomeUiState {
        // 每一个 runCatching 都是"防闪退"：Natives 是 JNI(可能 UnsatisfiedLinkError)，
        // 读设置走磁盘，Os.uname 也可能抛。各自兜底，最坏某个字段显示默认值。
        val kernelVersion = runCatching { getKernelVersion() }
            .getOrElse { KernelVersion(-1, -1, -1) }
        val isManager = runCatching { Natives.isManager }.getOrDefault(false)
        val stealthState = runCatching { Natives.stealthState() }.getOrDefault(-1)
        val managerNotRecognized = !isManager && stealthState != 1
        // 隐身模式(内核 stealthState == 1):内核其实仍认我们，只是不上报 MANAGER 标志。
        val stealthOn = stealthState == 1
        val ksuVersionRaw = runCatching { Natives.version }.getOrDefault(-1)
        val kernelUapiRaw = runCatching { Natives.kernelUAPIVersion }.getOrDefault(0)
        val managerUapiRaw = runCatching { Natives.managerUAPIVersion }.getOrDefault(0)
        val ksuVersion = if (isManager) ksuVersionRaw else null
        val kernelUAPIVersion = if (isManager) kernelUapiRaw else null
        val lkmMode = if (ksuVersion != null && kernelVersion.isGKI()) {
            runCatching { Natives.isLkmMode }.getOrNull()
        } else {
            null
        }
        // 要起 shell 的探测只在 IO 线程做
        val isRootAvailable = if (probeRoot) {
            runCatching { rootAvailable() }.getOrDefault(false)
        } else {
            false
        }
        val managerVersion = getManagerVersion()

        val state = HomeUiState(
            kernelVersion = kernelVersion,
            ksuVersion = ksuVersion,
            lkmMode = lkmMode,
            isLkmBundled = lkmMode == true &&
                runCatching { Natives.isLkmBundled }.getOrDefault(false),
            isManager = isManager,
            managerNotRecognized = managerNotRecognized,
            isManagerPrBuild = BuildConfig.IS_PR_BUILD,
            isKernelPrBuild = runCatching { Natives.isPrBuild }.getOrDefault(false),
            requiresNewKernel = isManager && managerUapiRaw > kernelUapiRaw,
            kernelUAPIVersion = kernelUAPIVersion,
            managerUAPIVersion = managerUapiRaw,
            isRootAvailable = isRootAvailable,
            isSafeMode = runCatching { Natives.isSafeMode }.getOrDefault(false),
            isLateLoadMode = runCatching { Natives.isLateLoadMode }.getOrDefault(false),
            stealthState = stealthState,
            systemInfo = SystemInfo(
                kernelVersion = runCatching { Os.uname().release }.getOrDefault("未知"),
                managerVersion = "${managerVersion.versionName} (${managerVersion.versionCode}-${managerUapiRaw})",
                deviceModel = resolveDeviceName(),
                fingerprint = Build.FINGERPRINT,
                selinuxStatus = if (probeRoot) {
                    runCatching { getSELinuxStatusRaw() }.getOrDefault("Unknown")
                } else {
                    "Unknown"
                },
                seccompStatus = runCatching {
                    Os.prctl(21 /* PR_GET_SECCOMP */, 0, 0, 0, 0)
                }.getOrDefault(-1),
            ),
        )

        // 🔒 隐身:把整个状态归一化成「内核未就绪」那一支。
        return if (stealthOn) state.asStealthKernelNotReady() else state
    }

    private companion object {
        const val TAG = "HomeViewModel"
    }
}
