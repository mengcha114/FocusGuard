package com.focusguard.app.token

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 感知哈希（dHash）计算器。
 *
 * 这是整套 token 节约方案里最关键的一环：
 * 手机屏幕在相邻两次检测之间往往几乎没有变化
 * （同一个页面停留、同一段视频、同一份文档），
 * 此时把图片再发给大模型是纯粹的浪费。
 *
 * dHash 把图片缩到 9x8 灰度，比较每行相邻像素的明暗关系，
 * 得到 64 位指纹。两张图指纹的汉明距离越小，内容越相似。
 * 计算量极小（毫秒级），完全在本地完成，零成本。
 */
object ImageHasher {

    private const val HASH_WIDTH = 9
    private const val HASH_HEIGHT = 8

    /**
     * 计算 64 位 dHash 指纹。
     *
     * @return 64 位指纹，高位在前
     */
    fun dHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, HASH_WIDTH, HASH_HEIGHT, true)
        val gray = IntArray(HASH_WIDTH * HASH_HEIGHT)

        for (y in 0 until HASH_HEIGHT) {
            for (x in 0 until HASH_WIDTH) {
                val pixel = scaled.getPixel(x, y)
                // 加权灰度，贴近人眼感知
                gray[y * HASH_WIDTH + x] =
                    (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            }
        }
        if (scaled != bitmap) scaled.recycle()

        var hash = 0L
        var bitIndex = 0
        for (y in 0 until HASH_HEIGHT) {
            for (x in 0 until HASH_WIDTH - 1) {
                val left = gray[y * HASH_WIDTH + x]
                val right = gray[y * HASH_WIDTH + x + 1]
                if (left > right) {
                    hash = hash or (1L shl bitIndex)
                }
                bitIndex++
            }
        }
        return hash
    }

    /** 两个指纹的汉明距离，范围 0（完全相同）到 64（完全相反）。 */
    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /**
     * 判断两张图是否"实质相同"。
     *
     * 阈值 5 是经验值：小于 5 时通常只是时钟秒数、进度条、
     * 弹幕等无关紧要的局部变化，页面主体内容没变。
     */
    fun isSimilar(a: Long, b: Long, threshold: Int = 5): Boolean =
        hammingDistance(a, b) <= threshold

    /**
     * 判断画面是否几乎全黑（息屏、黑屏播放器加载中）。
     * 这类画面没有识别价值，直接跳过。
     */
    fun isNearlyBlank(bitmap: Bitmap): Boolean {
        val sampleSize = 16
        val scaled = Bitmap.createScaledBitmap(bitmap, sampleSize, sampleSize, true)
        var sum = 0L
        var minV = 255
        var maxV = 0

        for (y in 0 until sampleSize) {
            for (x in 0 until sampleSize) {
                val p = scaled.getPixel(x, y)
                val v = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                sum += v
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
        }
        if (scaled != bitmap) scaled.recycle()

        val avg = sum / (sampleSize * sampleSize)
        val contrast = maxV - minV
        // 平均亮度极低，或者整屏几乎没有明暗差异（纯色屏）
        return avg < 12 || contrast < 10
    }
}
