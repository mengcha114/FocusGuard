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
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
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

    /** 本次冷启动是否已尝试过自动恢复守护（避免重复弹授权框）。 */
    private var autoReauthAttempted = false

    /** 停止守护前的答题验证状态（防误停/防被监管对象随意停止）。 */
    private var showStopVerify by mutableStateOf(false)
    private var stopVerifyQuestion by mutableStateOf(
        com.focusguard.app.challenge.ChallengeGenerator().generate(2)
    )
    private var stopVerifyAnswer by mutableStateOf("")
    private var stopVerifyError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appSettings = AppSettings(this)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        serviceRunning = com.focusguard.app.service.MonitorService.isRunning ||
            appSettings.serviceRunning

        // ── 锁机状态检查（放在 setContent 之前）──────────
        // 用户通过切出等方式绕过锁机后再打开应用时，必须立刻回到锁机页。
        // 早期实现在这里没有任何处理，导致主界面与锁机页互相拉起 → 闪退。
        try {
            val lockState = com.focusguard.app.data.LockState(this)
            val usageRuleStore = com.focusguard.app.usage.UsageRuleStore(this)
            val hasBlockRule = usageRuleStore.allRules().any { it.hardBlockMinutes != null }

            // 有锁机或封锁规则 → 确保守护服务与看门狗在位
            if (lockState.isLocked || hasBlockRule) {
                com.focusguard.app.service.LockGuardService.ensureRunning(this)
                com.focusguard.app.service.GuardWatchdogWorker.schedule(this)
            }

            // 锁机仍在生效 → 直接跳转锁机页并结束自己，不渲染主界面
            if (lockState.isLocked && lockState.shouldBlockNow) {
                com.focusguard.app.enforce.LockScreenActivity.show(this)
                finish()
                return
            }
        } catch (e: Exception) {
            // 状态检查失败不能阻塞应用启动
            android.util.Log.w("MainActivity", "锁机状态检查失败：${e.message}")
        }

        // ── 守护自动恢复 ──────────────────────────────
        // 曾开启 AI 守护但服务已中断（进程被杀 / MediaProjection 被系统回收）
        // → 打开应用时自动重新请求屏幕录制授权并恢复检测。
        // 这是"解锁后不再自动检测"的闭环修复：用户下次打开应用即恢复。
        try {
            if (appSettings.serviceRunning &&
                !com.focusguard.app.service.MonitorService.isRunning &&
                !autoReauthAttempted
            ) {
                autoReauthAttempted = true
                screenCaptureLauncher.launch(
                    mediaProjectionManager.createScreenCaptureIntent()
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "自动恢复守护失败：${e.message}")
        }

        setContent {
            FocusGuardTheme(themeMode = appSettings.themeMode) {
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
                            // 动态选中：根据当前导航目的地高亮对应标签
                            // （此前 selected 写死为 true/false，点击后阴影不移动）
                            val navBackStackEntry by navController.currentBackStackEntryAsState()
                            val currentRoute = navBackStackEntry?.destination?.route
                            NavigationBar {
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                    label = { Text("主页") },
                                    selected = currentRoute == "home",
                                    onClick = {
                                        navController.navigate("home") {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                                    label = { Text("应用管控") },
                                    selected = currentRoute == "apps",
                                    onClick = {
                                        navController.navigate("apps") {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                    label = { Text("锁机") },
                                    selected = currentRoute == "timer_lock",
                                    onClick = {
                                        navController.navigate("timer_lock") {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                                    label = { Text("AI 对话") },
                                    selected = currentRoute == "logs",
                                    onClick = {
                                        navController.navigate("logs") {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    label = { Text("设置") },
                                    selected = currentRoute == "settings",
                                    onClick = {
                                        navController.navigate("settings") {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
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
                                    onStopGuard = { requestStopGuard() },
                                    onTestDetection = { testDetection() }
                                )
                            }
                            composable("apps") {
                                AppControlScreen()
                            }
                            composable("logs") {
                                com.focusguard.app.ui.screens.AiChatScreen()
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
                        }
                    }
                }
            }

            // ── 停止守护答题验证对话框 ────────────────────
            if (showStopVerify) {
                AlertDialog(
                    onDismissRequest = { showStopVerify = false },
                    title = { Text("停止守护需先答题") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "为防止守护被随意停止，请先回答一道题：",
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                            Text(
                                text = stopVerifyQuestion.question,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                            OutlinedTextField(
                                value = stopVerifyAnswer,
                                onValueChange = { stopVerifyAnswer = it; stopVerifyError = null },
                                label = { Text("你的答案") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            stopVerifyError?.let {
                                Text(it, fontSize = 12.sp, color = Color(0xFFF44336))
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (com.focusguard.app.challenge.ChallengeGenerator()
                                        .isAnswerCorrect(stopVerifyAnswer, stopVerifyQuestion.answer)
                                ) {
                                    showStopVerify = false
                                    stopGuard()
                                } else {
                                    stopVerifyError = "回答错误，请重试"
                                }
                            }
                        ) { Text("验证并停止") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showStopVerify = false }) {
                            Text("取消", color = Color.White.copy(alpha = 0.5f))
                        }
                    },
                    containerColor = Color(0xFF241F27),
                    shape = RoundedCornerShape(20.dp)
                )
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
            "shizuku" -> {
                val enhancer = com.focusguard.app.enhance.ShizukuEnhancer
                if (!enhancer.isAvailable()) {
                    Toast.makeText(
                        this,
                        "未检测到 Shizuku。请先安装 Shizuku 并启动（https://shizuku.rikka.app），再回来授权",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
                enhancer.requestPermission()
                // 授权后立即自愈：使用情况访问 + 电池优化白名单
                android.os.Handler(mainLooper).postDelayed({
                    if (enhancer.isReady()) {
                        enhancer.selfHeal(this)
                        Toast.makeText(this, "Shizuku 已连接，权限自愈完成", Toast.LENGTH_SHORT).show()
                    }
                }, 1500)
            }
            "dhizuku" -> {
                val enhancer = com.focusguard.app.enhance.DhizukuEnhancer
                if (!enhancer.connect(this)) {
                    Toast.makeText(
                        this,
                        "未检测到 Dhizuku（${enhancer.lastError}）：请先安装 Dhizuku 并激活为设备所有者（需 Shizuku 支持），再回来授权",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
                if (enhancer.isPermissionGranted()) {
                    // 已授权：直接启用 Lock Task
                    val ok = enhancer.grantLockTask(this)
                    Toast.makeText(
                        this,
                        if (ok) {
                            "Lock Task 已启用：锁机将进入系统级防退出，任何手势都无法退出"
                        } else {
                            "启用 Lock Task 失败：${enhancer.lastError}"
                        },
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    // 未授权：拉起 Dhizuku 授权界面（连接已建立，这次一定能弹出来）
                    enhancer.requestPermission(this) { granted ->
                        runOnUiThread {
                            if (granted) {
                                val ok = enhancer.grantLockTask(this)
                                Toast.makeText(
                                    this,
                                    if (ok) {
                                        "Dhizuku 已授权，Lock Task 已启用：锁机将无法通过手势退出"
                                    } else {
                                        "Dhizuku 已授权，但加入 Lock Task 白名单失败：${enhancer.lastError}"
                                    },
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(
                                    this,
                                    "Dhizuku 授权被拒绝或取消",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
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

    /**
     * 停止守护入口：先答题验证（防止被监管对象随意停止守护）。
     * 答对后真正停止。
     */
    private fun requestStopGuard() {
        stopVerifyQuestion = com.focusguard.app.challenge.ChallengeGenerator().generate(2)
        stopVerifyAnswer = ""
        stopVerifyError = null
        showStopVerify = true
    }

    private fun stopGuard() {
        appSettings.serviceRunning = false
        serviceRunning = false
        com.focusguard.app.service.MonitorService.stopService(this)
    }

    /**
     * 「立即检测」按钮：
     * - 守护未运行 → 直接开始守护（弹屏幕录制授权）
     * - 守护运行中 → 立即触发一次检测，结果 Toast 提示（详情见 AI 对话页）
     */
    private fun testDetection() {
        if (!com.focusguard.app.service.MonitorService.isRunning) {
            Toast.makeText(this, "守护未运行，正在开启…", Toast.LENGTH_SHORT).show()
            startGuard()
            return
        }
        Toast.makeText(this, "已触发检测，结果稍后出现在 AI 对话页", Toast.LENGTH_SHORT).show()
        com.focusguard.app.service.MonitorService.requestImmediateCheck(this)
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回后同步权限状态
        syncPermissionFlags()
        permissionRefreshTick++

        // 回到前台时再检查一次锁机状态：
        // 覆盖"应用已在后台 → 锁机开始 → 用户切回主界面"的场景
        try {
            val lockState = com.focusguard.app.data.LockState(this)
            if (lockState.isLocked && lockState.shouldBlockNow) {
                com.focusguard.app.service.LockGuardService.ensureRunning(this)
                com.focusguard.app.enforce.LockScreenActivity.show(this)
                finish()
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "onResume 锁机检查失败：${e.message}")
        }
    }
}
