package com.sevenk.core.ui.util

/**
 * 主题锁定 —— 让它"下载过来打开就是这个样子,谁也改不了"。
 *
 * 锁定的是「主题设置」页里的这几项(见 ColorPaletteScreenMiuix):
 *   · 主题模式 = 跟随系统
 *   · 启用 Monet color = 关
 *   · 模糊 = 开
 *   · 悬浮底栏 = 开
 *   · 液态玻璃 = 关
 *   · 导航栏角标 = 开
 *
 * 实现分三层,任何一层都拦得住:
 *   1. SettingsRepositoryImpl:get 直接返回锁定值,set 丢弃不写(手工改 pref 文件也没用);
 *   2. SettingsViewModel:setter 直接 return,不往 UI state 里塞新值;
 *   3. ColorPaletteScreenMiuix:开关置灰不可点。
 *
 * 想恢复成"用户可以随便改":把 [ENABLED] 改成 false 重编即可,别的都不用动。
 */
object ThemeLock {

    /** 总开关:用户说“别禁用了” → 关掉,主题设置页恢复成用户可以改 */
    const val ENABLED = false

    /** 主题模式:0 = 跟随系统(浅色 1 / 深色 2 / Monet 3~6) */
    const val THEME_MODE = 0

    /** 启用 Monet color */
    const val MIUIX_MONET = false

    /** 模糊(顶栏和底栏的模糊效果) */
    const val ENABLE_BLUR = true

    /** 悬浮底栏 */
    const val FLOATING_BOTTOM_BAR = true

    /** 液态玻璃(悬浮底栏的液态玻璃效果) */
    const val FLOATING_BOTTOM_BAR_BLUR = false

    /** 导航栏角标 */
    const val NAVIGATION_BADGE = true
}
