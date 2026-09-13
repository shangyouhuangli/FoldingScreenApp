package com.example.foldingscreen

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 透明启动 Activity —— 应用唯一入口（无主界面）。
 *
 * 启动流程（显式状态机，全流程异常保护）：
 *  onCreate → 悬浮窗权限未授予：跳转系统悬浮窗设置页等待返回；
 *             已授予：检查通知权限（Android 13+）→ 启动前台服务 → finish()。
 *  首次 onResume（pendingOverlaySettings=true）→ 跳过，避免在设置页弹出前误判；
 *  从设置页返回 → 按实际权限决定「启动服务」或「提示后退出」。
 *
 * 录屏权限（MediaProjection）不在启动时申请：由 OverlayService 在用户首次
 * 点击开启模式时通过 [MediaProjectionRequestActivity] 动态申请（规格要求动态申请）。
 */
class LaunchActivity : Activity() {

    companion object {
        private const val TAG = "LaunchActivity"
        private const val REQ_OVERLAY = 1001
        private const val REQ_NOTIFICATION = 1002
    }

    /** 是否正在等待「悬浮窗权限设置页」返回（用于跳过首次 onResume 的误判） */
    private var pendingOverlaySettings = false

    /** 服务是否已启动（防止重复启动/重复 finish） */
    private var serviceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 无布局：透明窗口，应用没有主界面
        try {
            ensurePermissionsAndStart()
        } catch (e: Exception) {
            Log.e(TAG, "启动流程异常: ${e.message}")
            Toast.makeText(this, "启动异常：${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            if (pendingOverlaySettings) {
                // 首次 onResume：设置页即将弹出，本次不处理，等待真正从设置页返回
                pendingOverlaySettings = false
                return
            }
            if (Settings.canDrawOverlays(this)) {
                startOverlayService()
            } else {
                Toast.makeText(this, "需要悬浮窗权限才能使用本应用", Toast.LENGTH_LONG).show()
                finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onResume 异常: ${e.message}")
            finish()
        }
    }

    /** 权限检查与启动流程（全部异常受保护） */
    private fun ensurePermissionsAndStart() {
        // 1. 悬浮窗权限（核心权限，缺失则引导去系统设置）
        if (!Settings.canDrawOverlays(this)) {
            pendingOverlaySettings = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try {
                startActivityForResult(intent, REQ_OVERLAY)
            } catch (e: Exception) {
                // 极少数机型/ROM 无此设置页
                Log.e(TAG, "无法打开悬浮窗设置页: ${e.message}")
                Toast.makeText(
                    this,
                    "请在系统设置 → 应用 → 折叠屏模拟 → 打开「显示在其他应用上层」",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
            return
        }

        // 2. 通知权限（Android 13+ 运行时权限；拒绝不阻塞核心功能，仅通知不显示）
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATION
            )
            return
        }

        startOverlayService()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            // 从悬浮窗设置页返回（resultCode 通常为 RESULT_CANCELED，以实际权限为准）
            pendingOverlaySettings = false
            if (Settings.canDrawOverlays(this)) {
                startOverlayService()
            }
            // 未授权：交给紧随其后的 onResume 统一提示并退出
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION) {
            // 无论是否授予通知权限，都继续启动服务
            startOverlayService()
        }
    }

    /** 启动前台服务后立即销毁自身（幂等，防止重复调用） */
    private fun startOverlayService() {
        if (serviceStarted) return
        serviceStarted = true
        try {
            val intent = Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_START_SERVICE)
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动服务失败: ${e.message}")
            Toast.makeText(this, "启动服务失败：${e.message}", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
