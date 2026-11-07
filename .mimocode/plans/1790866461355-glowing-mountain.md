# 本轮：通知内容被隐藏的修复 + 高版本 Android 适配 8 条 + Gitee 更新源改为 raw 清单（v3.11.48）

用户反馈（2026-10-24）：
1. 「有人反馈开了守护之后，通知栏能看到通知，但是看不到具体内容 …… 本来通知栏可以看到别人微信发的具体内容的，但是现在看不到了」
2. Gitee 同步那条我回问「A/B 选哪个」→ 用户「**你看着办吧**」⇒ 按我的判断走 **A（raw `update.json` 清单）**。

---

## 一、通知内容看不到（核心）

### 根因（已定位，两处证据）
`service/MonitorService.kt` **常挂一个 MediaProjection 截屏会话**（`:183 private var mediaProjection`、`:312 MediaProjectionManager`、循环里 `createVirtualDisplay` + `acquireLatestImage` 反复抓帧）。
系统/国内 ROM 在**存在屏幕捕获会话**时会把通知正文替换成「内容已隐藏」（隐私保护；部分 ROM 是全设备级，部分只影响录制画面）。现象完全吻合：**通知本身还在、正文没了**，且「开守护前正常、开守护后消失」。

已排除的嫌疑（都不成立）：
- 我们自己的通知：`service/AlertNotifier.kt:109 .setVisibility(Notification.VISIBILITY_PUBLIC)` ✓ 是公开的，不是元凶；
- 免打扰/中断过滤：全仓库**没有** `setInterruptionFilter` / `INTERRUPTION_FILTER_*` ✓；
- 锁屏脱敏策略：`enhance/LockPolicies.kt:241-246` 的 `setKeyguardDisabledFeatures` 只含 TRUST_AGENTS/FINGERPRINT/FACE/IRIS，**不含** `KEYGUARD_DISABLE_SECURE_NOTIFICATIONS` ✓；
- 写系统设置：仓库内**没有** `Settings.Secure.put` / `Settings.Global.put` ✓。

### 修法 1（主）：截屏改走**无障碍截图**，不再常挂投影
Android 11(API 30)+ 的 `AccessibilityService.takeScreenshot()` **不需要投影会话**、没有「正在共享屏幕」提示、也不会触发通知脱敏。
- `app/src/main/res/xml/accessibility_service_config.xml`：加 `android:canTakeScreenshot="true"`（保留现有 `canRetrieveWindowContent`）。
- `access/GuardAccessibilityService.kt`：新增
  `fun captureScreenshot(onDone: (android.graphics.Bitmap?) -> Unit)` —— `takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback { onSuccess/onFailure })`，结果回主线程；失败/未连服务 ⇒ `onDone(null)`（**不抛异常**）。
- 提供一个静态入口（例如 `CaptureSource.screenshot(context, onDone)`）给 `MonitorService` 用：**API ≥ 30 且无障碍已连 ⇒ 走无障碍截图**；否则（API < 30 / 无障碍未开）⇒ 走"纯文字模式"（见修法 2），**不再自动建投影**。
- `MonitorService`：`startMonitor()` 里**默认不再申请/不建立 MediaProjection**（保留旧的投影代码路径，仅当用户显式打开「截屏识别（旧方式）」时才用）；抓帧处统一改为调用 `CaptureSource`。

### 修法 2（兜底）：设置里给用户一个「不截屏」的档
`data/Settings.kt` + `ui/components/EnhanceCards.kt`（检测增强卡）新增：
- `var screenshotDetection: Boolean`（prefs `screenshot_detection`，**默认 true**）+ 三档说明文案：
  - 开（默认）：用无障碍截图识别画面内容（不弹共享屏幕、不影响通知内容）；
  - 关：只按**应用名 + 无障碍读到的文字**判断（老机型/特殊 ROM 上保证通知内容正常，代价是纯图片类内容识别不到）。
- 卡片里写明这句副作用原文：「若某些机型仍隐藏通知内容，把此项关闭即可恢复」。

### 修法 3（一致性检查，一起做）
- `enhance/LockTaskEnhancer.kt:60` 的 `setStatusBarDisabled(true)` / `setKeyguardDisabled(true)` **只在 LockTask 期间**置真，退出时 `:71` 已恢复；但要补「**应用启动时清理残留**」：`FocusGuardApp.onCreate` 里若 `lockState` 未处于锁机态，则调 `setStatusBarDisabled(false)` + `setKeyguardDisabled(false)` + `setKeyguardDisabledFeatures(NONE)` 一次（防止上次异常退出残留导致状态栏/锁屏异常）。
- 结论文案给用户：**通知内容看不到 = 常驻截屏导致系统脱敏**，不是我们改了通知设置。

