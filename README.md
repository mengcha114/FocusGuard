# FocusGuard (专注卫士)

> ⚠️ **软件目前处于早期测试阶段，功能与架构仍在密集调整中，可能存在各种问题，请谨慎使用。**

FocusGuard 是一款 Android 自律辅助应用。它会定期看一眼屏幕，通过 AI 判断你是在学习还是在玩，如果发现你在娱乐，就会把手机锁起来，让你专心做事。

---
##作者的话
**求求了，点个 star 吧！谢谢了！😭**

---

## 主要功能

1. **AI 判断你在干什么**
   - 支持多种 AI 服务（OpenAI 兼容接口、Claude、Gemini 等）
   - 判断分三级：先看应用名 → 再看屏幕文字 → 最后交给 AI 看图
   - 判断到娱乐行为会提醒你，多次提醒无效就会锁机

2. **锁机保护**
   - 锁机时全屏挡住屏幕，退出不了，只能答题解锁
   - 支持答对一题解锁 / 连续答对多题解锁 / 找朋友帮忙解锁
   - 可以设置中途暂停（也要答题换）、换一题（次数有限）
   - 条件允许时可以开启更强的 Dhizuku 锁机模式

3. **AI 对话助手**
   - 内置聊天面板，可以跟 AI 聊天，让它帮你加待办、提醒你自律，在设置页面还可以自定义聊天角色喵
   - 在聊天里说"锁我 X 分钟"也能真的触发锁机

4. **待办与备忘录**
   - 简单的待办清单，可以设优先级和截止时间
   - 独立的备忘录页面，支持分组、改外观
   - 到期的备忘录会发通知提醒
   - 支持从其他备忘录 App 导入内容

---

## 构建

```bash
# 需要 JDK 17 + Android SDK
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

CI：GitHub Actions 自动构建，APK 从 Actions 产物或 Releases 页面下载。

---

## 免责声明与隐私说明

- 本软件**处于测试阶段**，可能有稳定性问题或误判，请理性使用
- **屏幕检测会把截屏画面上传到您配置的 AI 服务**进行识别——请使用可信的服务商，注意画面中可能包含的个人隐私
- 检测到银行、支付、密码管理、相册等敏感应用时，会自动跳过截屏（可在设置中关闭或自定义）
- API 密钥会加密保存在手机本地，不会明文存储，也不参与云备份
- 锁机只是自我约束工具，无法阻止关机、拔电池等物理手段
- 因使用本软件造成的任何后果，由使用者自行承担

---

## 贡献

欢迎提交 Issue 与 Pull Request。

- 技术栈：Kotlin + Jetpack Compose
- 主要目录：
  - `app/src/main/java/com/focusguard/app/enforce/` — 锁机 / 悬浮窗 / 答题
  - `app/src/main/java/com/focusguard/app/detection/` — 检测（分类 → 文字 → AI）
  - `app/src/main/java/com/focusguard/app/ai/` — AI 客户端
  - `app/src/main/java/com/focusguard/app/service/` — 后台守护

---

## 许可证

[MIT License](LICENSE) © 2026 mengcha114

本软件按「现状」提供，不附带任何担保，详见 [LICENSE](LICENSE)。
