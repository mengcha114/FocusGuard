package com.focusguard.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 截屏结果。同时返回 JPEG 字节与 Bitmap，
 * 让上层既能发给大模型，也能在本地做感知哈希比对而不必二次解码。
 */
data class CaptureResult(
    val jpegBytes: ByteArray,
    val bitmap: Bitmap
) {
    fun recycle() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

/**
 * 屏幕采集器。
 *
 * token 相关的关键设计：
 * 1. VirtualDisplay 与 ImageReader 复用，不每次重建（省电、更稳定）
 * 2. 输出分辨率压到 640 宽 —— 视觉模型按图块计费，
 *    640x1400 左右的竖屏图约占最低档图块数，再小会影响文字识别
 * 3. JPEG 质量 55，肉眼看得清界面文字，体积比质量 90 小一半以上
 */
class ScreenCapturer(context: Context) : Closeable {

    companion object {
        private const val TAG = "ScreenCapturer"
        /** 目标宽度。视觉模型按图块计费，宽度过大会显著抬升单次成本。 */
        private const val TARGET_WIDTH = 640
        private const val JPEG_QUALITY = 55
        private const val ACQUIRE_TIMEOUT_MS = 1500L
    }

    private val screenDpi: Int
    private val targetWidth: Int
    private val targetHeight: Int

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val handlerThread = HandlerThread("ScreenCapturer").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private var closed = false

    init {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        screenDpi = metrics.densityDpi
        val scale = TARGET_WIDTH.toFloat() / metrics.widthPixels.coerceAtLeast(1)
        targetWidth = TARGET_WIDTH
        // 保持偶数高度，部分设备对奇数尺寸的 VirtualDisplay 支持不佳
        targetHeight = ((metrics.heightPixels * scale).toInt() / 2) * 2
    }

    /**
     * 采集一帧。失败返回 null。
     * 调用方用完必须调 [CaptureResult.recycle]。
     */
    fun capture(projection: MediaProjection): CaptureResult? {
        if (closed) return null

        return try {
            ensureDisplay(projection)
            val reader = imageReader ?: return null

            val image = acquireLatest(reader) ?: run {
                Log.w(TAG, "未取到有效帧")
                return null
            }

            val bitmap = image.use { img ->
                val plane = img.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * targetWidth

                val raw = Bitmap.createBitmap(
                    targetWidth + rowPadding / pixelStride,
                    targetHeight,
                    Bitmap.Config.ARGB_8888
                )
                raw.copyPixelsFromBuffer(buffer)

                if (raw.width != targetWidth) {
                    val cropped = Bitmap.createBitmap(raw, 0, 0, targetWidth, targetHeight)
                    raw.recycle()
                    cropped
                } else {
                    raw
                }
            }

            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            CaptureResult(output.toByteArray(), bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "截屏失败", e)
            null
        }
    }

    /** 兼容旧调用点：只需要字节流时使用。 */
    fun captureScreen(projection: MediaProjection): ByteArray? {
        val result = capture(projection) ?: return null
        return try {
            result.jpegBytes
        } finally {
            result.recycle()
        }
    }

    private fun ensureDisplay(projection: MediaProjection) {
        if (imageReader != null && virtualDisplay != null) return

        val reader = ImageReader.newInstance(
            targetWidth, targetHeight, PixelFormat.RGBA_8888, 2
        )
        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "FocusGuardCapture",
            targetWidth, targetHeight, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
        Log.d(TAG, "VirtualDisplay 就绪 ${targetWidth}x$targetHeight")
    }

    /**
     * 等待一帧可用。VirtualDisplay 刚建立时首帧需要一点时间，
     * 用监听器等待而不是固定 sleep，既更快也更可靠。
     */
    private fun acquireLatest(reader: ImageReader): android.media.Image? {
        reader.acquireLatestImage()?.let { return it }

        val latch = CountDownLatch(1)
        reader.setOnImageAvailableListener({ latch.countDown() }, handler)
        try {
            latch.await(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            reader.setOnImageAvailableListener(null, handler)
        }
        return reader.acquireLatestImage()
    }

    override fun close() {
        closed = true
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        imageReader = null
        handlerThread.quitSafely()
    }
}
