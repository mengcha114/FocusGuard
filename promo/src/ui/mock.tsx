import React from "react";
import { AbsoluteFill } from "remotion";
import { FONT_SANS, FONT_SERIF, ink, Palette } from "../theme";
import { Backdrop, Card, Pill, Ring, Shield } from "./kit";

/**
 * 网页还原的应用界面（截图缺失时的兜底），尺寸按 1080×2270 的手机屏幕设计，
 * 由 Phone 外框按比例缩放。结构与应用中的 Compose 界面一致。
 */
const SCREEN_W = 1080;
const SCREEN_H = 2270;

export const Scaled: React.FC<{ children: React.ReactNode; width: number }> = ({ children, width }) => {
  const k = width / SCREEN_W;
  return (
    <div style={{ width: SCREEN_W, height: SCREEN_H, transform: `scale(${k})`, transformOrigin: "top left", position: "absolute" }}>
      {children}
    </div>
  );
};

const StatusBar: React.FC<{ p: Palette }> = ({ p }) => (
  <div
    style={{
      height: 90, display: "flex", alignItems: "center", justifyContent: "space-between",
      padding: "0 60px", fontFamily: FONT_SANS, fontSize: 34, color: p.text, opacity: 0.85,
    }}
  >
    <span>09:30</span>
    <span>● ● ▮ 86%</span>
  </div>
);

/** 锁机页（普通模式 / 系统级）。 */
export const MockLock: React.FC<{
  p?: Palette;
  seconds: number;
  progress: number;
  roll?: number;
  strength?: number;
  badge?: string;
}> = ({ p = ink, seconds, progress, roll, strength = 2, badge = "🔒 普通模式守护" }) => (
  <AbsoluteFill style={{ fontFamily: FONT_SANS, color: p.text }}>
    <Backdrop p={p} />
    <StatusBar p={p} />
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center", paddingTop: 60 }}>
      <div style={{ fontSize: 120, fontWeight: 300, letterSpacing: 6 }}>09:30</div>
      <div style={{ fontSize: 36, color: p.haze, marginTop: 6 }}>10月2日 星期四</div>
      <div style={{ marginTop: 44 }}>
        <Pill text={strength === 4 ? "专注锁定中 · 不可解锁" : "专注锁定中"} badge={badge} p={p} scale={1.3} />
      </div>
      <div style={{ marginTop: 90 }}>
        <Ring size={720} progress={progress} seconds={seconds} roll={roll} p={p} />
      </div>
      <Card p={p} style={{ width: 900, marginTop: 90 }}>
        <div style={{ fontSize: 26, letterSpacing: 6, color: p.haze }}>今日箴言</div>
        <div style={{ fontSize: 42, lineHeight: 1.5, marginTop: 14 }}>专注是最好的自律，坚持是最酷的自己。</div>
      </Card>
      <div style={{ marginTop: 70, width: 900, display: "flex", gap: 30 }}>
        {strength === 4 ? (
          <div
            style={{
              flex: 1, height: 150, borderRadius: 40, border: `3px solid ${p.error}88`,
              display: "flex", alignItems: "center", justifyContent: "center", fontSize: 44, color: p.error,
            }}
          >
            不可解锁 · 只能等时间结束
          </div>
        ) : (
          <>
            <div
              style={{
                flex: 1, height: 150, borderRadius: 40, border: `3px solid ${p.line}`,
                display: "flex", alignItems: "center", justifyContent: "center", fontSize: 44, color: p.haze,
              }}
            >
              暂停（答题）
            </div>
            <div
              style={{
                flex: 1.4, height: 150, borderRadius: 40, background: p.accent, color: p.bg,
                display: "flex", alignItems: "center", justifyContent: "center", fontSize: 48, fontWeight: 800,
              }}
            >
              答题解锁
            </div>
          </>
        )}
      </div>
    </div>
  </AbsoluteFill>
);

