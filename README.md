# FocusGuard (专注卫士)

> ⚠️ **软件目前处于早期测试阶段，功能与架构仍在密集调整中，极其不稳定。**

FocusGuard 是一款基于 AI 视觉识别与多层硬性防护的 Android 专注自律应用。应用通过定期采集屏幕画面，交由多厂商视觉大模型进行行为判定（学习/工作 vs 娱乐/游戏），并在检出娱乐违规时执行硬性封锁与锁机保护。

---

## 🌟 核心功能

1. **AI 视觉行为检测**
   - 适配 OpenAI 兼容接口、Anthropic Claude、Google Gemini 等多协议大模型
   - 三级梯队判断：应用包名分类 → 屏幕文字匹配（特征词可自定义）→ AI 视觉深度理解
   - 智能秒级动态采样调度（风险评分 EWMA + 应用停留时长学习 + 提醒折半）

2. **多层锁机防护体系**
   - **Dhizuku / Lock Task 模式**（推荐）：通过 Device Owner 权限激活系统级 Kiosk 模式，屏蔽分屏、小窗、手势与 Home 键
   - **常驻全屏悬浮窗**（无 Dhizuku 场景）：高优先级 `TYPE_APPLICATION_OVERLAY` + 键盘事件硬拦截 + 系统栏沉浸式屏蔽，**答题界面同样绘制在悬浮窗内**——不依赖 Activity，系统手势物理上无法退出
   - **答题解锁机制**：本地即时生成挑战题，支持单题解锁 / 连对解锁 / 朋友凯撒密文辅助，可设置「换一题」（限 5 次）与中途暂停配额

3. **智能 AI 助手与备忘录**
   - AI 对话面板：Markdown 渲染 + SSE 流式输出 + 对话持久化
   - 结构化待办清单（优先级、截止时间、逾期提示），AI 可在对话中直接追加/完成待办
   - **独立备忘录页**：分组列表（逾期/今天/明天/待安排/已完成）、Material3 日期+时间选择器、个性化外观（字号三档/文字颜色/卡片背景）
   - **到期提醒**：AlarmManager 墙钟闹钟 + 通知横幅（Android 12+ 精确闹钟权限，不可用时自动降级；开机自动重排）
   - **完成热力图**：GitHub 风格 14 周×7 天琥珀色阶日历，点按查看每日完成内容，含连续天数/本周/累计统计
   - **第三方导入**：从任意备忘录 App 分享文本导入、剪贴板批量粘贴、txt/csv 文件导入（每行一条，可用 `|` 分隔优先级与截止）

---

## 🚀 构建

```bash
# 需要 JDK 17 + Android SDK
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

CI：GitHub Actions 自动构建（`assembleDebug`），APK 从 Actions 产物下载。

---

## ⚠️ 免责声明与隐私说明

- 本软件**处于测试阶段**，可能存在稳定性问题或误判，请理性使用
- **屏幕检测会把截屏画面上传到您配置的 AI 模型服务**进行识别——请使用可信的服务商，并留意画面中可能包含的个人隐私信息
- **内置敏感应用保护**：检测到银行/支付/密码管理/健康医疗/邮箱/相册等敏感应用时，本轮自动跳过截屏与内容读取（可在「设置 → 隐私保护」中关闭或自定义列表）
- **API 密钥加密存储**：密钥使用 Android Keystore 的 AES-256-GCM 加密落盘，不以明文写入应用数据；应用数据不参与云备份
- 锁机功能为**自我约束工具**，无法阻止物理手段（关机、拔电池），不构成对设备的完全控制
- 因使用本软件造成的任何后果，由使用者自行承担

---

## 🤝 贡献

欢迎提交 Issue 与 Pull Request。

- 代码风格：Kotlin + Jetpack Compose，Material 3
- 提交信息：Apache 简洁规范（`feat:` / `fix:` / `chore:` 前缀，单行）
- 主要目录：
  - `app/src/main/java/com/focusguard/app/enforce/` — 锁机 / 悬浮窗 / 答题
  - `app/src/main/java/com/focusguard/app/detection/` — 检测流水线（分类 → 文字 → AI）
  - `app/src/main/java/com/focusguard/app/ai/` — 多协议 AI 客户端
  - `app/src/main/java/com/focusguard/app/service/` — 守护服务与自愈闹钟

---

## 📄 许可证

[MIT License](LICENSE) © 2026 mengcha114

本软件按「现状」提供，不附带任何担保，详见 [LICENSE](LICENSE)。
