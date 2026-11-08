package com.focusguard.app.util

import android.graphics.Bitmap

/**
 * 纯 Kotlin 的盒式模糊（三次迭代近似高斯）。
 *
 * 背景图模糊不能用 inSampleSize 降到很低再放大——那会变成马赛克方块；
 * 也不能用全屏 RenderEffect（部分 GPU 会原生崩溃）。这里在缩略图上做
 * 多次盒式模糊，再交给 Compose 平滑放大，得到连续的朦胧感。
 *
 * 调用方需保证 [blur] 在 IO 线程执行。
 */
object FastBlur {

    fun blur(src: Bitmap, radius: Int): Bitmap {
        if (radius <= 0 || src.width <= 0 || src.height <= 0) return src
        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            boxBlurHorizontal(pix, tmp, w, h, radius)
            boxBlurVertical(tmp, pix, w, h, radius)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pix, 0, w, 0, 0, w, h)
        if (out !== src) src.recycle()
        return out
    }

    private fun boxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                sumA += p ushr 24
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = ((sumA / div) shl 24) or
                    ((sumR / div) shl 16) or
                    ((sumG / div) shl 8) or
                    (sumB / div)
                val add = src[row + (x + r + 1).coerceAtMost(w - 1)]
                val sub = src[row + (x - r).coerceAtLeast(0)]
                sumA += (add ushr 24) - (sub ushr 24)
                sumR += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sumG += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sumB += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }

    private fun boxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (x in 0 until w) {
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                sumA += p ushr 24
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((sumA / div) shl 24) or
                    ((sumR / div) shl 16) or
                    ((sumG / div) shl 8) or
                    (sumB / div)
                val add = src[(y + r + 1).coerceAtMost(h - 1) * w + x]
                val sub = src[(y - r).coerceAtLeast(0) * w + x]
                sumA += (add ushr 24) - (sub ushr 24)
                sumR += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sumG += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sumB += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }
}
