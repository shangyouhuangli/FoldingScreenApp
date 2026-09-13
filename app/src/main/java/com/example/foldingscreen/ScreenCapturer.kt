package com.example.foldingscreen

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.nio.ByteBuffer

/**
 * 屏幕采集器：MediaProjection + VirtualDisplay + ImageReader。
 *
 * 流程：
 *  VirtualDisplay（AUTO_MIRROR 镜像主屏全画面，含桌面与所有前台应用）
 *      → ImageReader（RGBA_8888，3 缓冲）
 *      → 采集线程把最新帧逐行拷贝到复用缓冲
 *      → 通过 [onFrame] 回调投递给渲染线程上传 GL 纹理。
 *
 * 设计要点：
 *  - 使用 acquireLatestImage() 丢弃积压帧，保证「始终是最新画面」，降低端到端延迟；
 *  - 3 槽轮换缓冲避免采集线程与渲染线程互相阻塞；
 *  - 逐行拷贝处理 rowStride 对齐（部分设备 rowStride > width*4）。
 *
 * 用途说明（重要）：本应用在【渲染悬浮层出现之前】启动采集并取得第一帧纯净画面
 * （此时画面中不含本应用任何悬浮窗），随后停止采集，将该帧作为渲染源纹理。
 * 这是因为 MediaProjection 会把 TYPE_APPLICATION_OVERLAY 悬浮窗一并采入画面
 * （FLAG_SECURE 又会导致被覆盖区域整块变黑），全屏不透明渲染层出现后无法再获得
 * 纯净实时帧 —— 这是平台行为，故画面源采用「开启瞬间的实时快照」。
 */
class ScreenCapturer(
    private val mediaProjection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    /** (像素缓冲, 宽, 高, 行字节数) —— 渲染线程消费 */
    private val onFrame: (ByteBuffer, Int, Int, Int) -> Unit
) {

    companion object {
        private const val TAG = "ScreenCapturer"
    }

    /** 采集专用线程：ImageReader 回调在此执行，避免阻塞主线程 */
    private val captureThread = HandlerThread("screen-capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)

    private val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

    /** 3 槽轮换缓冲（每槽 width*height*4 字节，紧致打包） */
    private val buffers = Array(3) { ByteBuffer.allocateDirect(width * height * 4) }
    private var writeIndex = 0

    private var virtualDisplay: VirtualDisplay? = null

    /** 启动虚拟显示并开始采集 */
    fun start() {
        imageReader.setOnImageAvailableListener({ reader ->
            // 始终取最新一帧，跳过积压帧（低延迟关键）
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val src = plane.buffer
                val rowStride = plane.rowStride
                val dst = buffers[writeIndex % buffers.size]
                writeIndex++
                dst.clear()

                if (rowStride == width * 4) {
                    // 无对齐：整块拷贝
                    src.position(0)
                    dst.put(src)
                } else {
                    // 逐行拷贝，去掉行尾对齐填充
                    val row = ByteArray(width * 4)
                    for (y in 0 until height) {
                        src.position(y * rowStride)
                        src.get(row)
                        dst.put(row)
                    }
                }
                dst.flip()
                onFrame(dst, width, height, width * 4)
            } catch (e: Exception) {
                Log.w(TAG, "采集帧处理失败: ${e.message}")
            } finally {
                image.close() // 必须释放，否则 ImageReader 缓冲耗尽会停止回调
            }
        }, captureHandler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "foldingscreen-capture",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null, null
        )
        Log.i(TAG, "虚拟显示已创建 ${width}x${height}@${dpi}dpi")
    }

    /** 停止采集并释放全部资源 */
    fun stop() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader.close()
        } catch (_: Exception) {
        }
        captureThread.quitSafely()
        Log.i(TAG, "屏幕采集已停止")
    }
}
