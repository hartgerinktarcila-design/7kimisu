package com.sevenk.core.ui.util

/**
 * 「首次运行播种内置默认壁纸」的**纯决策逻辑**(v0.13.161)。
 *
 * 抽出来的原因:播种是**破坏性**操作(会写用户壁纸),而且必须做到
 * 「老用户一个像素都不改」。这段判断不依赖 Android(不需要 Context / SharedPreferences),
 * 所以可以用普通 JVM 直接跑断言,不必在真机上清数据来验证。
 *
 * 三种结果:
 * · [Decision.SKIP]      —— 已经播种过 → 什么都不做(老用户升级后就是这个分支);
 * · [Decision.MARK_ONLY] —— 从没播种过,但**用户自己已经设过壁纸**(竖屏或横屏任一份)
 *                            → 只补 `wallpaper_seeded` 标记,两份壁纸文件一个字节都不动;
 * · [Decision.SEED_BOTH] —— 全新的机子(两份都没有)→ 竖屏、横屏各写各自的内置默认图。
 */
internal object WallpaperSeedPolicy {

    enum class Decision { SKIP, MARK_ONLY, SEED_BOTH }

    /** 设置里"没有壁纸"的表示:`none`(或空串,防御性兼容) */
    private fun noneKind(kind: String): Boolean = kind.isEmpty() || kind == "none"

    fun decide(seeded: Boolean, portraitKind: String, landscapeKind: String): Decision {
        if (seeded) return Decision.SKIP
        val userHasOwn = !noneKind(portraitKind) || !noneKind(landscapeKind)
        return if (userHasOwn) Decision.MARK_ONLY else Decision.SEED_BOTH
    }
}
