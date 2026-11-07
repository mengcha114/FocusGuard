# 交接文档（FocusGuard · v3.11.57）

> 写给下一个对话：读完这份就能接着干，不用再摸一遍。

## 1. 仓库与发布状态（当前）

| 项 | 值 |
|---|---|
| 仓库 | `/root/FocusGuard`（remote `origin` = github.com/mengcha114/FocusGuard） |
| **工作分支** | **`feat/grade-challenge`**（⚠️ 不是 `main`，也**不是** `feature/ai-chat-tools`；CI 只监听 `main`/`fix/**`/`feat/**`/`v*` 标签，推错分支等于没有 CI） |
| HEAD | `de57284` = **v3.11.57 / vc158**，**分支 CI 绿 ✓** |
| CI 测试版 | `ci-latest` 会随分支推送自动覆盖，标题形如「CI 测试版 v3.11.57（自动覆盖）」 |
| **线上最新正式版** | **`v3.11.47 - 正式版`**（tag `v3.11.47`）；`v3.11.48`…`v3.11.57` **全部未打 tag、未发布** |
| Gitee 镜像 | `https://gitee.com/autumiao/FocusGuard`（master + tags + Release v3.11.46/47 含两个 APK）。⚠️ 这台机器访问 gitee.com 会被反爬 **403**，且 Gitee API **不带 token 返回 404** |
| 本地工作区 | 干净（除未跟踪 `.mimocode/`） |

**推送方式**（token 只在命令里内联，**绝不写文件**）：
```bash
T='github_pat_…'          # 复用历史里的 token（可用 history 工具搜 github_pat）
git -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=60 push -q \
  "https://x-access-token:$T@github.com/mengcha114/FocusGuard.git" HEAD:feat/grade-challenge
```
推完必须用 `/branches/feat/grade-challenge` 的 `commit.sha` 与本地 `rev-parse HEAD` 对账（本会话遇到过 push 静默失败）。

**发正式版流程**：分支 CI 绿 → 改 `.github/workflows/build-apk.yml` 里的 `body:`（一行一条「新增了/修复了/优化了」+ `────────────────` + 【两个版本的区别】）→ 提交 →
`git tag -f vX.Y.Z <sha>` → `git push -f … refs/tags/vX.Y.Z` → 等 tag 构建绿 → `GET /releases/latest` 核对标题/说明行数/两个 APK。
**新功能发布必须用户点头**（他明确说过「我说了算」，别擅自 tag）。

## 2. 用户的核心要求（硬规矩）

1. **中文回复**；API 按次计费 ⇒ 少调用、批量改。
2. **推送前必须自己检查一遍（有没有冲突、漏洞、写完没接上）再推，不许图快**。他为此发过两次火。
3. 每次推送都升 `versionCode`/`versionName`（CI 靠版本号判新旧）。
4. 他**没有高版本安卓真机**（他测的机器 **Android ≤ 10**），所以任何“只有 API 30+ 才有”的能力都必须有低版本回退，并且要写清楚矩阵。
5. 别加逃生通道/后门（产品目标是“最强锁机软件”）。

## 3. 本会话已交付（v3.11.38 → v3.11.57）

- **定时锁机**（`data/ScheduledLock.kt` + `LockGuardService.checkScheduledLocks()` + 设置页卡片）：按周定时、到点由守护 300ms 巡检触发；**关闭/删除/改小都要答题**；界面已并入锁机模式页作为第三种模式（持续锁机 → 番茄钟 → 定时锁机，`TimerLockScreen.kt` + `ui/components/ScheduledLockSection.kt`）；锁机页有可点入口。
- **AI 聊天可下定时锁机指令**（`enforce/ScheduledLockToolExecutor.kt`，只允许新增，禁止删除/停用）。
- **加固项按权限显示**（`EnhanceCards.LockHardeningCard` 用 `hardeningUsable()` 过滤 + 「另有 N 项…」）。
- **正式版 v3.11.46 / v3.11.47** 已发布（含定时锁机 UI、Gitee 优先的更新源）。
- **截屏链路重构（v3.11.48→v3.11.57）**：新增无障碍截图 `GuardAccessibilityService.captureScreenshot()` + `capture/AccessibilityCapturer.kt` + `android:canTakeScreenshot="true"`；`MonitorService` 不再无条件建投影；新增设置项 **「截屏识别」**；修低版本必须回落录屏的致命 bug。
- **强制退出（无崩溃日志）真因已修**：新增 `util/LeaveGuard.kt`；`MainActivity.onUserLeaveHint` 的 `finishAndRemoveTask()` 增加第三条闸门；设置页「下载新版本」「导出诊断日志」、待办/增强卡/升级跳转共 5 处外部跳转全部走 `LeaveGuard`。
- **更新检测加固**：响应体限 2MB 手动限量读取（不能用 `readNBytes`，API 33+ 才有）、测试版页面只在 `markdown-body` 后 200KB 内解析、测试版改为**按版本号比较**（覆盖资产时 `published_at` 不变）、日志页超长文本限长渲染（20k/8k/50k）。
- **其他兼容修复**：前台服务类型 `mediaProjection|dataSync` 分开声明并在建投影前升级；旧投影先 `unregisterCallback+stop` 再新建；`onDestroy` 释放投影；自愈闹钟先查 `canScheduleExactAlarms()`；前台切换**延迟 5.5 秒**再检测（避免“还没打开内容就判中性”）。