/** 首页：问候 + 专注指数环 + 统计 + 最近检测。 */
export const MockHome: React.FC<{ p?: Palette; score?: number }> = ({ p = ink, score = 86 }) => {
  const r = 120;
  const c = 2 * Math.PI * r;
  const rows = [
    ["学习/工作", "正在阅读英语文章，继续保持", p.success],
    ["学习/工作", "在写代码，状态很好", p.success],
    ["娱乐", "检测到短视频，已提醒你回到学习", p.error],
    ["学习/工作", "在做数学题，专注度不错", p.success],
  ] as const;
  return (
    <AbsoluteFill style={{ fontFamily: FONT_SANS, color: p.text }}>
      <Backdrop p={p} />
      <StatusBar p={p} />
      <div style={{ padding: "30px 56px" }}>
        <div style={{ fontSize: 36, color: p.haze }}>早上好，今天也专注一点</div>
        <div style={{ fontSize: 84, fontWeight: 800 }}>专注卫士</div>
        <Card p={p} style={{ marginTop: 40, display: "flex", alignItems: "center", gap: 50 }}>
          <svg width={280} height={280} style={{ transform: "rotate(-90deg)" }}>
            <circle cx={140} cy={140} r={r} fill="none" stroke={p.line} strokeWidth={24} />
            <circle
              cx={140} cy={140} r={r} fill="none" stroke={p.accent} strokeWidth={24} strokeLinecap="round"
              strokeDasharray={`${(c * score) / 100} ${c}`}
            />
          </svg>
          <div>
            <div style={{ fontSize: 56, fontWeight: 800 }}>
              <span style={{ color: p.success }}>●</span> 守护中
            </div>
            <div style={{ fontSize: 34, color: p.haze, marginTop: 10 }}>AI 正在关注你的专注状态</div>
            <div style={{ fontFamily: FONT_SERIF, fontSize: 90, fontWeight: 700, marginTop: 10 }}>
              {score}
              <span style={{ fontSize: 36, color: p.haze }}> 专注</span>
            </div>
          </div>
        </Card>
        <div style={{ display: "flex", gap: 28, marginTop: 34 }}>
          {[
            ["今日检测", "36", p.accent],
            ["专注指数", "86", p.glow],
            ["违规", "2", p.error],
          ].map(([t, v, col]) => (
            <Card key={t} p={p} style={{ flex: 1, padding: 30 }}>
              <div style={{ width: 70, height: 70, borderRadius: 22, background: `${col}29` }} />
              <div style={{ fontSize: 64, fontWeight: 800, marginTop: 18 }}>{v}</div>
              <div style={{ fontSize: 30, color: p.haze }}>{t}</div>
            </Card>
          ))}
        </div>
        <div style={{ fontSize: 44, fontWeight: 700, marginTop: 44 }}>最近检测</div>
        {rows.map(([k, v, col], i) => (
          <Card key={i} p={p} style={{ marginTop: 22, padding: 30, display: "flex", gap: 24, alignItems: "center" }}>
            <div style={{ fontSize: 30, padding: "8px 20px", borderRadius: 16, background: `${col}29`, color: col }}>{k}</div>
            <div style={{ fontSize: 34 }}>{v}</div>
          </Card>
        ))}
      </div>
    </AbsoluteFill>
  );
};

/** AI 对话页。 */
export const MockChat: React.FC<{ p?: Palette; highlight?: number }> = ({ p = ink, highlight = 0 }) => {
  const msgs: [boolean, string][] = [
    [true, "我总是忍不住刷短视频，怎么办"],
    [false, "先给自己定一个小目标：专注 30 分钟，中间不碰手机。我可以帮你锁机。"],
    [true, "锁我 30 分钟"],
    [false, "好的，已为你锁机 30 分钟。加油，结束后我在这里等你。"],
  ];
  return (
    <AbsoluteFill style={{ fontFamily: FONT_SANS, color: p.text }}>
      <Backdrop p={p} />
      <StatusBar p={p} />
      <div style={{ padding: "30px 56px" }}>
        <div style={{ fontSize: 80, fontWeight: 800 }}>AI 对话</div>
        <div style={{ fontSize: 32, color: p.haze }}>与 AI 聊天 · 检测提醒也会出现在这里</div>
        <div style={{ marginTop: 60, display: "flex", flexDirection: "column", gap: 36 }}>
          {msgs.map(([user, t], i) => (
            <div key={i} style={{ display: "flex", justifyContent: user ? "flex-end" : "flex-start" }}>
              <div
                style={{
                  maxWidth: 760, fontSize: 40, lineHeight: 1.5, padding: "28px 36px",
                  borderRadius: user ? "40px 40px 10px 40px" : "40px 40px 40px 10px",
                  background: user ? p.accent : `${p.card}E6`, color: user ? p.bg : p.text,
                  border: user ? undefined : `2px solid ${p.line}99`,
                  boxShadow: i === 2 ? `0 0 ${60 * highlight}px ${p.accent}` : undefined,
                  transform: i === 2 ? `scale(${1 + 0.06 * highlight})` : undefined,
                }}
              >
                {t}
              </div>
            </div>
          ))}
        </div>
      </div>
    </AbsoluteFill>
  );
};

