import React from "react";
import { AbsoluteFill, Img, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { FONT_SANS, FONT_SERIF, ink, Palette } from "../theme";

/** 弹簧入场进度 0→1（delay 为帧）。 */
export const useIn = (delay = 0, damping = 14) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  return spring({ frame: frame - delay, fps, config: { damping, mass: 0.8 } });
};

/** 背景：主题渐变 + 两团流光（与应用 AppBackground 同构）。 */
export const Backdrop: React.FC<{ p?: Palette; drift?: number; intensity?: number }> = ({
  p = ink,
  drift,
  intensity = 1,
}) => {
  const frame = useCurrentFrame();
  const d = drift ?? (Math.sin(frame / 90) + 1) / 2;
  return (
    <AbsoluteFill style={{ background: `linear-gradient(180deg, ${p.surface} 0%, ${p.bg} 55%, ${p.bg} 100%)` }}>
      <div
        style={{
          position: "absolute",
          width: 1300,
          height: 1300,
          left: 380 + 120 * d,
          top: -520 + 160 * d,
          borderRadius: "50%",
          background: `radial-gradient(circle, ${p.accent}${hexA(0.42 * intensity)} 0%, ${p.accent}00 62%)`,
        }}
      />
      <div
        style={{
          position: "absolute",
          width: 1200,
          height: 1200,
          left: -560 - 90 * d,
          top: 1050 - 180 * d,
          borderRadius: "50%",
          background: `radial-gradient(circle, ${p.glow}${hexA(0.32 * intensity)} 0%, ${p.glow}00 62%)`,
        }}
      />
    </AbsoluteFill>
  );
};

export const hexA = (a: number) =>
  Math.round(Math.max(0, Math.min(1, a)) * 255).toString(16).padStart(2, "0");

/** 下三分之一字幕：大号加粗白字 + 半透明底条，逐字淡入。 */
export const Caption: React.FC<{ text: string; sub?: string; delay?: number }> = ({ text, sub, delay = 6 }) => {
  const s = useIn(delay, 16);
  return (
    <div
      style={{
        position: "absolute",
        left: 0,
        right: 0,
        bottom: 300,
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        opacity: s,
        transform: `translateY(${(1 - s) * 36}px)`,
      }}
    >
      <div
        style={{
          fontFamily: FONT_SANS,
          fontWeight: 800,
          fontSize: 64,
          color: "#fff",
          padding: "18px 40px",
          borderRadius: 28,
          background: "rgba(10,14,22,0.55)",
          backdropFilter: "blur(12px)",
          letterSpacing: 2,
          textShadow: "0 4px 24px rgba(0,0,0,0.45)",
          maxWidth: 940,
          textAlign: "center",
        }}
      >
        {text}
      </div>
      {sub && (
        <div style={{ fontFamily: FONT_SANS, fontSize: 36, color: "rgba(255,255,255,0.82)", marginTop: 18 }}>{sub}</div>
      )}
    </div>
  );
};

/** 手机外框：圆角机身 + 柔和投影 + 屏幕反光。children 为屏幕内容。 */
export const Phone: React.FC<{
  children: React.ReactNode;
  width?: number;
  tilt?: number;
  glow?: string;
  style?: React.CSSProperties;
}> = ({ children, width = 760, tilt = 0, glow = ink.accent, style }) => {
  const height = width * 2.1;
  return (
    <div
      style={{
        width,
        height,
        borderRadius: width * 0.12,
        padding: width * 0.028,
        background: "linear-gradient(145deg, #2a3242, #0b0f17)",
        boxShadow: `0 60px 140px rgba(0,0,0,0.55), 0 0 120px ${glow}33, inset 0 0 0 2px rgba(255,255,255,0.08)`,
        transform: `perspective(2400px) rotateY(${tilt}deg)`,
        ...style,
      }}
    >
      <div
        style={{
          position: "relative",
          width: "100%",
          height: "100%",
          borderRadius: width * 0.095,
          overflow: "hidden",
          background: ink.bg,
        }}
      >
        {children}
        <div
          style={{
            position: "absolute",
            inset: 0,
            background: "linear-gradient(120deg, rgba(255,255,255,0.10) 0%, rgba(255,255,255,0) 32%)",
            pointerEvents: "none",
          }}
        />
      </div>
    </div>
  );
};

/** 截图铺满屏幕；无截图时渲染 fallback（网页还原界面）。 */
export const Screen: React.FC<{ src: string | null; fallback: React.ReactNode }> = ({ src, fallback }) =>
  src ? <Img src={src} style={{ width: "100%", height: "100%", objectFit: "cover" }} /> : <>{fallback}</>;

/** 盾牌图标（取自 res/drawable/ic_shield.xml 的 path），支持描边绘制动画。 */
export const Shield: React.FC<{ size: number; color: string; draw?: number; fill?: number }> = ({
  size,
  color,
  draw = 1,
  fill = 1,
}) => {
  const len = 80;
  return (
    <svg width={size} height={size} viewBox="0 0 24 24">
      <path
        d="M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5L12,1z"
        fill={color}
        fillOpacity={0.18 * fill}
        stroke={color}
        strokeWidth={0.9}
        strokeLinejoin="round"
        strokeDasharray={len}
        strokeDashoffset={len * (1 - draw)}
      />
      <path
        d="M12,11.99h7c-0.53,4.12 -3.28,7.79 -7,8.94V12H5V6.3l7,-3.11v8.8z"
        fill={color}
        opacity={fill}
      />
    </svg>
  );
};