## 4. 「截屏识别」最终语义（已与用户对齐，勿再改）

| 系统 | 开关默认 | 实际方式 |
|---|---|---|
| Android 11+（API 30+）且无障碍已连 | **默认开** | **无障碍截图**（不弹共享屏幕、不影响通知内容） |
| Android 10 及以下 / 无障碍没开 | **默认关且置灰** | **共享屏幕（录屏）** |

两值语义：**开 = 无障碍截图；关 = 共享屏幕**。
代码落点：`Settings.screenshotDetection`（默认值按 SDK 版本）、`MonitorService`（`a11yReady` 要求 `SDK_INT >= R`；`wantProjection = data != null && (!sd || !a11yReady)`）、`DetectionPipeline`（无投影时 `else if (settings.screenshotDetection)` 才用无障碍截图）、`SettingsScreen`（`enabled = a11yShotSupported`）。
另有兜底：无障碍截图连续失败 3 次会写一条检测日志提示“改用共享屏幕”。

## 5. 未完成 / 下一步（按优先级）

1. **等用户真机验收 v3.11.57**：① 低版本上「截屏识别」显示为关+不可点；② 日志不再出现“屏幕内容不可用”，AI 检测恢复；③ 检测更新→下载新版本、导出诊断日志都不再退出应用。若仍异常，让他把日志页「崩溃日志」卡内容复制过来。
2. **识别速度**：目前只做了“前台切换延迟 5.5s 触发一次”。用户仍反馈“识别太慢” ⇒ 下一步按日志拆耗时（截屏 / 文字提取 / AI 判定 / 缓存命中），可加“同应用 30s 内复用结论”“画面未变化直接跳过”（`DetectionPipeline` 已有 L4 去重，需确认开关默认值）。
3. **Gitee 更新源改 raw 清单（方案 A，用户已授权“你看着办”）**：仓库根加 `update.json`（`{tag, notes, assets{edu,general}}`），`UpdateChecker.probeGitee()` 改读 `https://gitee.com/autumiao/FocusGuard/raw/master/update.json`（公开免 token），失败再回退 GitHub。现在实现仍是“先试 Gitee API”，**实测不带 token 会 404，等于空转**。
4. **兼容审计剩余 4 条**（另一代理已定位，未实施）：
   - C5 使用情况权限被撤后守护静默空转（`guardTick` 每 5s 判 `PermissionChecker.isUsageStatsGranted`）
   - C6 `detection/AppClassifier.kt:133` `queryUsageStats(INTERVAL_DAILY, now-15s, now)` 语义错 ⇒ 改 `queryEvents`
   - C7 `AlertNotifier` 未判 `areNotificationsEnabled()`（API 33+ 未授权时提醒静默消失）
   - C8 `boot/BootReceiver.kt:143-166` 把“没抛异常”当成功 ⇒ 延迟校验 `MonitorService.isRunning`
5. **升 targetSdk 35/36 前必做**（现在 targetSdk=34，本轮不动）：常驻服务 `dataSync`→`specialUse` + `<property PROPERTY_SPECIAL_USE_FGS_SUBTYPE>` + `Service.onTimeout()`；Android 15 禁止 BOOT_COMPLETED 拉起 dataSync FGS；`AppBlockActivity`/`AlertActivity` 补 `enableEdgeToEdge` + `safeDrawing`；`onBackPressed`→`OnBackPressedCallback`。
6. **待用户拍板**：初中题池最薄（初三 45 题）、1–3 年级无题库题是否接受；README 是否推 `main`（问过三次）；学期默认“下学期”是否合意；`SettingsDiff.dailyCallLimit` 是否回退。

## 6. 已知坑（别重复踩）