/** 答题页：大数运算 + 自绘键盘。 */
export const MockChallenge: React.FC<{ p?: Palette; typed: string; correct: number; pressed?: string }> = ({
  p = ink,
  typed,
  correct,
  pressed,
}) => {
  const keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓"];
  return (
    <AbsoluteFill style={{ fontFamily: FONT_SANS, color: p.text }}>
      <Backdrop p={p} />
      <StatusBar p={p} />
      <div style={{ padding: "60px 60px", display: "flex", flexDirection: "column", alignItems: "center" }}>
        <div style={{ fontSize: 40, color: p.haze }}>答题解锁</div>
        <div style={{ fontFamily: FONT_SERIF, fontSize: 110, fontWeight: 700, color: p.accent, marginTop: 10 }}>
          {correct} / 5
        </div>
        <Card p={p} style={{ width: 940, marginTop: 50, textAlign: "center" }}>
          <div style={{ fontSize: 32, color: p.haze }}>计算</div>
          <div style={{ fontFamily: FONT_SERIF, fontSize: 70, fontWeight: 700, marginTop: 16 }}>4827 × 365 = ?</div>
        </Card>
        <div
          style={{
            width: 940, height: 150, marginTop: 40, borderRadius: 36, border: `3px solid ${p.accent}`,
            display: "flex", alignItems: "center", justifyContent: "center",
            fontFamily: FONT_SERIF, fontSize: 80, letterSpacing: 6, color: typed ? p.text : p.faint,
          }}
        >
          {typed || "请输入答案"}
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 280px)", gap: 30, marginTop: 60 }}>
          {keys.map((k) => (
            <div
              key={k}
              style={{
                height: 170, borderRadius: 40, display: "flex", alignItems: "center", justifyContent: "center",
                fontSize: 64, fontWeight: 700,
                background: k === "✓" ? p.accent : pressed === k ? `${p.accent}55` : `${p.card}E6`,
                color: k === "✓" ? p.bg : p.text,
                border: `2px solid ${p.line}99`,
                transform: pressed === k ? "scale(0.94)" : undefined,
              }}
            >
              {k}
            </div>
          ))}
        </div>
      </div>
    </AbsoluteFill>
  );
};

/** 锁机配置页（番茄钟）。 */
export const MockTimer: React.FC<{ p?: Palette }> = ({ p = ink }) => (
  <AbsoluteFill style={{ fontFamily: FONT_SANS, color: p.text }}>
    <Backdrop p={p} />
    <StatusBar p={p} />
    <div style={{ padding: "30px 56px" }}>
      <div style={{ fontSize: 80, fontWeight: 800 }}>强制锁机</div>
      <div style={{ fontSize: 32, color: p.haze }}>防护等级 · 普通模式</div>
      <Card p={p} style={{ marginTop: 50 }}>
        <div style={{ fontSize: 46, fontWeight: 700 }}>锁机模式</div>
        <div style={{ display: "flex", gap: 24, marginTop: 30 }}>
          {["持续锁机", "番茄钟"].map((t, i) => (
            <div
              key={t}
              style={{
                flex: 1, padding: 34, borderRadius: 32, textAlign: "center", fontSize: 42, fontWeight: 700,
                border: `3px solid ${i === 1 ? p.accent : p.line}`, background: i === 1 ? `${p.accent}29` : "transparent",
                color: i === 1 ? p.accent : p.text,
              }}
            >
              {t}
            </div>
          ))}
        </div>
        <div style={{ fontSize: 34, color: p.haze, marginTop: 34 }}>专注 / 休息</div>
        <div style={{ display: "flex", gap: 20, marginTop: 20 }}>
          {["25 / 5", "50 / 10", "90 / 20"].map((t, i) => (
            <div
              key={t}
              style={{
                flex: 1, padding: 28, borderRadius: 28, textAlign: "center", fontSize: 40, fontWeight: 700,
                border: `3px solid ${i === 1 ? p.accent : p.line}`, background: i === 1 ? `${p.accent}29` : "transparent",
                color: i === 1 ? p.accent : p.text,
              }}
            >
              {t}
            </div>
          ))}
        </div>
        <div style={{ fontSize: 32, color: p.haze, marginTop: 30 }}>共 200 分钟专注 + 40 分钟休息，阶段切换时会通知你</div>
      </Card>
      <div
        style={{
          marginTop: 60, height: 160, borderRadius: 44, background: p.accent, color: p.bg,
          display: "flex", alignItems: "center", justifyContent: "center", fontSize: 50, fontWeight: 800,
        }}
      >
        🔒 开始番茄钟 · 4 轮
      </div>
    </div>
  </AbsoluteFill>
);

export { Shield };