**涉及文件**：`res/xml/accessibility_service_config.xml`、`access/GuardAccessibilityService.kt`（新增截图入口）、`service/MonitorService.kt`（截屏来源切换、不再默认建投影）、`data/Settings.kt`、`ui/components/EnhanceCards.kt`（「截屏识别」开关）、`FocusGuardApp.kt`（启动清理残留 DPM 状态）。

---

## 二、Gitee 更新源：方案 A（raw 清单）

Gitee 的 `/api/v5/.../releases/latest` **不带 token 会 404**（已实测），所以应用里"先试 Gitee API"等于空转。改成读仓库里的 **raw 文件**（公开、无 token）：
- 仓库根新增 `update.json`（每发一版更新一次）：
  ```json
  {"tag":"v3.11.48","notes":"…一行一条…",
   "assets":{"edu":"https://gitee.com/autumiao/FocusGuard/releases/download/v3.11.48/app-edu-debug.apk",
             "general":"https://gitee.com/autumiao/FocusGuard/releases/download/v3.11.48/app-general-debug.apk"}}
  ```
- `update/UpdateChecker.kt`：`probeGitee()` 改成 GET `https://gitee.com/autumiao/FocusGuard/raw/master/update.json` → `JSONObject` 取 `tag`/`notes`/`assets[flavor]`；解析失败/文件缺失 ⇒ `Probe(false)`（继续回退 GitHub atom → GitHub API，逻辑不变）。
- 保留现有版本比较方向（本地 ≥ 远端 ⇒ 已是最新 ✓，`UpdateCheckerTest` 覆盖）。
- 发版流程固定加一步：打完 tag 后更新 `update.json`（tag/notes/两个直链）→ 推 GitHub `feat/grade-challenge` + 推 Gitee `master`。

**涉及文件**：`update/UpdateChecker.kt`、仓库根 `update.json`（新建）、`.github/workflows/build-apk.yml`（发布说明同步加一行「优化了检测更新：优先 Gitee 镜像」）。

---

## 验证（按用户「完成以后一定要多检查几遍再推」）
1. **编译/单测**：push 后等该 sha 的分支 CI **绿**（Kotlin 只能靠 CI 验证）；`UpdateCheckerTest` 4 条版本比较必须仍过。
2. **静态复核**（每处改完立刻 `grep -n`）：
   - `accessibility_service_config.xml` 里 `canTakeScreenshot="true"` 在；
   - `MonitorService` 里默认路径**不再** `createVirtualDisplay`（除非用户开了旧方式）；
   - `captureScreenshot(` 有定义且被 `MonitorService` 调到；
   - `update.json` 里 tag 与最新 tag 一致、两个 URL 与 Gitee 附件名一致。
3. **Gitee 侧**：匿名 `curl https://gitee.com/autumiao/FocusGuard/raw/master/update.json` 能拿到 JSON（这台机器可能被 WAF 403 ⇒ 记下"未验证"，让用户手机上试）。
4. **真机回归（交用户）**：① 开守护后微信通知栏**能看到正文**；② 检测仍工作（日志里有识别记录）；③ 「检测更新」在 GitHub 不通时也能查出新版本。
5. 版本 → `3.11.48` / `vc149`；CI 绿后打 tag 发正式版，并同步 Gitee（Release + 两个 APK + `update.json`）。

---

## 三、高版本 Android 适配（审计已完成，17 条里挑「现在就会踩」的 8 条本轮修）

两份只读审计（源码级，带行号）结论：`PendingIntent` 12/12 都带 `FLAG_IMMUTABLE` ✓、无运行时 `registerReceiver`（不踩 API 34 广播标志）✓、`POST_NOTIFICATIONS` 已声明并申请 ✓、无 `setRepeating` ✓、`MemoReminder` 的精确闹钟降级 ✓、`FLAG_RETRIEVE_INTERACTIVE_WINDOWS` ✓。以下 8 条是 **targetSdk 仍是 34 就已经会出问题** 的，合并进本轮：