/**
 * 倒计时环（与应用 CountdownRing 同构：底轨 + 外发光 + 渐变进度弧 + 衬线数字）。
 * [roll] 为当前秒内的进度 0→1：秒位在每秒开头 6 帧内自下滑入。
 * 纯函数式（只依赖参数），Remotion 乱序/并发渲染帧时结果一致。
 */
export const Ring: React.FC<{
  size: number;
  progress: number;
  seconds: number;
  roll?: number;
  p?: Palette;
  label?: string;
}> = ({ size, progress, seconds, roll = 1, p = ink, label = "锁定剩余" }) => {
  const stroke = size * 0.05;
  const r = size / 2 - stroke * 1.4;
  const c = 2 * Math.PI * r;
  const m = Math.floor(seconds / 60);
  const s = Math.floor(seconds % 60);
  const txt = `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
  const prevSec = s === 59 ? 0 : s + 1;
  const k = Math.min(1, roll * 5); // 前 20% 的秒内完成滚动
  return (
    <div style={{ position: "relative", width: size, height: size }}>
      <svg width={size} height={size} style={{ position: "absolute", transform: "rotate(-90deg)" }}>
        <defs>
          <linearGradient id="ringG" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor={p.accent} stopOpacity={0.55} />
            <stop offset="100%" stopColor={p.accent} />
          </linearGradient>
        </defs>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={p.line} strokeOpacity={0.55} strokeWidth={stroke} />
        <circle
          cx={size / 2} cy={size / 2} r={r} fill="none" stroke={p.accent} strokeOpacity={0.18}
          strokeWidth={stroke * 2.2} strokeLinecap="round" strokeDasharray={`${c * progress} ${c}`}
        />
        <circle
          cx={size / 2} cy={size / 2} r={r} fill="none" stroke="url(#ringG)" strokeWidth={stroke}
          strokeLinecap="round" strokeDasharray={`${c * progress} ${c}`}
        />
      </svg>
      <div
        style={{
          position: "absolute", inset: 0, display: "flex", flexDirection: "column",
          alignItems: "center", justifyContent: "center",
        }}
      >
        <div style={{ fontFamily: FONT_SANS, fontSize: size * 0.045, letterSpacing: 6, color: p.haze }}>{label}</div>
        <div style={{ display: "flex", fontFamily: FONT_SERIF, fontWeight: 700, fontSize: size * 0.22, color: p.text }}>
          {txt.split("").map((ch, i) => {
            // 仅秒的个位每秒滚动；其余位静止
            const rolling = i === txt.length - 1;
            const prev = String(prevSec % 10);
            return (
              <span
                key={i}
                style={{
                  position: "relative", display: "inline-block", overflow: "hidden",
                  height: size * 0.27, lineHeight: `${size * 0.27}px`,
                }}
              >
                {rolling && k < 1 && (
                  <span style={{ position: "absolute", left: 0, transform: `translateY(${-k * 100}%)`, opacity: 1 - k }}>
                    {prev}
                  </span>
                )}
                <span
                  style={{
                    display: "inline-block",
                    transform: rolling ? `translateY(${(1 - k) * 100}%)` : undefined,
                    opacity: rolling ? k : 1,
                  }}
                >
                  {ch}
                </span>
              </span>
            );
          })}
        </div>
      </div>
    </div>
  );
};

/** 状态胶囊（与应用 StatusPill 同构，可带模式徽章）。 */
export const Pill: React.FC<{ text: string; badge?: string; p?: Palette; scale?: number }> = ({
  text,
  badge,
  p = ink,
  scale = 1,
}) => (
  <div
    style={{
      display: "inline-flex", alignItems: "center", gap: 14 * scale,
      padding: `${14 * scale}px ${28 * scale}px`, borderRadius: 999,
      background: `${p.accent}29`, border: `2px solid ${p.accent}73`,
      fontFamily: FONT_SANS, fontSize: 30 * scale, fontWeight: 700, color: p.text,
    }}
  >
    <span style={{ color: p.accent }}>🔒</span>
    {text}
    {badge && (
      <span
        style={{
          fontSize: 22 * scale, padding: `${4 * scale}px ${14 * scale}px`, borderRadius: 16,
          background: `${p.card}D9`, border: `1.5px solid ${p.line}`, color: p.accent,
        }}
      >
        {badge}
      </span>
    )}
  </div>
);

/** 玻璃卡片。 */
export const Card: React.FC<{ children: React.ReactNode; p?: Palette; style?: React.CSSProperties }> = ({
  children,
  p = ink,
  style,
}) => (
  <div
    style={{
      background: `${p.card}CC`, border: `2px solid ${p.line}99`, borderRadius: 36,
      padding: 36, fontFamily: FONT_SANS, color: p.text, ...style,
    }}
  >
    {children}
  </div>
);
