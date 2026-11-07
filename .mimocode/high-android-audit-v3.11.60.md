# 高版本 Android 支持性审计（v3.11.60，targetSdk 34 / compileSdk 36 / minSdk 26）

> 问题：这台机器没有高版本真机，只能靠「读代码 + 对照系统限制」判断。
> 结论先行：**targetSdk 34 下，Android 11–16 理论上都能跑**；本次修掉 4 个会在高版本上
> 静默失效的点；升 targetSdk 35 之前还有 4 项必做（见 §3）。

---

## 1. 本次修掉的高版本隐患（都有明确触发条件）

| # | 问题 | 会在哪个版本炸 | 修法 |
|---|---|---|---|
| H1 | `MonitorService.startForegroundSafely()` 在没有投影时走 **2 参** `startForeground()`；Android 14 会把清单里声明的**全部**类型（`mediaProjection|dataSync`）一次性申报，而此刻还没有投影授权 ⇒ 类型申报不成立，系统拒绝，`runCatching` 把它变成「前台化静默失败 → stopSelf」 | 14+ | API 29+ **一律显式传类型**：无投影只报 `DATA_SYNC`，有投影才报 `MEDIA_PROJECTION|DATA_SYNC`；失败返回 false，`startMonitoring` 直接中止 |
| H2 | 前台化顺序原本是「先 dataSync → 再 `getMediaProjection` → 再升级类型」。Android 10+ 要求**取投影之前**就已经有一个 `mediaProjection` 类型的前台服务 | 10+（14 更严） | 顺序改为：有授权数据 ⇒ 先以 `mediaProjection|dataSync` 前台化 ⇒ 再 `getMediaProjection` ⇒ `registerCallback` ⇒ 建虚拟显示 |
| H3 | `AccessibilityCapturer` 把「被限流」和「机型不支持」当成同一件事：Android 14 起 `takeScreenshot` 有 ~333ms 限流，命中后这一轮直接判「屏幕内容不可用」，连续 3 次还会误报「该机型不支持」 | 14+ | `captureScreenshot` 回调带上 `errorCode`；错误码 3（限流）**等 400ms 重试一次**，且不计入「连续失败」 |
| H4 | `ScreenCapturer.ensureDisplay()` 先赋值 `imageReader` 再 `createVirtualDisplay`：建失败时留下「reader 有、display 没有」的半成品，下一轮再建一个（泄漏）。Android 14 起一个 MediaProjection **只能成功 createVirtualDisplay 一次** | 14+ | 建失败即关闭 reader 并清空字段 |
| H5 | `LockGuardService.start()` 从后台被系统拒绝（`ForegroundServiceStartNotAllowedException`，12+ 常见于未给悬浮窗权限）时只写日志 ⇒ 用户以为守护还开着 | 12+ | 失败时发一条「守护未能自动启动」通知（固定 id，不会刷屏） |
| H6 | Android 14+ 新装默认关闭「闹钟和提醒」（精确闹钟）⇒ 2 秒/30 秒自愈闹钟退化成非精确（Doze 下最长 ~15 分钟），而非精确闹钟**不享受后台启动前台服务的豁免** | 14+ | 权限页新增「闹钟和提醒」一项（仅 Android 12+ 显示）+ 一键跳系统设置 |

## 2. 已确认没问题的（逐条查过，不必再动）

- **前台服务类型与权限**：清单 `mediaProjection|dataSync` + `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`、`FOREGROUND_SERVICE_DATA_SYNC` 都齐；`LockGuardService` 只声明 `dataSync`，2 参调用即等价于该类型 ✓
- **MediaProjection**：每次授权新建会话，旧会话先 `unregisterCallback`+`stop()`；`onStop()` 回调已接（系统回收授权 ⇒ 停检测 + 提示恢复）；`onDestroy`/`stopMonitoring` 释放；`createVirtualDisplay` 前已注册回调（14 要求）✓
- **Android 14 的「单个应用/整屏」选择器**：`MainActivity.screenCaptureIntent()` 在 API 34+ 用 `MediaProjectionConfig.createConfigForDefaultDisplay()` ✓
- **无障碍截图**：API 30 门禁 + `canTakeScreenshot="true"` + `hardwareBuffer.close()` ✓（`takeScreenshot` 是 API 30 才有，低版本一律回落录屏）
- **通知**：渠道在 `FocusGuardApp` 统一创建；API 33 运行时权限有申请入口；发不出去时不再假装成功（v3.11.58）；API 34 的 full-screen intent 被限制时会自动降级为普通横幅/直接弹 Activity ✓
- **PendingIntent**：12/12 都带 `FLAG_IMMUTABLE`；**没有动态 `registerReceiver`**（不踩 API 34 的 exported 标志）✓
- **精确闹钟**：`LockGuardAlarm`/`MemoReminder` 都先 `canScheduleExactAlarms()` 再分三级降级 ✓
- **包可见性**：`QUERY_ALL_PACKAGES` 已声明（否则 API 30+ 查别的应用名一律失败）✓
- **`android:exported`**：清单里每个组件都显式写了（API 31+ 硬要求）✓
- **后台启动界面**：一律「悬浮窗优先 + 无 SAW 才 Activity」，并且在 C8 里加了开机恢复的 1.5 秒结果校验 ✓
- **系统设置写入**：全仓库没有 `Settings.Secure.put` / `Settings.Global.put`（不会触发"应用偷偷改设置"类风控）✓
- **使用情况权限**：失权后 `queryEvents` 返回空表而不抛异常 ⇒ 已加 30 秒复查 + 通知 + 首页降级提示 ✓

## 3. 升 targetSdk 35/36 之前必做（现在不影响运行）

1. **`dataSync` → `specialUse`**：Android 15 起 `dataSync` 前台服务 24h 内累计 6h 会被 `Service.onTimeout()` 打断（没实现就崩），必须换 `specialUse` + `<property PROPERTY_SPECIAL_USE_FGS_SUBTYPE>` + 实现 `onTimeout()`。
2. **Android 15 禁止从 `BOOT_COMPLETED` 启动 `dataSync` 前台服务** —— 现在 `BootReceiver` 正是这么干的（`LockGuardService.start`），升到 35 后**重启即抛异常、守护起不来**。和第 1 条一起改。
3. **edge-to-edge 强制**：`LockScreenActivity` / `AppBlockActivity` / `AlertActivity` 三个全屏页要 `WindowCompat.enableEdgeToEdge` + `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)`，否则底部按钮被导航条盖住（用户会报"卡死"）。
4. **预测性返回**：`onBackPressed()` 换 `OnBackPressedCompat` 回调；`resizeableActivity="false"` 在 Android 16 大屏上会被忽略，需要复核锁机页在分屏/大屏下的表现。

## 4. 只能真机验证的三件事（交用户）

1. Android 11+ 上把「截屏识别」打开后，**通知栏微信正文仍可见**（说明真的没建投影）。
2. 连点两次「开始守护」只出现一个录屏指示（说明旧会话被回收）。
3. 关掉通知权限后，首页守护状态行出现「通知权限已关闭」；再打开会自动消失。
4. （新）权限页在 Android 12+ 上会多出「闹钟和提醒」一项，点它能跳到系统开关。
