package com.example.foldingscreen

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * 透明授权 Activity：向系统发起屏幕录制授权（MediaProjection）。
 *
 * 触发时机：用户首次点击悬浮按钮开启模式时，由 [OverlayService] 以
 * FLAG_ACTIVITY_NEW_TASK 启动本 Activity，系统弹出「开始录制或投屏」对话框。
 *
 * 结果回传：授权结果通过静态回调 [callback] 交还给服务（单实例应用，静态引用安全），
 * 回调执行后立即置空，避免泄漏。系统撤销授权后再次开启需重新授权（符合 Android 14
 * 单次授权 token 约束）。
 */
class MediaProjectionRequestActivity : Activity() {

    companion object {
        private const val REQ_MEDIA_PROJECTION = 2001

        /** 服务侧注册的授权回调：回调 (resultCode, data)，用完即置空 */
        @Volatile
        var callback: ((resultCode: Int, data: Intent?) -> Unit)? = null
    }

    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        // 弹出系统录屏授权对话框（异常受保护：极少数 ROM 可能拒绝发起）
        try {
            startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                REQ_MEDIA_PROJECTION
            )
        } catch (e: Exception) {
            callback?.invoke(Activity.RESULT_CANCELED, null)
            callback = null
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA_PROJECTION) {
            callback?.invoke(resultCode, data)
            callback = null
        }
        finish()
    }
}
