package com.sevenk.core.data.repository

interface SettingsRepository {
    var uiMode: String
    var checkUpdate: Boolean
    var checkModuleUpdate: Boolean
    var themeMode: Int
    var miuixMonet: Boolean
    var keyColor: Int
    var colorStyle: String
    var colorSpec: String
    var enablePredictiveBack: Boolean
    var enableBlur: Boolean
    var enableFloatingBottomBar: Boolean
    var enableFloatingBottomBarBlur: Boolean
    var enableNavigationBadge: Boolean
    var navigationRailExpanded: Boolean
    var pageScale: Float
    var enableWebDebugging: Boolean
    var moduleSortEnabledFirst: Boolean
    var moduleSortActionFirst: Boolean
    var moduleRepoSortOrder: Int
    var superuserShowSystemApps: Boolean
    var superuserShowOnlyPrimaryUserApps: Boolean
    var superuserSortOption: Int
    var suLogFilters: Set<String>?
    var autoJailbreak: Boolean
    var useSoftReboot: Boolean
    // 个性化:壁纸
    /**
     * 竖屏(基础)壁纸。**这是老的那一份**,pref key 从旧版本沿用,语义:
     * 竖屏用它;横屏没单独设 [wallpaperLandPath] 时也用它(老用户零变化)。
     */
    var wallpaperPath: String
    var wallpaperKind: String

    /**
     * 横屏专用壁纸(与 [wallpaperPath] 对称的第二套)。
     * 空/`none` = 没单独设 → 横屏自动沿用竖屏那份(升级前行为不变)。
     */
    var wallpaperLandPath: String
    var wallpaperLandKind: String

    /** 是否已经播种过内置默认壁纸 */
    var wallpaperSeeded: Boolean

    var wallpaperDim: Float
    var wallpaperBlur: Float
    var uiTranslucent: Boolean
    var uiTranslucentAlpha: Float
    var wallpaperSeed: Boolean
    var wallpaperSeedColor: Int

    /** 自定义状态卡图片的取景位置:0 居中 / 1 显示上部 / 2 显示下部(平板壁纸比卡片高,得选看哪一条) */
    var statusDecorationAnchor: Int

    /** 自定义状态卡图片的压暗强度(0 = 不压暗,靠文字描边保可读) */
    var statusDecorationDim: Float

    /** 自裁取景:水平偏移(-1 左 / 0 中 / 1 右) */
    var statusDecorationBiasX: Float

    /** 自裁取景:垂直偏移(-1 上 / 0 中 / 1 下) */
    var statusDecorationBiasY: Float

    /** 自裁取景:放大倍数(1 = 铺满卡片) */
    var statusDecorationZoom: Float

    /** 自定义状态卡图片时:只留图,隐藏标题/版本/LKM 文字 */
    var statusDecorationHideText: Boolean

    /** 自定义导航图标:裁成圆形(头像最好看) */
    var navIconCircle: Boolean

    /** 自定义导航图标:按主题色染色(适合单色剪影图;彩色美少女图建议关闭) */
    var navIconTint: Boolean

    /** 下雪特效开关 */
    var snowEnabled: Boolean

    /** 雪花大小倍率(1 = 标准) */
    var snowSize: Float

    /** 雪花下落速度倍率(1 = 标准) */
    var snowSpeed: Float

    /** 巨魔雨开关 */
    var trollRainEnabled: Boolean

    /** 巨魔图标大小倍率(1 = 标准) */
    var trollRainSize: Float

    /** 巨魔图标下落速度倍率(1 = 标准) */
    var trollRainSpeed: Float

    /** 重力感应:倾斜手机时雪花/巨魔朝低处流 */
    var gravityEnabled: Boolean

    /** 重力方向校准:0=正常 1=左右翻转 2=上下翻转 3=反转180° */
    var gravityMode: Int

    /** 隐身模式的拨号密令数字(如 70707 对应 *#*#70707#*#*) */
    var stealthCode: String

    /**
     * 网页管理器的开关（**只是界面上的开关状态**）。
     *
     * 真正的服务跑在 **ksud（root 守护进程）** 里，配置和口令存在
     * `/data/adb/sevenk/webadmin.conf`，由 `ksud webadmin on|off` 控制。
     * 这样划掉 App、一键清理、重启手机都不影响网页可用。
     */
    var webAdminEnabled: Boolean

    /** 界面扁平化:顶栏/底栏/首页卡片都只留文字和图标,不画底色(模块页不受影响) */
    var flatHome: Boolean

    val intentToken: String

    suspend fun getSuCompatStatus(): String
    suspend fun getSuCompatPersistValue(): Long?
    fun isSuEnabled(): Boolean
    fun setSuEnabled(enabled: Boolean): Boolean
    fun setSuCompatModePref(mode: Int)
    fun getSuCompatModePref(): Int

    suspend fun getKernelUmountStatus(): String
    fun isKernelUmountEnabled(): Boolean
    fun setKernelUmountEnabled(enabled: Boolean): Boolean

    suspend fun getSelinuxHideStatus(): String
    fun isSelinuxHideEnabled(): Boolean
    fun setSelinuxHideEnabled(enabled: Boolean): Int

    suspend fun getSulogStatus(): String
    suspend fun getSulogPersistValue(): Long?
    fun setSulogEnabled(enabled: Boolean): Boolean

    suspend fun getAdbRootStatus(): String
    suspend fun getAdbRootPersistValue(): Long?
    fun setAdbRootEnabled(enabled: Boolean): Boolean

    fun isDefaultUmountModules(): Boolean
    fun setDefaultUmountModules(enabled: Boolean): Boolean

    fun isLkmMode(): Boolean

    fun execKsudFeatureSave()
}