- **JVM 单测里 `org.json` 是空实现** ⇒ 任何碰 `JSONObject/JSONArray` 的单测都会让 CI 红在“单元测试”步骤。单测只测纯逻辑。
- 服务/工具类里写 `val settings = Settings(this)` 会与 `LockGuardService.settings` 属性冲突 ⇒ 用全限定名 + 换局部变量名。
- **`Icons.Default.Alarm` 不存在**（本项目用 `Schedule`）；`FilterChip` 已退役，选项一律 `FxOption/FxOptionRow`。
- **一个 python 补丁块里 assert 失败会带走同一块后面所有补丁** ⇒ 一处补丁一个块，改完立刻 `grep -n` 复核；括号计数器对含大量内联字符串的文件会误判，只能用来回答“文件到底动了没有”。
- Kotlin 语法只能靠 CI 验证 ⇒ 本地改完先 `git diff` 通读，再推。
- 定时锁机**不要引 AlarmManager**：守护服务每 300ms 巡检就够。
- 任何“离开我们去开别的应用”的入口都必须走 `LeaveGuard.open(...)`（或至少 `markLeaving()`），否则会被 `onUserLeaveHint` 当成用户退出而 `finishAndRemoveTask()`，表现为**无崩溃日志的闪退**。

## 7. 常用命令

```bash
# 等某个 sha 的 CI 结果
T='github_pat_…'; SHA=$(git rev-parse --short HEAD)
curl -s -H "Authorization: token $T" \
  "https://api.github.com/repos/mengcha114/FocusGuard/actions/runs?per_page=6" \
  | python3 -c "import json,sys;[print(r['head_sha'][:7],r['status'],r['conclusion']) for r in json.load(sys.stdin)['workflow_runs'] if r['head_sha'].startswith('$SHA')]"

# 看失败原因（下载完整日志 zip）
curl -sL -H "Authorization: token $T" ".../actions/runs/<RUN_ID>/logs" -o /tmp/logs.zip

# 核对线上最新 Release
curl -s -H "Authorization: token $T" "https://api.github.com/repos/mengcha114/FocusGuard/releases/latest"

# Gitee 同步（token 内联，勿落文件）
G='…gitee_token…'
git -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=60 push -q -f \
  "https://autumiao:$G@gitee.com/autumiao/FocusGuard.git" HEAD:refs/heads/master refs/tags/vX.Y.Z
```

---

## 8. 凭据（GitHub / Gitee）与用法

> ⚠️ **我没有把明文 token 写进这份文档**，原因有三：
> ① 项目既有规矩就是「token 只走环境变量或命令行内联，绝不落文件」；
> ② 这份文档很可能被整段粘贴到别的对话/聊天记录里，落盘等于二次扩散；
> ③ 明文 token 写进文件后很容易被 `git add` 误提交。
>
> **正确做法**：把两条 token 放进你自己的本地文件（不进仓库），每次用时 `source` 进来。

### 8.1 一次性准备（你自己执行，只做一次）

```bash
# 存到仓库外、仅本人可读
cat > ~/.focusguard-secrets.env <<'EOF'
export GH_TOKEN='<你的 GitHub PAT，github_pat_ 开头>'
export GITEE_TOKEN='<你的 Gitee 私人令牌，32 位十六进制>'
EOF
chmod 600 ~/.focusguard-secrets.env
```

用时：`source ~/.focusguard-secrets.env`

- **GitHub PAT 需要什么权限**：仓库 `Contents: Read and write`（推代码、打标签）即可。
  发布 Release 用的是 **workflow 里的 `secrets.GITHUB_TOKEN`**（已声明 `permissions: contents: write`），
  所以手动 tag 只需要推标签权限。生成入口：GitHub → Settings → Developer settings → Personal access tokens（Fine-grained）。
- **Gitee 私人令牌**：Gitee → 设置 → 安全设置 → 私人令牌，勾 `projects`（仓库读写）即可。
  Gitee 的 **API 必须带 token**（不带返回 404），但 Release 附件的**下载直链是公开的**。

### 8.2 GitHub：推送 / 核对 / 打标签发版

