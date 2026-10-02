package com.focusguard.app.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 个性化外观设置（背景、光晕、卡片、圆角、字体、动画）。
 *
 * 全部存在独立的 prefs 文件里，并通过 [AppearanceState] 以 Compose 状态暴露：
 * 设置页一改，所有页面（含锁机页）立即重绘。旧实现每个组件各自 `remember`
 * 一次 Settings 读取，开关改了也不会生效——「流光背景开启不起作用」的根因。
 */
data class Appearance(
    /** 背景类型：纯色 / 渐变 / 网格光斑 / 自定义图片。 */
    val background: Int = BG_GRADIENT,
    /** 自定义背景图在应用私有目录中的文件名（[BG_IMAGE] 时使用）。 */
    val imageFile: String = "",
    /** 背景图模糊半径（dp，仅 Android 12+ 生效）。 */
    val imageBlur: Int = 12,
    /** 背景图上方的遮罩不透明度（0–90，保证文字可读）。 */
    val imageDim: Int = 45,
    /** 流光光晕开关。 */
    val glow: Boolean = true,
    /** 光晕强度（10–100）。 */
    val glowIntensity: Int = 60,
    /** 光晕缓慢漂移动画。 */
    val glowMotion: Boolean = true,
    /** 卡片风格：玻璃 / 实色 / 描边。 */
    val cardStyle: Int = CARD_GLASS,
    /** 卡片不透明度（40–100，玻璃风格下生效）。 */
    val cardOpacity: Int = 78,
    /** 圆角档位：小 / 中 / 大。 */
    val corner: Int = CORNER_MEDIUM,
    /** 全局字号缩放（85–125 %）。 */
    val fontScale: Int = 100,
    /** 锁机页倒计时字体：衬线 / 无衬线 / 等宽。 */
    val lockFont: Int = FONT_SERIF,
    /** 锁机页显示箴言。 */
    val showMotto: Boolean = true,
    /** 锁机页显示顶部时钟日期。 */
    val showClock: Boolean = true,
    /** 界面动画（入场、按压回弹、数字滚动）。 */
    val motion: Boolean = true
) {
    companion object {
        const val BG_SOLID = 0
        const val BG_GRADIENT = 1
        const val BG_MESH = 2
        const val BG_IMAGE = 3

        const val CARD_GLASS = 0
        const val CARD_SOLID = 1
        const val CARD_OUTLINE = 2

        const val CORNER_SMALL = 0
        const val CORNER_MEDIUM = 1
        const val CORNER_LARGE = 2

        const val FONT_SERIF = 0
        const val FONT_SANS = 1
        const val FONT_MONO = 2

        private const val PREFS = "focus_guard_appearance"

        fun load(context: Context): Appearance {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val legacy = context.getSharedPreferences("focus_guard_settings", Context.MODE_PRIVATE)
            val d = Appearance()
            return Appearance(
                background = p.getInt("background", d.background),
                imageFile = p.getString("image_file", "") ?: "",
                imageBlur = p.getInt("image_blur", d.imageBlur),
                imageDim = p.getInt("image_dim", d.imageDim),
                // 迁移旧开关：bg_blur_enabled → glow
                glow = p.getBoolean("glow", legacy.getBoolean("bg_blur_enabled", true)),
                glowIntensity = p.getInt("glow_intensity", d.glowIntensity),
                glowMotion = p.getBoolean("glow_motion", d.glowMotion),
                cardStyle = p.getInt("card_style", d.cardStyle),
                cardOpacity = p.getInt("card_opacity", d.cardOpacity),
                corner = p.getInt("corner", d.corner),
                fontScale = p.getInt("font_scale", d.fontScale),
                // 迁移旧开关：lock_font_serif → lockFont
                lockFont = p.getInt(
                    "lock_font",
                    if (legacy.getBoolean("lock_font_serif", true)) FONT_SERIF else FONT_SANS
                ),
                showMotto = p.getBoolean("show_motto", d.showMotto),
                showClock = p.getBoolean("show_clock", d.showClock),
                motion = p.getBoolean("motion", d.motion)
            )
        }

        fun save(context: Context, a: Appearance) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("background", a.background)
                .putString("image_file", a.imageFile)
                .putInt("image_blur", a.imageBlur)
                .putInt("image_dim", a.imageDim)
                .putBoolean("glow", a.glow)
                .putInt("glow_intensity", a.glowIntensity)
                .putBoolean("glow_motion", a.glowMotion)
                .putInt("card_style", a.cardStyle)
                .putInt("card_opacity", a.cardOpacity)
                .putInt("corner", a.corner)
                .putInt("font_scale", a.fontScale)
                .putInt("lock_font", a.lockFont)
                .putBoolean("show_motto", a.showMotto)
                .putBoolean("show_clock", a.showClock)
                .putBoolean("motion", a.motion)
                .apply()
        }
    }

    /** 卡片圆角（dp）。 */
    val cardCorner: Int get() = when (corner) {
        CORNER_SMALL -> 10
        CORNER_LARGE -> 26
        else -> 18
    }
}

/** 全局外观状态：任何页面读取 [value] 都会在设置修改后自动重组。 */
object AppearanceState {
    var value by mutableStateOf(Appearance())
        private set
    private var loaded = false

    fun get(context: Context): Appearance {
        if (!loaded) {
            value = Appearance.load(context.applicationContext)
            loaded = true
        }
        return value
    }

    fun update(context: Context, transform: (Appearance) -> Appearance) {
        get(context)
        val next = transform(value)
        value = next
        Appearance.save(context.applicationContext, next)
    }

    /** 自定义背景图文件。 */
    fun imageFile(context: Context, a: Appearance = get(context)): java.io.File? {
        if (a.imageFile.isBlank()) return null
        val f = java.io.File(context.filesDir, a.imageFile)
        return f.takeIf { it.exists() }
    }

    /**
     * 导入用户选择的图片：等比缩放到屏幕长边 ≤ 2160px 后存入私有目录
     * （不保留原图，也不需要存储权限）。返回新文件名，失败返回 null。
     */
    fun importImage(context: Context, uri: android.net.Uri): String? = runCatching {
        val resolver = context.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return null
        var sample = 1
        while (longEdge / sample > 2160) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        // 删除旧图，文件名带时间戳以让 Compose 重新解码
        context.filesDir.listFiles { f -> f.name.startsWith("custom_bg_") }?.forEach { it.delete() }
        val name = "custom_bg_${System.currentTimeMillis()}.jpg"
        java.io.FileOutputStream(java.io.File(context.filesDir, name)).use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it)
        }
        bmp.recycle()
        name
    }.getOrNull()
}
