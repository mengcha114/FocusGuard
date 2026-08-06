package com.focusguard.app

import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
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
        } else {
            Toast.makeText(this, "屏幕录制权限被拒绝", Toast.LENGTH_SHORT).show()
        }
    }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            appSettings.deviceAdminGranted = true
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Notification permission granted or denied
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appSettings = AppSettings(this)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            FocusGuardTheme {
                val navController = rememberNavController()
                var serviceRunning by remember { mutableStateOf(appSettings.serviceRunning) }
                var showPermissionSetup by remember { mutableStateOf(!isAllPermissionsGranted()) }

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
                                    icon = { Icon(Icons.Default.Timer, contentDescription = null) },
                                    label = { Text("番茄钟") },
                                    selected = false,
                                    onClick = { navController.navigate("pomodoro") }
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
                                    onTestDetection = { testDetection() },
                                    onStartLock = { navController.navigate("timer_lock") }
                                )
                            }
                            composable("apps") {
                                AppControlScreen(
                                    onOpenUsageLimits = { navController.navigate("usage_limits") }
                                )
                            }
                            composable("pomodoro") {
                                PomodoroScreen()
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
        return appSettings.screenCaptureGranted &&
               appSettings.overlayGranted &&
               appSettings.deviceAdminGranted
    }

    private fun requestPermission(permission: String) {
        when (permission) {
            "screen_capture" -> {
                screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
            "overlay" -> {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    appSettings.overlayGranted = true
                }
            }
            "device_admin" -> {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(
                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        ComponentName(this@MainActivity, com.focusguard.app.admin.GuardDeviceAdminReceiver::class.java)
                    )
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "专注卫士需要设备管理员权限来锁屏")
                }
                deviceAdminLauncher.launch(intent)
            }
            "notification" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            "accessibility" -> {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
            "battery" -> {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun startGuard() {
        if (!appSettings.screenCaptureGranted) {
            Toast.makeText(this, "请先授予屏幕录制权限", Toast.LENGTH_SHORT).show()
            requestPermission("screen_capture")
            return
        }
        appSettings.serviceRunning = true
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun stopGuard() {
        appSettings.serviceRunning = false
        com.focusguard.app.service.MonitorService.stopService(this)
    }

    private fun testDetection() {
        // Launch a test detection via service
        Toast.makeText(this, "正在测试识别...", Toast.LENGTH_SHORT).show()
    }
}
