package com.focusguard.app.capture

import android.graphics.Bitmap
import com.focusguard.app.access.GuardAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * 无障碍截屏：不建立 MediaProjection 会话。
 *
 * 为什么不用 MediaProjection 做首选：常驻的屏幕捕获会话会让系统/部分 ROM
 * 把通知正文替换成「内容已隐藏」（用户实测「开了守护后看不到微信内容」），
 * 而且每次启动都要用户点「共享屏幕」。无障碍截图两者都没有。
 *
 * 失败一律返回 null（未授权、低版本、被系统限流），调用方回退到投影截屏。
 */
object AccessibilityCapturer {

    /** 与 [ScreenCapturer] 保持同一档输出：宽 640、JPEG 55。 */
    private const val TARGET_WIDTH = 640
    private const val JPEG_QUALITY = 55

    suspend fun capture(): CaptureResult? {
        val bitmap = shot() ?: return null
        return try {
            val scaled = if (bitmap.width > TARGET_WIDTH) {
                val h = (bitmap.height.toFloat() * TARGET_WIDTH / bitmap.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, TARGET_WIDTH, h, true).also {
                    if (it !== bitmap && !bitmap.isRecycled) bitmap.recycle()
                }
            } else {
                bitmap
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            CaptureResult(out.toByteArray(), scaled)
        } catch (t: Throwable) {
            if (!bitmap.isRecycled) bitmap.recycle()
            null
        }
    }

    private suspend fun shot(): Bitmap? = withContext(Dispatchers.Main) {
        val service = GuardAccessibilityService.instance ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            try {
                service.captureScreenshot { bmp -> if (cont.isActive) cont.resume(bmp) }
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