| # | 问题 | 位置 | 后果 | 修法 |
|---|---|---|---|---|
| C1 | 重复点「开始守护」⇒ **两个 MediaProjection + 两个 VirtualDisplay 并存**，旧会话 callback 未注销 | `service/MonitorService.kt:233-238` → `:309-314` | Android 14 每次授权都是新会话 ⇒ 录屏指示常驻、耗电、`stopMonitoring` 只停新的（**很可能也是通知脱敏一直不退的原因之一**） | `startMonitoring()` 开头：`mediaProjection?.let { runCatching{ unregisterCallback }; runCatching{ it.stop() } }` 再接收新 token |
| C2 | `startForeground()` 三处未包 try/catch（守护服务已加固 ✓ 监控服务没有 ✗） | `service/MonitorService.kt:246,270,310` | API 34 抛 `SecurityException`/`MissingForegroundServiceTypeException` ⇒ 穿出 `onStartCommand` ⇒ 崩溃 + START_STICKY 重建崩溃循环 | 三处 `runCatching{ startForeground(...) }.onFailure { stopSelf() }` |
| C3 | `onDestroy` 不释放投影；`createVirtualDisplay` 失败时 `ImageReader` 泄漏 | `service/MonitorService.kt:1110-1115`、`detection/ScreenCapturer.kt:133-149` | 录屏会话残留到进程结束（通知持续被系统标记为"正在被录制"） | `onDestroy` 补 `runCatching{ mediaProjection?.stop() }`；`ensureDisplay` 失败时关掉并清空 `imageReader` |
| C4 | 自愈闹钟**不检查** `canScheduleExactAlarms()` | `service/LockGuardAlarm.kt:49-57` | API 31+ 抛 `SecurityException` ⇒ 退化到非精确 ⇒ Doze 下延迟数分钟，"5 秒拉活"失效 | 照 `MemoReminder.kt:66-95` 写法：精确 → `setAndAllowWhileIdle` → `set` 三级降级 |
| C5 | 守护循环**从不检查**使用情况权限；权限被撤后 `queryEvents` 静默返回空 | `service/ForegroundAppDetector.kt:46-69` → `service/LockGuardService.kt:797-800` | 锁机置顶 + 应用封锁同时**静默失效**，用户以为在守 | `guardTick` 每 ~5s 判 `PermissionChecker.isUsageStatsGranted()`；失权 ⇒ 发通知 + 首页显示「防线已降级」，并强制走无障碍判定 |
| C6 | `queryUsageStats(INTERVAL_DAILY, now-15s, now)` 语义错（返回**整天**聚合），再用 `maxByOrNull{lastTimeUsed}` 当"当前前台" | `detection/AppClassifier.kt:133-138` | 误判前台 ⇒ 误封锁/漏封锁（AI 检测与宽限期复检的输入） | 改成 `queryEvents(now-10min, now)` 取最后一个 `ACTIVITY_RESUMED`，或直接 `ForegroundAppDetector.current()` |
| C7 | 娱乐提醒链路不检查通知是否被用户关掉（API 33+ 未授权 ⇒ `notify()` 静默丢弃） | `service/AlertNotifier.kt:51-61,117-118` | 提醒彻底消失、宽限期倒计时用户看不到，直接跳锁机 | `alertEntertainment` 开头 `nm.areNotificationsEnabled()`；false ⇒ 走悬浮窗提醒 + 提示去开通知（照 `MemoReminder.kt:142`） |
| C8 | 开机恢复把"没抛异常"当成功（BAL 拦截时不抛异常 ⇒ `launched=true` ⇒ 跳过"点这里一键恢复"通知） | `boot/BootReceiver.kt:143-166` | 重启后 AI 守护静默关闭且无提示；另 `LockGuardService.kt:773`（无悬浮窗权限分支）的 `startActivity` 在 API 31+ 基本必被吞 | 延迟 1.5s 校验 `MonitorService.isRunning` / Activity 是否在前台，失败即发恢复通知；`LockGuardService` 无 SAW 时改为「常驻通知 + 提示授予悬浮窗」而非静默 `startActivity` |

**记录但本轮不做（升 targetSdk 35/36 之前必须做）**：① 常驻服务 `dataSync` → `specialUse` + `<property PROPERTY_SPECIAL_USE_FGS_SUBTYPE>` + override `Service.onTimeout()`（否则 6 小时窗口后崩/守护每 6 小时死一次）；② Android 15 禁止 BOOT_COMPLETED 拉起 dataSync 前台服务 ⇒ 同上改类型；③ 两个全屏页（`AppBlockActivity` / `AlertActivity`）补 `WindowCompat.enableEdgeToEdge` + `safeDrawing` insets；④ `onBackPressed` → `OnBackPressedCallback`（预测性返回）、大屏 `resizeableActivity` 语义变化。**本轮不动 targetSdk**（仍 34），只把这四条写进计划的"下一轮"。

**本轮涉及文件（C 组）**：`service/MonitorService.kt`、`detection/ScreenCapturer.kt`、`service/LockGuardAlarm.kt`、`service/LockGuardService.kt`、`service/ForegroundAppDetector.kt`、`detection/AppClassifier.kt`、`service/AlertNotifier.kt`、`boot/BootReceiver.kt`。

**C 组验证**：`grep -n` 逐个确认（`canScheduleExactAlarms`、`areNotificationsEnabled`、`isUsageStatsGranted`、`unregisterCallback`、`queryEvents(` 替代、`runCatching { startForeground`）+ CI 绿 + 真机：① 连点两次「开始守护」不出现两个录屏指示；② 关闭通知权限时有可见提示；③ 撤掉「使用情况访问」后首页提示防线降级。
