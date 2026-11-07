# FocusGuard 开发经验与踩坑手册

> 来源：v3.11.x 这一长串热修（v3.11.0 → v3.11.57）的真实事故。
> 每条都写「现象 → 根因 → 正确做法」，可以直接当 checklist 用。

---

## 目录

1. [Kotlin 编译坑（Kotlin 只能靠 CI 验证）](#1-kotlin-编译坑kotlin-只能靠-ci-验证)
2. [Compose 坑](#2-compose-坑)
3. [Android 平台坑（分版本）](#3-android-平台坑分版本)
4. [权限 / 特权（Dhizuku / Shizuku / 设备管理员 / 无障碍）](#4-权限--特权dhizuku--shizuku--设备管理员--无障碍)
5. [锁机与悬浮窗架构坑](#5-锁机与悬浮窗架构坑)
6. [数据工程坑（题库 / JSON / 批量处理）](#6-数据工程坑题库--json--批量处理)
7. [构建、CI 与发布坑](#7-构建ci-与发布坑)
8. [改代码的工程纪律（最省时间的一节）](#8-改代码的工程纪律最省时间的一节)
9. [排障方法论：怎么从一句话反馈查到根因](#9-排障方法论怎么从一句话反馈查到根因)
10. [和这位用户协作的经验](#10-和这位用户协作的经验)

---

## 1. Kotlin 编译坑（Kotlin 只能靠 CI 验证）

本地没有 JVM/Gradle（只有 Python），所以**任何 Kotlin 语法问题都只能在 GitHub Actions 里暴露**。踩过的：

### 1.1 扩展函数接收者类型不匹配

```kotlin
// ✗ 报 Unresolved reference. None of the following candidates is applicable
//    because of receiver type mismatch
answer.filter { it in 'A'..'H' }.distinct().size >= 2
```
`String.filter { }` 返回 **String（CharSequence）**，而 `distinct()` 定义在 `Iterable` 上 ⇒ 解析不到。
```kotlin
// ✓
answer.filter { it in 'A'..'H' }.toSet().size >= 2
```

> 教训：`String` 上调用任何集合类扩展前，先想“它返回的是 String 还是 List”。

### 1.2 扩展函数不能“全限定调用”

```kotlin
// ✗ 非法：Unresolved reference: androidx
modifier.androidx.compose.foundation.verticalScroll(state)
// ✗ 同样非法（编译期不认这种写法）
.heightIn(...).androidx.compose.foundation.verticalScroll(...)
```
扩展函数**必须 import**：`import androidx.compose.foundation.verticalScroll`。
→ 我在这上面连续踩了两次（`clickable`、`verticalScroll`）。

### 1.3 缺 import 的隐蔽报错

`scope.launch { }` 报 **`Unresolved reference: launch`**，因为 `launch` 是 `CoroutineScope` 的**扩展函数**，需要
`import kotlinx.coroutines.launch`。同理 `suspendCancellableCoroutine`、`cont.resume` 都需要单独 import。

### 1.4 类名与属性名冲突

`LockGuardService` 里有属性 `settings`；在服务里写 `val settings = Settings(this)` ⇒ 报
**`Unresolved reference: Settings`** + 一串级联错误。
→ 用全限定名 + 换局部变量名：
```kotlin
val schedSettings = com.focusguard.app.data.Settings(this)
```

### 1.5 不存在的图标

`Icons.Default.Alarm` / `Icons.Default.Timer` 之类不一定在依赖里。本项目只用 core + 部分 extended：
**遇到 `Unresolved reference: Alarm` 就换 `Icons.Default.Schedule`**（已确认可用）。
`FilterChip` 在本项目已“退役”，选项控件一律用自研 `FxOption` / `FxOptionRow`。

### 1.6 注解位置

用脚本插入 `@Composable` 时插到「已有注解和函数之间」会变成**两个注解之间夹了一行**⇒ 编译失败。插入后要 `sed -n` 看三行上下文。

### 1.7 data class 加字段会连坐所有位置参数

给 `QuestionBank.Item` 加 `sem` 字段 ⇒ `ChallengeGeneratorTest.kt:84` 6 处类型错误，CI 红。
→ **加字段时同步 grep 所有 `Item(` 构造与测试**。

### 1.8 API 33+ 才有的 Java 新方法

```kotlin
// ✗ 编译能过，但低版本运行时 NoSuchMethodError ⇒ 闪退
input.readNBytes(MAX)
```
`InputStream.readNBytes` 是 Java 9 / **Android API 33+**。低版本要用手动循环读：
```kotlin
val out = ByteArrayOutputStream(); val buf = ByteArray(64 * 1024); var total = 0
while (total < MAX) {
    val n = input.read(buf, 0, minOf(buf.size, MAX - total)); if (n <= 0) break
    out.write(buf, 0, n); total += n
}
```
> 同类要小心的：`String.isBlank` 没问题，但 `List.of`、`Map.of`、`Optional`、`java.time` 部分 API 都有 API 级别门槛。

---

## 2. Compose 坑

### 2.1 一个 `remember` 里跑重活 / 每帧都算

`AiClient.exportDiagnostics()` 原来在组合里被调用**两次**（一次给复制按钮、一次给 Text）。内容一大就是每帧分配大字符串。
```kotlin
// ✓ 只算一次，并限长
val diagText = remember(key) { runCatching { export() }.getOrElse { "失败：${it.message}" }.take(8000) }
```

### 2.2 滚动容器嵌在不可控高度里

`Card(heightIn(max = 300.dp)) { Text(modifier = Modifier.verticalScroll(...)) }` 在 `LazyColumn` 里容易测量异常/卡顿。
稳妥写法：**给滚动区固定高度**，滚动加在容器上而不是 Text 上：
```kotlin
Box(Modifier.fillMaxWidth().height(200.dp).verticalScroll(rememberScrollState())) { Text(big) }
```

### 2.3 长文本直接把 View 撑爆（低内存机型 = 闪退）

崩溃日志 / 诊断信息可能几十 MB。渲染前必须 `.take(N)`：渲染 20k、复制 50k、诊断 8k。

### 2.4 `takeScreenshot` 这类回调可能被调两次

协程里 `cont.resume()` 调两次会抛 `IllegalStateException`。用 `AtomicBoolean` 加一次性闸门：
```kotlin
val once = AtomicBoolean(false)
fun finish(v: T?) { if (once.compareAndSet(false, true) && cont.isActive) cont.resume(v) }
```

### 2.5 悬浮窗里不能弹 `AlertDialog`

`LockOverlayManager` 是 `TYPE_APPLICATION_OVERLAY` 的 ComposeView，弹 Dialog 会崩（项目注释里明确写了）。
→ 需要弹窗时：① 在 Activity（锁机页/设置页）里做；或 ② 在同一悬浮窗内**内联**渲染答题/面板。

### 2.6 答题/验证类 UI 不能复用“一次性提示通道”

AI 解析曾经塞进 `onWrong(...)`：既多计一次答错，又会被 900ms 后的 `nextQuestion()` 清掉 ⇒ 用户永远看不到。
→ **长文本必须独立状态槽**（`aiExplanation`）+ 独立区块渲染 + 只在“提交新答案”时清空。

---

## 3. Android 平台坑（分版本）

### 3.1 「点了就闪退，但没有崩溃日志」⇒ 先怀疑是我们自己杀的

**真因**：为了“退出后立刻从最近任务消失”，`MainActivity.onUserLeaveHint` 会 `finishAndRemoveTask()`。
任何“跳去别的应用”的操作（浏览器下载、分享面板、系统设置页、录屏授权框）都会触发它 ⇒ 整个任务被移除。
**系统视角是我们自己退出，所以不写崩溃日志。**

修法（已落地）：全局 `util/LeaveGuard`
```kotlin
LeaveGuard.open(context, intent)   // 内部：markLeaving() + FLAG_ACTIVITY_NEW_TASK
// onUserLeaveHint：
if (!hideFromRecents || launchingSystemUi || isFinishing || LeaveGuard.isExpected()) return
```
> 规律：**凡是“离开我们去开别人”的入口，必须打标记**；否则用户报“闪退”而日志一片空白。

### 3.2 常驻屏幕捕获会让通知正文被隐藏

现象：开了守护后通知栏能看到通知，但看不到微信内容。
根因：`MediaProjection` 常驻会话 ⇒ 系统/部分 ROM 把通知正文替换成「内容已隐藏」。
修法：优先 **无障碍截图**（API 30+，`takeScreenshot`），没有投影就没有这个副作用。

配套坑：
- `android:canTakeScreenshot="true"` 必须写在 `accessibility_service_config.xml`，否则 `takeScreenshot` 抛异常。
- `takeScreenshot` 有频率限制（约 333ms/次），别在 300ms 巡检里无脑调。
- **低版本（API < 30）没有这个能力**：若判断条件只写“无障碍已连”，低版本会一轮都抓不到画面 ⇒ 检测全废（本项目真实事故）。

### 3.3 MediaProjection 的四个坑

| 坑 | 说明 | 正确做法 |
|---|---|---|
| 每次授权都是新会话 | Android 14 起用户点两次“开始守护”会并存两个投影 | 新建前 `unregisterCallback` + `stop()` 旧的 |
| 必须先注册回调 | 否则 `createVirtualDisplay` 抛异常 | `projection.registerCallback(cb, null)` 再建 |
| 前台服务类型校验 | API 34 起，声明 `mediaProjection` 却**没有投影**就前台化 ⇒ `SecurityException` | 先用 `dataSync` 前台化；真建投影前再 `startForeground(..., MEDIA_PROJECTION or DATA_SYNC)`；清单写 `mediaProjection|dataSync` |
| 忘了释放 | 服务被系统销毁时投影残留，录屏指示常驻 | `onDestroy` 里 `runCatching { mediaProjection?.stop() }` |

### 3.4 Android 14 的录屏授权框会让人选“单个应用”

无参 `createScreenCaptureIntent()` 在新系统上会弹“单个应用 / 整个屏幕”选择器，选错就只截那一个应用 ⇒ 检测形同关闭。
```kotlin
if (SDK_INT >= 34) mgr.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
else mgr.createScreenCaptureIntent()
```

### 3.5 后台启动 Activity 会被静默吞掉

API 31+ 从 Service/Receiver/Worker/Alarm 里 `startActivity` 基本被禁；**不抛异常**，只是什么都没发生。
只有 `SYSTEM_ALERT_WINDOW` 等少数豁免。⇒ 关键页面（锁机页/封锁页）不能只靠 `startActivity`，
必须 **悬浮窗优先**，并且把“拉起失败”显式告诉用户（通知 + 首页提示），别用“没抛异常”当成功。

### 3.6 精确闹钟

API 31+ 需要 `SCHEDULE_EXACT_ALARM`（14+ 新装默认拒绝），不授权时 `setExact*` 抛 `SecurityException`。
```kotlin
val canExact = SDK_INT < 31 || runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
if (!canExact) am.setAndAllowWhileIdle(...) else am.setExactAndAllowWhileIdle(...)
```
而且**非精确闹钟唤起的广播不享受“后台启动前台服务”豁免** ⇒ 靠闹钟兜底的守护链会断。

### 3.7 通知权限（API 33+）

未授权 `POST_NOTIFICATIONS` 时 `nm.notify()` **静默丢弃**（不抛异常）⇒ “服务在跑但用户什么都看不到”。
凡是“提醒必达”的地方都要先判 `areNotificationsEnabled()`，没有就走悬浮窗 + 引导授权。

### 3.8 `registerReceiver` / `PendingIntent`

- API 34 起动态注册广播要 `Context.RECEIVER_EXPORTED / NOT_EXPORTED`；本项目**没有动态注册**（全清单静态 + `AudioManager` 媒体键），所以安全。
- API 31 起 `PendingIntent` 必须显式 `FLAG_IMMUTABLE`（本项目 12/12 都带 ✓）。新写时别漏。

### 3.9 `dataSync` 前台服务的 6 小时上限（targetSdk 35 起）

长期常驻（守护）若用 `dataSync` 类型，Android 15 起 24h 内累计 6h 后会回调 `Service.onTimeout()`，
没实现就崩/被杀。正确类型是 `specialUse` + `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" .../>`，
并且 Android 15 禁止 `BOOT_COMPLETED` 拉起 `dataSync` FGS（重启后守护不恢复）。

### 3.10 Edge-to-edge（targetSdk 35+）与预测性返回（36）

全屏页必须 `WindowCompat.enableEdgeToEdge(window)` + `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)`，
否则底部按钮被导航条盖住（用户会说“卡死”）。`onBackPressed` 要换成 `OnBackPressedCallback`。

### 3.11 使用情况权限被撤 = 静默空转

`UsageStatsManager.queryEvents()` 在授权不足时**返回空表而不抛异常** ⇒ 守护逻辑“正常跑、什么都没做”。
要在守护循环里定期 `PermissionChecker.isUsageStatsGranted()`，失权时显式告警。

### 3.12 `queryUsageStats` 的 INTERVAL 语义

`queryUsageStats(INTERVAL_DAILY, now-15s, now)` 拿到的其实是**今天整天**的聚合桶（不是 15 秒窗口）。
想“当前前台”就用 `queryEvents(now-10min, now)` 取最后一个 `ACTIVITY_RESUMED`。

### 3.13 事件流窗口 + 缓存 TTL 会合谋造出“恒为 null 的前台”（v3.11.58 真事故）

**现象**：同一应用停留几分钟后，检测日志出现「隐私保护：无法识别前台应用，本轮已跳过截屏」、
超限也不弹封锁页，而守护显示一切正常。

**根因**：`ForegroundAppDetector.current()` 只查「最近 10 秒」的 `ACTIVITY_RESUMED`，
查不到就复用缓存（TTL 30 秒）。用户在同一个视频/游戏里不动 ⇒ 没有新事件 ⇒
约 45 秒后缓存也过期 ⇒ **恒返回 null**。于是 `bestPackage()` 退化、
`isForeground()` 复核永远为假（而 `queryEvents` 又不抛异常）。

**正确做法**：
- 短窗口（10s）拿不到时，必须再查**长窗口**（10 分钟），并检查该应用之后有没有
  `PAUSED/STOPPED/BACKGROUND`——只看 RESUMED 会把“已经退出的应用”当成前台
  （不少桌面/ROM 不发桌面 RESUMED）。
- 同理：**“没事件”不等于“没前台”**。任何“窗口内无事件就返回 null”的探测都要有长窗口/缓存兜底。
- 一个进程里只应有一个前台判定入口（`ForegroundAppDetector`），别让检测、计时、执法各查各的。

**副产物**：`AppClassifier.classifyForegroundApp` 原来那版 `queryUsageStats(INTERVAL_DAILY, …)`
正是“各自查一套”的遗留物，语义与实现都不对（见 3.12）。

---

## 4. 权限 / 特权（Dhizuku / Shizuku / 设备管理员 / 无障碍）

- **系统应用（语音助手等）冻结一定失败**：`setPackagesSuspended` 会把系统包塞进 `failed` 且**不打印日志**；`setApplicationHidden` 对系统应用直接抛异常。
  真正能停住的是 **无障碍点“强行停止”**（`ForceStopHelper`）——不需要任何特权。
  ⇒ 可靠性排序：**冻结 > 隐藏 > 无障碍强停 > 反复顶回**。
- **“记录了但没真停”是常见 bug**：`LockTopGuard` 失败分支原来只写日志。任何“限制失败”的分支都要有兜底动作 + 用户可见记录（四态写检测日志）。
- **未授权时不要显示假开关**：用 `usable` 谓词过滤 + 一行“另有 N 项需要授权后自动出现”，比置灰开关体验好（用户反感假开关）。
- **DPM 策略要能回滚**：`setStatusBarDisabled` / `setKeyguardDisabled` 只在 LockTask 期间置真，退出与启动时都要清理，否则残留会导致状态栏/锁屏异常。

---

## 5. 锁机与悬浮窗架构坑

- **不能“强制退出”被封锁的应用**：要就地覆盖（`TYPE_APPLICATION_OVERLAY` 全屏）+ 真暂停（suspendBlock），两者**并存**，不是二选一。
- **小窗 / 分屏 / PiP 只能靠真暂停挡住**：判据是**窗口尺寸**（< 85% 屏幕），不是“窗口数 > 1”。
- **覆盖层不是 Activity** ⇒ `ForegroundAppDetector.current()` 仍报告下层应用，可用 `fg != pkg` 判断“用户走了”；**不要用 `rootInActiveWindow` 检查自己**。
- **暂停要引用计数**：锁机冻结 / AI 冻结 / 封锁冻结三条记录并存，释放时只有“没有其他引用”才真解除。
- **锁机页要尊重息屏**：用 `reassert()` 而不是 `show()`。

---

## 6. 数据工程坑（题库 / JSON / 批量处理）

### 6.1 静默过滤 = 数据凭空消失

CMMLU 一直贡献 0 条，最后发现是两个“合理”的门槛把它全丢了：
`if not q or not ans or not exp: return None`（CMMLU 没有解析）+ `if diff < 3: return None`（小学档是 d=2）。
**每个 loader 都要打印条数并对照预期量级**；`0 条 = 抓取/过滤失败`，不是“这个源没数据”。

### 6.2 编码 BOM

CSV 首列名变成 `\ufeffQuestion` ⇒ `DictReader` 的键取不到 ⇒ 全部跳过。用 `encoding="utf-8-sig"`。

### 6.3 学段/年级必须对齐“消费方枚举”

题库用 10/11/12，App 用 1–8 档 ⇒ 高二出小学题。**改数据前先读消费方枚举**，改完用**消费端模拟器**回归
（本项目 `sim3.py`：按 level × 学期 × 选科 × 借下一级跑池子，两个随机种子）。

### 6.4 “修而不删”

807 条“无选项”题其实选项内联在题干里（可切回来）；1,813 条答案被 `str()` 污染（归一化即可）。
先统计“真删的量”，再决定阈值——用户对题量敏感，且他自己预期“去掉无效的会远少于 1 万”。

### 6.5 JSON / 序列化

- 编辑“规则”时不要把时间戳之类的独立状态塞进同一条 JSON（整条重写会冲掉）⇒ **独立 key**。
- JVM 单测里 `org.json` 是空实现 ⇒ 任何碰 `JSONObject` 的单测都会红。**单测只测纯逻辑**。

### 6.6 长任务要脱离会话

```bash
pkill -f build_question_bank.py                    # 先清残留
setsid nohup python3 tools/build_question_bank.py > /tmp/build.log 2>&1 & disown
```
普通 `nohup … &` 会被工具超时连坐杀掉（日志 0 字节）。跑起来后**另起短调用轮询日志**。
顺便优化三件套：删掉“永远 0 条又最慢”的 loader、`http_get` 超时 60s→15s、多文件下载改 `ThreadPoolExecutor(8)` + 落盘缓存（20 分钟 → 2.5 分钟）。

---

## 7. 构建、CI 与发布坑

1. **CI 只监听 `main` / `fix/**` / `feat/**` / `v*` 标签**。推到 `feature/xxx` 不会有任何构建 —— 我在这上面浪费过一轮。
2. **push 可能静默没发生**（网络/超时）。判据：
   ```bash
   curl -s -H "Authorization: token $T" .../branches/feat/grade-challenge   # commit.sha 必须 == 本地 HEAD
   ```
   不等就重推。
3. **红色提交上的 tag 只会得到红色 tag 构建、不会生成 Release** ⇒ 打 tag 前必须分支 CI 绿。
4. **发布说明**：手写、一行一条“新增了/修复了/优化了”，`────────────────` 分隔，再加【两个版本的区别】块。
   **已发布那份要 `PATCH /releases/{id}`**，workflow 里的 `body:` 只管下一次。别把“下一版的修复”写进已发布版本的说明（干过一次，事后又 PATCH 回来）。
5. **发布后必须回读** `GET /releases/latest`：标题、说明行数、**两个 APK 资产**。
6. **Gitee 镜像**：仓库 API **不带 token 返回 404**；`gitee.com` 对本机 IP 有反爬（仓库页/下载直链 403）。所以“应用里先试 Gitee API”是空转 —— 正确做法是读仓库里的 `raw/<branch>/update.json` 这种公开 raw 文件。
7. **JVM 单测步骤**也会拦 CI（不是只有编译）。看到“单元测试”步骤红，先下载日志 zip 找 failed 用例名。
8. 每次推送都升 `versionCode`/`versionName` —— CI 测试版通道靠版本号判新旧。

---

## 8. 改代码的工程纪律（最省时间的一节）

### 8.1 一个补丁一个块，改完立刻 grep 复核

用脚本批量改文件时：
- **同一块里一个 `assert` 失败，后面所有补丁都不会执行**（真实事故：`scope` 补丁锚点匹配 2 次触发断言 ⇒ 同块里 `BootReceiver` 补丁整段没跑，文件零改动，我却以为改完了）。
- **锚点要照“刚读到的实际文本”写**：`delay(300` ✗（实际是 `delay(CHECK_INTERVAL_MS)`）、`question.answer.length > 1` ✗（实际是 `currentQuestion.answer.length > 1`）。
- 每改一处，立刻 `grep -n '<新代码片段>' <文件>` 确认真的落地。
- 括号计数器对“含大量内联字符串”的文件会**误判**（把已绿的文件判成不平衡）；它只能回答“文件到底动没动”，不能当语法检查。要更可靠就用 `git show HEAD:path` 与工作区对比，或者直接看 `git diff`。

### 8.2 推送前自检清单（用户明确要求过两次）

1. `git --no-pager diff --stat` + 通读关键 hunk；
2. `grep -n` 逐个确认新代码落地、旧符号没有残留引用；
3. 检查“同一语义”的所有使用点是否一致（例如一个开关被 6 处读取，语义改了要全改）；
4. 检查与它冲突的旧文案/旧逻辑是否清掉；
5. 想想“这个功能的失败路径”会不会被当成另一个 bug（例如抓不到画面 ⇒ 用户看到“检测不动”）；
6. 才 push，等 CI 绿，再回复。

### 8.3 状态机和幂等

- 任何“到点/轮询触发”的动作都要有**去重键**（`lastFired` + `MIN_GAP`），否则 300ms 轮询会触发几十次。
- 任何“可能同时存在两份”的资源（投影、会话、监听）都要“先回收再创建”。

### 8.4 不要为了绕开问题加开关

先找根因。开关只能作为“机型/系统能力差异”的出口，而且默认值要按能力选（高版本开、低版本关），不要给用户一个按不动的假开关。

---

## 9. 排障方法论：怎么从一句话反馈查到根因

1. **先分类**：崩溃（有栈）/ 我们自己退出（无栈）/ 功能不生效（有日志但结果不对）/ 体验慢。
   - **“闪退但没有崩溃日志” ⇒ 优先怀疑 `finishAndRemoveTask()`、`Process.killProcess`、系统因权限杀进程**，而不是 OOM/NPE。
2. **拿证据，不猜**：读真实代码行、跑真实命令（`curl` 看 http 码与字节数）、打日志看条数。
   例：“通知内容看不到”先排除 `VISIBILITY_SECRET`、DND、`KEYGUARD_DISABLE_SECURE_NOTIFICATIONS`、写系统设置，最后定位到常驻投影。
3. **对照“消费方”验证数据类问题**：改数据 → 跑消费端模拟器，而不是看数据自身统计。
4. **一次只改一个变量**：同时改 4 处再推，红了不知道是谁。
5. **失败要显式**：日志、检测记录、Toast、通知，四选一，但必须有。

---

## 10. 和这位用户协作的经验

- **中文**，回答要短、结论先行；他会因为你“只报进度没结论”而发火。
- **不许图快**：他说“完成以后一定要多检查几遍再推”“不要光图快”——照做，推之前真检查。
- **数字必须是真的**：题量、通过用例数、包大小；不要用“约”“预计”糊过去。
- **“存在 ≠ 做好”**：加了个面板/开关不等于做好，要入口明显、状态可见、能改的东西够全。
- **假开关/假状态最招骂**：没权限就隐藏或明确说明，不要显示“已开启”却什么都不做。
- **单向/难回退的设置必须走完整确认**（对话框 + 倒计时 + 明确文案）。
- **发布要等他点头**（新功能），别拿旧授权发新版。
- 他**没有高版本真机**（Android ≤ 10）⇒ 你的改动必须在低版本也能工作，并把“版本 → 行为”矩阵讲清楚。

---

## 附：一页速查清单（推之前过一遍）

- [ ] 新代码 `grep -n` 确认落地；旧符号无残留
- [ ] 同一语义的所有使用点都改到了
- [ ] 与旧文案/旧逻辑无冲突
- [ ] 失败路径有可见反馈（日志/提示/通知）
- [ ] 低版本（API 26/29/30 边界）行为明确
- [ ] 资源先回收再创建；定时动作有去重键
- [ ] 跳外部应用走 `LeaveGuard`
- [ ] `git diff` 通读 → push → CI 绿 → 再回复

---

## 11. 关键上下文：用户原话摘录（按主题，逐字）

> 这一节是“为什么代码长成这样”的原始依据。改相关模块前先读对应用户原话，别按自己的理解改。

### 11.1 过程纪律（反复强调，最容易被忽略）

- 「完成以后一定要多检查几遍再推」
- 「不要光图快」
- 「学段一定要搞对，不要再出现解答不了的问题」
- 「题目要求偏难，你知道的吧」
- 「然后还有周日选不到的问题，你每次推送之前都先检查一下是否做完做妥了再推」
- 「我不是说了吗，让你检查一遍后在推吗？记好了」
- 「不是跟你说了检查一遍有没有冲突，漏洞再推吗？」
- 「我没高版本安卓的手机进行测试，你一定要帮我改好了，多检查几遍，确保没有Bug和漏洞以及各种问题」

→ **决策影响**：形成了“一个补丁一个块 + 就地 grep 复核 + git diff 通读 + 同语义全量核对 + CI 绿才回复”的固定流程；也形成了本文第 8 节。

### 11.2 题库（数据工程）

- 「可以，然后题目稍微再筛选一下就可以发正式版了，现在目前有些题目出现了：不属于课本知识，比如生物，还有些题过简单了，纯考概念，你再删选一下发布正式版就行了」
- 「去掉无效的那么题目数量将远远小于你预期的10000道了」
- 「当无解析时可以调用已配置的AI生成解析」
- 「然后题目筛选的时候再让用户配置的AI再进行一次难度/学段校验，通过→推给用户，不通过→再重新筛选一道题」
- 「我测试的数学有些题显示多选，实则单选，并且单选即使选对了也显示错误」「英语历史这种题有的无选项框，无法作答」

→ **决策影响**：`CMMLU_KEEP` 只留 21 个课内科目；理科剔“无数字无运算符且 <40 字”的纯概念题；<3 选项的判断题丢弃（15,340 → 7,244 题）；
无解析题放行但答错时用用户自己的 AI 生成解析（缓存 + 计配额）；出题时用 AI 二次校验难度/学段（fail-open、≤3 次重筛、校验期停表）；
判“多选”改为“去重后 ≥2 个字母”。

### 11.3 锁机 / 冻结 / 答题进度

- 「然后实测锁机过程中的检测软件冻结后并不会真实冻结，比如语音助手」
- 「然后锁机（连续解答5次）时保留前面几次的记录（修复退出重进需要重新答的问题）」
- 「然后还有退出后再进题目会自动切换的问题」
- 「锁机页还是没有定时锁机的选择，然后设置定时锁机的页面太乱了，太丑了，请你修复一下，并设计好布局和精美的UI」

→ **决策影响**：`LockTopGuard` 失败分支降级到无障碍“强行停止”；`LockState` 持久化答题进度+当前题目+输入；
系统助手可靠性排序 冻结 > 隐藏 > 无障碍强停 > 顶回；定时锁机 UI 重做并并入锁机模式页（持续锁机 → 番茄钟 → 定时锁机）。

### 11.4 定时锁机

- 「然后添加定时锁机的功能，记得多检查几遍有没有什么漏洞」
- 「怎么没看到定时锁机的按钮？」
- 「你定时锁机为什么写在设置里面，而不是锁机页」
- 「第一，锁机页面还是没有定时锁机的选项，二，定时锁机的设置页面太简单，太简陋，也看不懂怎么设置，然后不美观，等等问题，请你做的简洁明了一点，让人都能看懂」
- 「然后就放在持续锁机/番茄锁机后面」
- 「然后AI聊天也可以控制定时锁机，比如xxx时锁我xxx小时」
- 「然后定时锁机也调小要有答题」

→ **决策影响**：不依赖 AlarmManager（守护 300ms 巡检触发）；关闭/删除/**改小**都要答题，锁机期间禁止修改；
AI 聊天工具只允许**新增**计划（禁止删除/停用）；入口有三处（设置页、锁机页可点行、锁机模式页第三模式）。

### 11.5 检测速度与触发时机

- 「然后就是识别的太慢了」
- 「应用一变前台就立刻检测可能用户还没打开娱乐内容而检测成中性，建议等5-6秒」

→ **决策影响**：前台切换触发检测，但**延迟 5.5 秒**（`FOREGROUND_QUICK_DELAY_MS`）；同一应用连续回调只算一次。
（注：`DetectionPipeline` 已有“画面未变化复用结论”的去重，结论缓存 `DecisionCache` 也在，但用户仍觉得慢，下一步要按环节拆耗时。）

### 11.6 “闪退 / 强制退出 / 通知看不到内容”

- 「它检测更新检测到新版本后点击下载直接闪退是怎么回事」
- 「CI更新驱动检查更新检查不到是怎么回事，然后检查更新还是会闪退，分析日志也会闪退，你检查一下」
- 「也可能不是闪退，是强制退出之类的？（因为没有崩溃日志）」
- 「有人反馈说：“点击开始守护，就会提示分享屏幕，然后只能选择一个应用，这种情况怎么解决​”」
- 「有人反馈开了守护之后，通知栏能看到通知，但是看不到具体内容 …… 本来通知栏可以看到别人微信发的具体内容的，但是现在看不到了，你解决一下」

→ **决策影响**：
- 无崩溃日志的“闪退” ⇒ 定位到 `onUserLeaveHint → finishAndRemoveTask()`，新增 `LeaveGuard`（第 3.1 节）；
- 通知内容被隐藏 ⇒ 常驻 MediaProjection 导致系统脱敏 ⇒ 改用无障碍截图（第 3.2 节）；
- 共享屏幕只能选一个应用 ⇒ Android 14 `MediaProjectionConfig`（第 3.4 节）。

### 11.7 「截屏识别」开关的语义（三次对齐后的最终版）

- 「然后这个开关默认开启效果好还是默认关闭效果好，然后按钮关闭后应该自动开启共享屏幕的权限（可用无障碍点击）然后发布一个测试版本」
- 「然后它开关选择后会提示重新启动，你可不可以做成自动重启的？」
- 「不是开关开-无障碍截屏开关关-共享屏幕吗？」
- 「所以是高版本安卓默认开，低版本自动关对吗？」

→ **决策影响**：**开 = 无障碍截图；关 = 共享屏幕**；高版本（API 30+）默认开，低版本默认关**且开关置灰**；
切换开关后**自动重启守护**（关时自动 arm `ProjectionConsent` 让无障碍点掉系统授权框），不再提示“请重新开始守护”。

### 11.8 发布与 Gitee

- 「好的，帮我发布正式版吧，同时同步了gitee上1b679a115431fe1e89c50d7b3f873a9b」
- 「然后把gitee的realea的超链接指向gitee里的」
- 「你看着办吧」（针对“Gitee 更新源选 A 还是 B”）

→ **决策影响**：正式版 v3.11.46 / v3.11.47 已 tag 发布（含两个 APK + 两版区别说明）；
Gitee 侧建了 `autumiao/FocusGuard`、推了 master+tags、建了 v3.11.46/47 的 Release 并上传两个 APK；
更新源改为“先试 Gitee、失败回落 GitHub”，**但 Gitee API 不带 token 返回 404，实际仍是空转** ⇒ 下一步按方案 A 改 raw `update.json`。
