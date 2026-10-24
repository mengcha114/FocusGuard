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

    private val failureCount = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var warned = false

    private fun resetFailure() {
        failureCount.set(0)
    }

    /** 有的机型/ROM 不支持无障碍截图：连续失败就提示用户改用共享屏幕，而不是静默抓不到画面。 */
    private fun noteFailure(context: android.content.Context) {
        if (failureCount.incrementAndGet() < 3 || warned) return
        warned = true
        runCatching {
            com.focusguard.app.data.LogStore(context).addLog(
                com.focusguard.app.data.DetectionLog(
                    timestamp = System.currentTimeMillis(),
                    classification = "NEUTRAL",
                    confidence = 0f,
                    reason = "无障碍截图连续失败（该机型可能不支持）：请在设置里关闭「截屏识别」改用共享屏幕",
                    action = "NONE",
                    source = "ERROR",
                    appLabel = "无障碍截图"
                )
            )
        }
    }

    private suspend fun shot(): Bitmap? = withContext(Dispatchers.Main) {
        val service = GuardAccessibilityService.instance ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
            fun finish(bmp: Bitmap?) {
                if (resumed.compareAndSet(false, true) && cont.isActive) cont.resume(bmp)
            }
            try {
                service.captureScreenshot { bmp ->
                    if (bmp == null) noteFailure(service) else resetFailure()
                    finish(bmp)
                }
            } catch (t: Throwable) {
                noteFailure(service)
                finish(null)
            }
        }
    }
}
