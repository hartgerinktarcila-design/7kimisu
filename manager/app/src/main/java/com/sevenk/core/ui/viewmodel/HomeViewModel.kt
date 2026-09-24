package com.sevenk.core.ui.viewmodel

import android.os.Build
import android.system.Os
import android.util.Log
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
import com.sevenk.core.data.repository.SettingsRepository
import com.sevenk.core.data.repository.SettingsRepositoryImpl
import com.sevenk.core.getKernelVersion
import com.sevenk.core.ksuApp
import com.sevenk.core.ui.screen.home.HomeUiState
import com.sevenk.core.ui.screen.home.ManagerVersion
import com.sevenk.core.ui.screen.home.SystemInfo
import com.sevenk.core.ui.screen.home.asStealthKernelNotReady
import com.sevenk.core.ui.screen.home.getManagerVersion
import com.sevenk.core.ui.util.checkNewVersion
import com.sevenk.core.ui.util.getSELinuxStatusRaw
import com.sevenk.core.ui.util.module.LatestVersionInfo
import com.sevenk.core.ui.util.resolveDeviceName
import com.sevenk.core.ui.util.rootAvailable

class HomeViewModel(
    private val settingsRepo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    // ⚠️ 2026-09-19 修(启动首帧:ANR + 闪退):
    //    这里以前是 `MutableStateFlow(buildState())`。HomeViewModel 是**在主线程上构造**的,
    //    而 buildState() 会同步去建 root shell(最多 2 个候选、每次探测超时 1.5s)
    //    和 SELinux shell → 首帧白屏甚至 ANR;里面任何一处抛异常还会直接闪退。
    //    现在:首帧只算**不碰 shell** 的那部分(纯 JNI + 本地信息,微秒级),
    //    root / SELinux 探测整体挪到 IO 线程,算完再刷一次 UI。
    //    界面结构、字段、默认值展示方式都不变,只是「有没有 root / SELinux 状态」
    //    这两个字段先显示默认值(false / Unknown),后台算完自动刷新。
    private val _uiState = MutableStateFlow(buildState(probeRoot = false))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // 后台补上 root / SELinux 探测(IO 线程,不再堵主线程)。
        // 算不出来就保持首帧的默认值,绝不让异常冒出去。
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) { buildStateOrNull(probeRoot = true) }
                ?: return@launch
            _uiState.update { state }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val baseState = withContext(Dispatchers.IO) { buildStateOrNull(probeRoot = true) }
                ?: return@launch
            _uiState.update { baseState }
            if (baseState.checkUpdateEnabled) {
                // ⚠️ 检查更新要联网,失败只是"没有新版本信息",不该把协程带崩(协程里未捕获
                //    的异常 = 闪退),所以也包一层;拿不到就保持上一次的值。
                val latestVersionInfo = withContext(Dispatchers.IO) {
                    runCatching { checkNewVersion() }.getOrNull()
                } ?: return@launch
                _uiState.update { it.copy(latestVersionInfo = latestVersionInfo) }
            }
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

    /**
     * @param probeRoot 是否做"需要起 shell"的探测(root 可用性 / SELinux 状态)。
     *                  主线程首帧传 false(绝不阻塞),IO 线程刷新传 true。
     */
    private fun buildState(probeRoot: Boolean): HomeUiState {
        // ⚠️ 2026-09-19 修:下面每一个 runCatching 都是"防闪退" ——
        //    Natives 是 JNI(可能 UnsatisfiedLinkError),读设置走磁盘,Os.uname 也可能抛。
        //    以前任何一个抛了,HomeViewModel 的构造 / 刷新协程就炸;现在各自兜底,
        //    最坏只是某个字段显示默认值,页面照样打开。
        val kernelVersion = runCatching { getKernelVersion() }
            .getOrElse { KernelVersion(-1, -1, -1) }
        val isManager = runCatching { Natives.isManager }.getOrDefault(false)
        // isManager 为 false 有两种原因,不能混着提示(详见 HomeUiState.managerNotRecognized 的注释):
        //   ① 隐身模式   → stealthState() 能读到 1(内核仍然认我们)
        //   ② 内核没认主 → 读不到(返回 -1)→ 只要重启一次设备即可,不要引导用户去刷机
        val stealthState = runCatching { Natives.stealthState() }.getOrDefault(-1)
        val managerNotRecognized = !isManager && stealthState != 1
        // 隐身模式(内核 stealthState == 1):内核其实仍然认我们,只是不上报 MANAGER 标志。
        // 界面要把首页伪装成「内核未就绪」那一支(详见 HomeUiState.asStealthKernelNotReady)。
        val stealthOn = stealthState == 1
        val ksuVersionRaw = runCatching { Natives.version }.getOrDefault(-1)
        val kernelUapiRaw = runCatching { Natives.kernelUAPIVersion }.getOrDefault(0)
        val managerUapiRaw = runCatching { Natives.managerUAPIVersion }.getOrDefault(0)
        val ksuVersion = if (isManager) ksuVersionRaw else null
        val kernelUAPIVersion = if (isManager) kernelUapiRaw else null
        val managerUAPIVersion = managerUapiRaw
        val lkmMode = if (ksuVersion != null && kernelVersion.isGKI()) {
            runCatching { Natives.isLkmMode }.getOrNull()
        } else {
            null
        }
        // 下面这两个都要起 shell(最慢、最可能抛)→ 只在 probeRoot = true(IO 线程)时做
        val isRootAvailable = if (probeRoot) {
            runCatching { rootAvailable() }.getOrDefault(false)
        } else {
            false
        }
        val managerVersion = runCatching { getManagerVersion(ksuApp) }
            .getOrElse { ManagerVersion("未知", 0L) }

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
            // 内核 UAPI 比管理器新(5 -> 6)时不再报"需要更新管理器":
            // UAPI 升级是向后兼容的,旧管理器可以直接用;否则内核先行更新就会误报,
            // 还会顺带把界面降级(见 isFullFeatured)。
            requiresNewManager = false,
            kernelUAPIVersion = kernelUAPIVersion,
            managerUAPIVersion = managerUAPIVersion,
            isRootAvailable = isRootAvailable,
            isSafeMode = runCatching { Natives.isSafeMode }.getOrDefault(false),
            isLateLoadMode = runCatching { Natives.isLateLoadMode }.getOrDefault(false),
            checkUpdateEnabled = runCatching { settingsRepo.checkUpdate }.getOrDefault(false),
            latestVersionInfo = LatestVersionInfo(),
            currentManagerVersionCode = managerVersion.versionCode,
            systemInfo = SystemInfo(
                kernelVersion = runCatching { Os.uname().release }.getOrDefault("未知"),
                managerVersion = "${managerVersion.versionName} (${managerVersion.versionCode}-${managerUAPIVersion})",
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

        // 🔒 隐身:把整个状态归一化成「内核未就绪」那一支(照抄没刷内核时首页的样子)。
        //    界面层一行不用改就会走进那条已存在的分支(见 HomeUiState.asStealthKernelNotReady)。
        //    顺手把"更新检查"也关掉 —— 状态里 checkUpdateEnabled 为 false 时
        //    refresh() 不会再联网查版本,伪装期少一条痕迹。
        return if (stealthOn) state.asStealthKernelNotReady() else state
    }
}