```bash
source ~/.focusguard-secrets.env
cd /root/FocusGuard

# 推送（⚠️ 分支必须是 feat/** 或 fix/**，否则不触发 CI）
git -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=60 push -q \
  "https://x-access-token:$GH_TOKEN@github.com/mengcha114/FocusGuard.git" HEAD:feat/grade-challenge

# 核对是否真推上去了（远端 sha 必须 == 本地 HEAD）
curl -s -H "Authorization: token $GH_TOKEN" \
  https://api.github.com/repos/mengcha114/FocusGuard/branches/feat/grade-challenge \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['commit']['sha'][:7])"
git rev-parse --short HEAD

# 等 CI（按 head_sha 匹配，别信“最新一条”）
SHA=$(git rev-parse --short HEAD)
curl -s -H "Authorization: token $GH_TOKEN" \
  "https://api.github.com/repos/mengcha114/FocusGuard/actions/runs?per_page=6" \
  | python3 -c "
import json,sys
for r in json.load(sys.stdin)['workflow_runs']:
    if r['head_sha'].startswith('$SHA'): print(r['status'], r['conclusion'], r['id']); break
else: print('none')"

# 发正式版（前提：该 sha 分支 CI 绿；发布说明先改 .github/workflows/build-apk.yml 的 body:）
git tag -f v3.11.58 <sha>
git push -f "https://x-access-token:$GH_TOKEN@github.com/mengcha114/FocusGuard.git" refs/tags/v3.11.58

# 回读核对：标题 / 说明行数 / 两个 APK
curl -s -H "Authorization: token $GH_TOKEN" \
  https://api.github.com/repos/mengcha114/FocusGuard/releases/latest
```

### 8.3 Gitee：镜像同步 / 建 Release / 传 APK

```bash
source ~/.focusguard-secrets.env

# ① 推代码与标签（标签可能已存在 ⇒ 加 -f）
git -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=60 push -q -f \
  "https://autumiao:$GITEE_TOKEN@gitee.com/autumiao/FocusGuard.git" \
  HEAD:refs/heads/master refs/tags/v3.11.58

# ② 建 Release（正文复用 GitHub 那份：先 curl 下载 GitHub release JSON 到 /tmp/rel.json）
python3 - <<'GITEE_RELEASE'
import json,os,urllib.parse,urllib.request
G=os.environ['GITEE_TOKEN']
body=json.load(open('/tmp/rel.json'))['body']
data=urllib.parse.urlencode({'access_token':G,'tag_name':'v3.11.58',
      'name':'v3.11.58 - 正式版','body':body,'target_commitish':'master'}).encode()
req=urllib.request.Request('https://gitee.com/api/v5/repos/autumiao/FocusGuard/releases',
                           data=data,method='POST')
r=json.load(urllib.request.urlopen(req,timeout=40)); print('release id =',r['id'])
GITEE_RELEASE

# ③ 上传两个 APK：POST /api/v5/repos/autumiao/FocusGuard/releases/{id}/attach_files
#    multipart 字段：access_token + file；单文件 20MB+，超时设 600s 以上
#    （本会话用过 http.client 手工拼 multipart 的脚本，思路见下）
```

最小上传脚本骨架：

```python
import os, http.client, uuid, json
G=os.environ['GITEE_TOKEN']; RID='<上一步返回的 id>'
def upload(path):
    name=os.path.basename(path); size=os.path.getsize(path); b='----b'+uuid.uuid4().hex
    head=(f'--{b}\r\nContent-Disposition: form-data; name="access_token"\r\n\r\n{G}\r\n'
          f'--{b}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
          f'Content-Type: application/vnd.android.package-archive\r\n\r\n').encode()
    tail=f'\r\n--{b}--\r\n'.encode()
    c=http.client.HTTPSConnection('gitee.com',timeout=900)
    c.putrequest('POST',f'/api/v5/repos/autumiao/FocusGuard/releases/{RID}/attach_files')
    c.putheader('Content-Type',f'multipart/form-data; boundary={b}')
    c.putheader('Content-Length',str(len(head)+size+len(tail)))
    c.endheaders(); c.send(head)
    with open(path,'rb') as f:
        while (chunk:=f.read(1<<20)): c.send(chunk)
    c.send(tail); print(name,'->',c.getresponse().status)
```

### 8.4 已知限制 / 坑

- **Gitee API 不带 token 返回 404**（不是 401）⇒ 应用里“先试 Gitee API”是空转，正解是读仓库 **raw** 文件（`update.json`）。
- **本机 IP 访问 gitee.com 会被反爬**（仓库页 / Release 页 / 下载直链都 403，`Server: ADAS`）⇒ 无法在服务器侧验证“匿名用户可下载”，必须让用户手机实测。
- **token 泄露处置**：立刻在 GitHub / Gitee 后台**吊销并重建**，只更新 `~/.focusguard-secrets.env`，仓库里不留痕迹。
- **绝对不要**把 token 写进：`.kt` / `.md` / `.yml`、commit message、issue/PR 正文、日志文件。

### 8.5 如果你坚持要明文写盘

请**明确说一句“写明文”**，我会写到 **仓库外** 的 `~/.focusguard-handoff-secrets.md`（`chmod 600`），
不会写进 `/root/FocusGuard/` 下任何文件。更推荐：保留 `~/.focusguard-secrets.env`，
把 8.2 / 8.3 的命令直接给下一个对话，它 `source` 一下即可用。
