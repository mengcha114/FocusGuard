package com.focusguard.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.focusguard.app.data.Settings as AppSettings
import com.focusguard.app.ui.screens.*
import com.focusguard.app.ui.theme.FocusGuardTheme

class MainActivity : ComponentActivity() {

    private lateinit var appSettings: AppSettings
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            appSettings.screenCaptureGranted = true
            com.focusguard.app.service.MonitorService.startService(
                this, result.resultCode, result.data!!
            )
            serviceRunning = true
        } else {
            Toast.makeText(this, "屏幕录制权限被拒绝", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Notification permission granted or denied
    }

    /**
     * 用于所有「跳系统设置页」类权限。
     * 这类权限没有回调结果，只能在返回后重新查询系统真实状态，
     * 因此统一用一个 launcher 触发 UI 刷新。
     */
    private val settingsPageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        syncPermissionFlags()
        permissionRefreshTick++
    }

    /** 权限界面刷新计数，自增即触发 Compose 重新查询权限状态。 */
    private var permissionRefreshTick by mutableIntStateOf(0)

    /** 守护服务运行状态（供 Compose 实时刷新按钮样式）。 */
    private var serviceRunning by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appSettings = AppSettings(this)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        serviceRunning = com.focusguard.app.service.MonitorService.isRunning ||
            appSettings.serviceRunning

        setContent {
            FocusGuardTheme {
                val navController = rememberNavController()
                // permissionRefreshTick 变化时重新计算 allGranted
                val refreshTick = permissionRefreshTick
                var showPermissionSetup by remember(refreshTick) {
                    mutableStateOf(!isAllPermissionsGranted())
                }

                if (showPermissionSetup) {
                    PermissionSetupScreen(
                        onFinish = { showPermissionSetup = false },
                        onRequestPermission = { permission -> requestPermission(permission) }
                    )
                } else {
                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                    label = { Text("主页") },
                                    selected = true,
                                    onClick = { navController.navigate("home") }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                                    label = { Text("应用管控") },
                                    selected = false,
                                    onClick = { navController.navigate("apps") }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                    label = { Text("锁机") },
                                    selected = false,
                                    onClick = { navController.navigate("timer_lock") }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                                    label = { Text("日志") },
                                    selected = false,
                                    onClick = { navController.navigate("logs") }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    label = { Text("设置") },
                                    selected = false,
                                    onClick = { navController.navigate("settings") }
                                )
                            }
                        }
                    ) { padding ->
                        NavHost(
                            navController = navController,
                            startDestination = "home",
                            modifier = Modifier.padding(padding)
                        ) {
                            composable("home") {
                                HomeScreen(
                                    serviceRunning = serviceRunning,
                                    onStartGuard = { startGuard() },
                                    onStopGuard = { stopGuard() },
                                    onTestDetection = { testDetection() }
                                )
                            }
                            composable("apps") {
                                AppControlScreen(
                                    onOpenUsageLimits = { navController.navigate("usage_limits") }
                                )
                            }
                            composable("logs") {
                                LogScreen()
                            }
                            composable("settings") {
                                SettingsScreen(
                                    onSave = { /* Save handled by screen */ }
                                )
                            }
                            composable("timer_lock") {
                                TimerLockScreen(onBack = { navController.popBackStack() })
                            }
                            composable("unlock_challenge") {
                                UnlockChallengeScreen(onUnlocked = { navController.popBackStack() })
                            }
                            composable("usage_limits") {
                                AppUsageLimitScreen(onBack = { navController.popBackStack() })
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isAllPermissionsGranted(): Boolean {
        // 锁机为软件全屏覆盖，不需要设备管理员；
        // 必需权限只有：应用使用情况（识别前台）+ 无障碍（锁机拦截）
        return com.focusguard.app.util.PermissionChecker.isUsageStatsGranted(this) &&
            com.focusguard.app.util.PermissionChecker.isAccessibilityEnabled(this)
    }

    /** 把系统真实权限同步回 Settings 标记（overlay、screen capture）。 */
    private fun syncPermissionFlags() {
        if (com.focusguard.app.util.PermissionChecker.canDrawOverlays(this)) {
            appSettings.overlayGranted = true
        }
    }

    private fun requestPermission(permission: String) {
        when (permission) {
            "screen_capture" -> {
                screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
            "overlay" -> {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                settingsPageLauncher.launch(intent)
            }
            "usage_stats" -> {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                settingsPageLauncher.launch(intent)
            }
            "notification" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            "accessibility" -> {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                settingsPageLauncher.launch(intent)
            }
            "battery" -> {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                settingsPageLauncher.launch(intent)
            }
        }
    }

    private fun startGuard() {
        if (appSettings.apiKey.isBlank()) {
            Toast.makeText(this, "请先在设置中填写 API 密钥并保存", Toast.LENGTH_LONG).show()
            return
        }
        // MediaProjection 授权每次启动都要重新申请，系统不允许复用
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun stopGuard() {
        appSettings.serviceRunning = false
        serviceRunning = false
        com.focusguard.app.service.MonitorService.stopService(this)
    }

    private fun testDetection() {
        if (!com.focusguard.app.service.MonitorService.isRunning) {
            Toast.makeText(this, "请先开始守护，再执行测试识别", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在测试识别，结果将出现在日志中", Toast.LENGTH_SHORT).show()
        com.focusguard.app.service.MonitorService.requestImmediateCheck(this)
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回后同步权限状态
        syncPermissionFlags()
        permissionRefreshTick++
    }
}
