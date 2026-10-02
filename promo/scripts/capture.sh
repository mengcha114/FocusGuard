#!/usr/bin/env bash
# 在 Android 模拟器中安装 FocusGuard debug APK，写入演示数据并截取真实界面。
#
# 用法：capture.sh <apk路径> <输出目录>
# 任何单张截图失败都只记录日志、不中断：渲染端会对缺失的截图改用网页还原界面。
set -u

APK="$1"
OUT="$2"
PKG="com.focusguard.app"
mkdir -p "$OUT"

log() { echo "[capture] $*"; }

adb wait-for-device
# 等系统完全启动
for _ in $(seq 1 60); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
adb shell input keyevent 82 || true
# 关闭系统动画，截图时机更稳定
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
# 演示用 24 小时制、整齐的状态栏
adb shell settings put system time_12_24 24 || true
adb shell settings put global sysui_demo_allowed 1 || true
adb shell am broadcast -a com.android.systemui.demo -e command enter || true
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0930 || true
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 86 -e plugged false || true
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 || true
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false || true

log "安装 APK"
adb install -r -g "$APK" || { log "安装失败"; exit 0; }

# ── 权限：使用情况访问 + 悬浮窗 + 通知（无障碍单独开启） ──
adb shell appops set "$PKG" GET_USAGE_STATS allow || true
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
A11Y="$PKG/$PKG.access.GuardAccessibilityService"
adb shell settings put secure enabled_accessibility_services "$A11Y" || true
adb shell settings put secure accessibility_enabled 1 || true

# 首次冷启动一次，让应用创建私有目录
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
sleep 3
adb shell am force-stop "$PKG"

NOW_MS=$(( $(date +%s) * 1000 ))

# ── 写入 SharedPreferences / 文件（debug 包可 run-as） ──
push_pref() { # $1=文件名(不含.xml)  $2=本地xml
  adb push "$2" /data/local/tmp/fg_pref.xml >/dev/null
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/fg_pref.xml shared_prefs/$1.xml'"
}
push_file() { # $1=目标文件名  $2=本地文件
  adb push "$2" /data/local/tmp/fg_file >/dev/null
  adb shell "run-as $PKG sh -c 'mkdir -p files && cp /data/local/tmp/fg_file files/$1'"
}

TMP=$(mktemp -d)

settings_xml() { # $1=theme_mode
cat > "$TMP/settings.xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="first_run_done" value="true" />
    <boolean name="service_running" value="true" />
    <int name="theme_mode" value="$1" />
    <string name="api_base_url">https://api.example.com/v1</string>
    <string name="model_name">demo-model</string>
    <string name="ai_custom_prompt">温柔但坚定的学长</string>
</map>
EOF
push_pref focus_guard_settings "$TMP/settings.xml"
}

appearance_xml() { # $1=background $2=glow(true/false)
cat > "$TMP/appearance.xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="background" value="$1" />
    <boolean name="glow" value="$2" />
    <int name="glow_intensity" value="70" />
    <boolean name="glow_motion" value="false" />
    <int name="card_style" value="0" />
    <int name="card_opacity" value="80" />
    <int name="corner" value="1" />
</map>
EOF
push_pref focus_guard_appearance "$TMP/appearance.xml"
}

# Dhizuku 视为「已检测过、不可用」：锁机走普通模式，且不会因未知态反复切换页面
cat > "$TMP/dhizuku.xml" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="readiness_checked" value="true" />
    <boolean name="ever_ready" value="false" />
    <boolean name="post_grant_restart_v1" value="true" />
</map>
EOF
push_pref dhizuku_enhancer "$TMP/dhizuku.xml"

# 备忘录
cat > "$TMP/memos.xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="memos">[{&quot;id&quot;:&quot;m1&quot;,&quot;text&quot;:&quot;背完 50 个四级单词&quot;,&quot;done&quot;:false,&quot;priority&quot;:2,&quot;dueAt&quot;:$((NOW_MS + 3600000)),&quot;createdAt&quot;:$NOW_MS,&quot;fromAi&quot;:false,&quot;completedAt&quot;:0},{&quot;id&quot;:&quot;m2&quot;,&quot;text&quot;:&quot;完成高数第三章习题&quot;,&quot;done&quot;:false,&quot;priority&quot;:1,&quot;dueAt&quot;:0,&quot;createdAt&quot;:$NOW_MS,&quot;fromAi&quot;:true,&quot;completedAt&quot;:0},{&quot;id&quot;:&quot;m3&quot;,&quot;text&quot;:&quot;整理今天的课堂笔记&quot;,&quot;done&quot;:false,&quot;priority&quot;:0,&quot;dueAt&quot;:0,&quot;createdAt&quot;:$NOW_MS,&quot;fromAi&quot;:false,&quot;completedAt&quot;:0}]</string>
    <string name="history">[]</string>
</map>
EOF
push_pref focus_guard_memos "$TMP/memos.xml"

# 对话历史
cat > "$TMP/chat.xml" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="messages">[{&quot;role&quot;:&quot;user&quot;,&quot;text&quot;:&quot;我总是忍不住刷短视频，怎么办&quot;,&quot;time&quot;:&quot;09:12&quot;},{&quot;role&quot;:&quot;ai&quot;,&quot;text&quot;:&quot;先给自己定一个小目标：**专注 30 分钟**，中间不碰手机。我可以帮你锁机，结束后好好休息一下。&quot;,&quot;time&quot;:&quot;09:12&quot;},{&quot;role&quot;:&quot;user&quot;,&quot;text&quot;:&quot;锁我 30 分钟&quot;,&quot;time&quot;:&quot;09:13&quot;},{&quot;role&quot;:&quot;ai&quot;,&quot;text&quot;:&quot;好的，已为你锁机 30 分钟。加油，结束后我在这里等你。&quot;,&quot;time&quot;:&quot;09:13&quot;}]</string>
</map>
EOF
push_pref focus_guard_chat_history "$TMP/chat.xml"

