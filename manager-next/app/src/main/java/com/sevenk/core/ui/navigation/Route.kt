package com.sevenk.core.ui.navigation

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * miuix-nav 的类型安全路由 key（须 @Serializable 才能随 back stack 保存/恢复）。
 */
@Serializable
sealed interface Route : NavKey, Parcelable {

    /** 主界面（Home/SuperUser/Module/Settings 四页 pager + 底栏） */
    @Parcelize
    @Serializable
    data object Main : Route

    /**
     * boot 修补刷写页。
     * @param bootUri 用户选择的 boot/init_boot 镜像；null = 直接安装（boot-patch -f，需 root）
     */
    @Parcelize
    @Serializable
    data class Flash(
        val bootUri: String? = null,
        val ota: Boolean = false,
        val partition: String? = null,
        val allowShell: Boolean = false,
        val enableAdb: Boolean = false,
        val backup: Boolean = false,
    ) : Route

    /** 模块 zip 安装页（来自文件选择器或 zip VIEW intent） */
    @Parcelize
    @Serializable
    data class FlashModule(val zipUri: String) : Route

    /** 关于页 */
    @Parcelize
    @Serializable
    data object About : Route
}