# 检测日志（首页统计与最近检测）
python3 - "$TMP/logs.json" "$NOW_MS" <<'PY'
import json, sys
out, now = sys.argv[1], int(sys.argv[2])
rows = [
    ("STUDY_WORK", 0.94, "正在阅读英语文章，继续保持", "NONE", "AI_VISION", "扇贝单词"),
    ("STUDY_WORK", 0.90, "在写代码，状态很好", "NONE", "SCREEN_TEXT", "VS Code"),
    ("ENTERTAINMENT", 0.92, "检测到短视频，已提醒你回到学习", "WARN", "AI_VISION", "短视频"),
    ("STUDY_WORK", 0.88, "在做数学题，专注度不错", "NONE", "AI_VISION", "作业帮"),
    ("NEUTRAL", 0.70, "在查看设置", "NONE", "APP_CATEGORY", "设置"),
    ("STUDY_WORK", 0.91, "在看网课", "NONE", "AI_VISION", "中国大学MOOC"),
]
logs = [dict(timestamp=now - i * 600000, classification=c, confidence=f, reason=r,
             action=a, source=s, appLabel=l) for i, (c, f, r, a, s, l) in enumerate(rows)]
json.dump(logs, open(out, "w"), ensure_ascii=False)
PY
push_file detection_logs.json "$TMP/logs.json"

clear_lock() {
cat > "$TMP/lock.xml" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map />
EOF
push_pref focus_guard_lock_state "$TMP/lock.xml"
}

# 写入锁机状态：$1=分钟 $2=强度 $3=暂停开关
# 单调时钟基准取设备当前 elapsedRealtime（毫秒）
set_lock() {
  local minutes="$1" strength="$2" pause="$3"
  local up_ms
  up_ms=$(adb shell cat /proc/uptime | awk '{printf "%d", $1*1000}')
  local dur=$(( minutes * 60000 ))
  # 让倒计时显示「已过去一小段」，进度环不是满圈
  local base=$(( up_ms - minutes * 60000 / 5 ))
  local now_ms=$(( $(date +%s) * 1000 ))
cat > "$TMP/lock.xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <long name="lock_elapsed_base" value="$base" />
    <long name="lock_duration_ms" value="$dur" />
    <long name="lock_wall_base" value="$(( now_ms - minutes * 60000 / 5 ))" />
    <long name="lock_until" value="$(( now_ms + dur - minutes * 60000 / 5 ))" />
    <string name="lock_source">PLAIN</string>
    <int name="lock_strength" value="$strength" />
    <boolean name="pause_enabled" value="$pause" />
    <int name="pause_quota" value="3" />
    <int name="pause_minutes" value="5" />
    <int name="pause_used" value="0" />
    <long name="snap_remaining_ms" value="0" />
</map>
EOF
  push_pref focus_guard_lock_state "$TMP/lock.xml"
}

shot() { # $1=文件名
  sleep "${2:-3}"
  if adb exec-out screencap -p > "$OUT/$1.png" && [ -s "$OUT/$1.png" ]; then
    log "截图 $1 ✓"
  else
    log "截图 $1 失败"; rm -f "$OUT/$1.png"
  fi
}

launch() { adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1 || true; }
restart() { adb shell am force-stop "$PKG"; launch; }

# 屏幕像素 → 底栏 5 个 tab 的点击坐标
read -r W H < <(adb shell wm size | tail -1 | sed 's/.*: //;s/x/ /')
TAB_Y=$(( H - H * 6 / 100 ))
tab() { adb shell input tap $(( W * (2 * $1 + 1) / 10 )) "$TAB_Y"; }

# ═════ 主界面系列：纸墨 · 深色，渐变背景 + 光晕 ═════
settings_xml 101        # 风格 0（纸墨）× 外观 1（深色）
appearance_xml 1 true
clear_lock
restart; shot home 5
tab 3; shot chat 3
tab 2; shot timer 3
tab 4; shot settings 3
# 主题设置区在设置页靠下，向上滑几次
for _ in 1 2 3 4 5 6; do adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 250; done
shot settings_theme 2

# ═════ 锁机页（普通模式）═════
lock_shot() { # $1=文件名 $2=分钟 $3=强度 $4=暂停
  adb shell am force-stop "$PKG"
  set_lock "$2" "$3" "$4"
  launch          # MainActivity 检测到锁机后自动拉起锁机界面
  shot "$1" 6
}
lock_shot lock 45 2 true
lock_shot strength4 120 4 false

# 答题界面：点击锁机页底部「答题解锁」按钮（位于下方 15% 区域）
adb shell am force-stop "$PKG"; set_lock 45 1 false; launch; sleep 6
adb shell input tap $((W/2)) $(( H - H * 12 / 100 ))
shot challenge 3

# ═════ 主题组（锁机页在不同风格下）═════
theme_lock() { # $1=文件名 $2=theme_mode $3=background
  settings_xml "$2"
  appearance_xml "$3" true
  lock_shot "$1" 45 2 true
}
theme_lock lock_ink    101 1
theme_lock lock_ocean  131 2
theme_lock lock_sakura 141 1
theme_lock lock_aurora 151 2
theme_lock lock_sunset 161 1
theme_lock lock_light  102 1

adb shell am force-stop "$PKG"
clear_lock
ls -la "$OUT"
log "完成：$(ls "$OUT" | wc -l) 张截图"
exit 0
