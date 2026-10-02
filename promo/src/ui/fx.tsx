import React from "react";
import { AbsoluteFill, interpolate, random, useCurrentFrame, useVideoConfig, Easing } from "remotion";
import { FONT_SANS, ink } from "../theme";
import { hexA } from "./kit";

/**
 * 动效工具集：粒子、逐字显现、光扫、涟漪、镜头运动、径向擦除。
 * 全部是 frame 的纯函数（不依赖组件内部状态），Remotion 并发/乱序渲染结果一致。
 */

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;
export const easeOut = Easing.bezier(0.16, 1, 0.3, 1);
export const easeInOut = Easing.bezier(0.65, 0, 0.35, 1);

/** 漂浮光尘：缓慢上浮 + 闪烁，带景深（远处小而暗、近处大而亮）。 */
export const Particles: React.FC<{
  count?: number;
  color?: string;
  seed?: string;
  speed?: number;
  /** 0→1：粒子向画面中心 (cx, cy) 汇聚的程度（收尾用）。 */
  converge?: number;
  cx?: number;
  cy?: number;
}> = ({ count = 46, color = ink.glow, seed = "p", speed = 1, converge = 0, cx = 540, cy = 860 }) => {
  const frame = useCurrentFrame();
  const { width, height } = useVideoConfig();
  return (
    <AbsoluteFill style={{ pointerEvents: "none" }}>
      {Array.from({ length: count }).map((_, i) => {
        const depth = random(`${seed}d${i}`); // 0 远 → 1 近
        const size = 3 + depth * 9;
        const x0 = random(`${seed}x${i}`) * width;
        const y0 = random(`${seed}y${i}`) * height;
        const vy = (12 + depth * 30) * speed;
        const sway = 30 + depth * 50;
        const y = ((y0 - (frame * vy) / 30) % height + height) % height;
        const x = x0 + Math.sin(frame / (40 + i) + i) * sway;
        const tw = 0.35 + 0.65 * (0.5 + 0.5 * Math.sin(frame / (9 + (i % 7)) + i * 1.7));
        const fx = x + (cx - x) * converge;
        const fy = y + (cy - y) * converge;
        return (
          <div
            key={i}
            style={{
              position: "absolute",
              left: fx,
              top: fy,
              width: size,
              height: size,
              borderRadius: "50%",
              background: color,
              opacity: (0.18 + depth * 0.55) * tw * (1 - converge * 0.3),
              boxShadow: `0 0 ${size * 3}px ${color}`,
              filter: depth < 0.35 ? "blur(2px)" : undefined,
            }}
          />
        );
      })}
    </AbsoluteFill>
  );
};

/**
 * 逐字显现：每个字从下方、模糊、透明 → 清晰到位，带轻微回弹。
 * stagger 为相邻字的间隔帧。
 */
export const RevealText: React.FC<{
  text: string;
  delay?: number;
  stagger?: number;
  style?: React.CSSProperties;
  dur?: number;
}> = ({ text, delay = 0, stagger = 2.2, style, dur = 16 }) => {
  const frame = useCurrentFrame();
  return (
    <span style={{ display: "inline-flex", flexWrap: "wrap", justifyContent: "center", ...style }}>
      {Array.from(text).map((ch, i) => {
        const k = interpolate(frame - delay - i * stagger, [0, dur], [0, 1], { ...clamp, easing: easeOut });
        return (
          <span
            key={i}
            style={{
              display: "inline-block",
              whiteSpace: "pre",
              opacity: k,
              transform: `translateY(${(1 - k) * 0.55}em) scale(${0.9 + 0.1 * k})`,
              filter: `blur(${(1 - k) * 10}px)`,
            }}
          >
            {ch}
          </span>
        );
      })}
    </span>
  );
};

/** 电影感字幕：逐字显现 + 底部光线描边，替代整行淡入。 */
export const Title: React.FC<{ text: string; sub?: string; delay?: number }> = ({ text, sub, delay = 6 }) => {
  const frame = useCurrentFrame();
  const line = interpolate(frame - delay, [0, 22], [0, 1], { ...clamp, easing: easeOut });
  const subK = interpolate(frame - delay - Array.from(text).length * 2.2 - 6, [0, 16], [0, 1], { ...clamp, easing: easeOut });
  return (
    <div
      style={{
        position: "absolute",
        left: 0,
        right: 0,
        bottom: 290,
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        fontFamily: FONT_SANS,
      }}
    >
      <RevealText
        text={text}
        delay={delay}
        style={{
          fontWeight: 900,
          fontSize: 70,
          color: "#fff",
          letterSpacing: 3,
          textShadow: "0 6px 30px rgba(0,0,0,0.6), 0 0 40px rgba(0,0,0,0.35)",
          maxWidth: 960,
        }}
      />
      <div
        style={{
          marginTop: 22,
          height: 4,
          width: 520 * line,
          borderRadius: 4,
          background: `linear-gradient(90deg, transparent, ${ink.accent}, transparent)`,
          boxShadow: `0 0 24px ${ink.accent}`,
        }}
      />
      {sub && (
        <div
          style={{
            marginTop: 22,
            fontSize: 38,
            color: "rgba(255,255,255,0.86)",
            opacity: subK,
            transform: `translateY(${(1 - subK) * 16}px)`,
            textShadow: "0 4px 18px rgba(0,0,0,0.6)",
          }}
        >
          {sub}
        </div>
      )}
      {/* 字幕区底部暗角，保证任何背景上都可读 */}
      <div
        style={{
          position: "absolute",
          zIndex: -1,
          left: -100,
          right: -100,
          top: -140,
          bottom: -160,
          background: "radial-gradient(ellipse at center, rgba(5,8,14,0.55) 0%, rgba(5,8,14,0) 70%)",
        }}
      />
    </div>
  );
};

/** 光扫：一道斜向高光从左到右扫过（叠加在手机屏幕/Logo 上）。 */
export const Sheen: React.FC<{ at: number; dur?: number; angle?: number; strength?: number }> = ({
  at,
  dur = 26,
  angle = 115,
  strength = 0.35,
}) => {
  const frame = useCurrentFrame();
  const k = interpolate(frame - at, [0, dur], [-0.4, 1.4], { ...clamp, easing: easeInOut });
  if (frame < at || frame > at + dur) return null;
  return (
    <AbsoluteFill
      style={{
        pointerEvents: "none",
        background: `linear-gradient(${angle}deg, transparent ${k * 100 - 18}%, rgba(255,255,255,${strength}) ${k * 100}%, transparent ${k * 100 + 18}%)`,
        mixBlendMode: "screen",
      }}
    />
  );
};

/** 涟漪：从中心扩散的 n 道光环（锁机「落锁」、强调时刻用）。 */
export const Ripple: React.FC<{ at: number; color?: string; cx?: number; cy?: number; rings?: number; max?: number }> = ({
  at,
  color = ink.accent,
  cx = 540,
  cy = 960,
  rings = 3,
  max = 1300,
}) => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{ pointerEvents: "none" }}>
      {Array.from({ length: rings }).map((_, i) => {
        const k = interpolate(frame - at - i * 7, [0, 42], [0, 1], { ...clamp, easing: easeOut });
        if (k <= 0 || k >= 1) return null;
        const r = max * k;
        return (
          <div
            key={i}
            style={{
              position: "absolute",
              left: cx - r,
              top: cy - r,
              width: r * 2,
              height: r * 2,
              borderRadius: "50%",
              border: `${6 * (1 - k) + 1}px solid ${color}`,
              opacity: (1 - k) * 0.8,
              boxShadow: `0 0 ${60 * (1 - k)}px ${color}, inset 0 0 ${40 * (1 - k)}px ${color}`,
            }}
          />
        );
      })}
    </AbsoluteFill>
  );
};

/** 光爆：一次性的径向闪光（Logo 成形瞬间）。 */
export const Flash: React.FC<{ at: number; color?: string; cx?: number; cy?: number; size?: number }> = ({
  at,
  color = ink.glow,
  cx = 540,
  cy = 900,
  size = 1600,
}) => {
  const frame = useCurrentFrame();
  const k = interpolate(frame - at, [0, 4, 30], [0, 1, 0], { ...clamp });
  if (k <= 0) return null;
  return (
    <div
      style={{
        position: "absolute",
        left: cx - size / 2,
        top: cy - size / 2,
        width: size,
        height: size,
        borderRadius: "50%",
        background: `radial-gradient(circle, ${color}${hexA(0.85 * k)} 0%, ${color}${hexA(0.25 * k)} 22%, transparent 60%)`,
        pointerEvents: "none",
      }}
    />
  );
};

/**
 * 镜头：缓慢推近 + 轻微漂移 + 呼吸，让每个画面都有「在动」的质感。
 * push 为整段推近比例，drift 为漂移幅度（px）。
 */
export const Camera: React.FC<{ children: React.ReactNode; push?: number; drift?: number; seed?: number }> = ({
  children,
  push = 0.06,
  drift = 18,
  seed = 0,
}) => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();
  const k = interpolate(frame, [0, durationInFrames], [0, 1], { ...clamp, easing: easeInOut });
  const s = 1 + push * k;
  const x = Math.sin(frame / 70 + seed) * drift;
  const y = Math.cos(frame / 85 + seed) * drift * 0.6;
  return (
    <AbsoluteFill style={{ transform: `translate(${x}px, ${y}px) scale(${s})`, transformOrigin: "50% 45%" }}>
      {children}
    </AbsoluteFill>
  );
};

/** 3D 悬浮：手机缓慢摇摆、上下浮动（视差）。 */
export const Float: React.FC<{ children: React.ReactNode; amp?: number; tilt?: number; seed?: number }> = ({
  children,
  amp = 14,
  tilt = 5,
  seed = 0,
}) => {
  const frame = useCurrentFrame();
  const y = Math.sin(frame / 24 + seed) * amp;
  const ry = Math.sin(frame / 52 + seed) * tilt;
  const rx = Math.cos(frame / 61 + seed) * tilt * 0.5;
  return (
    <div style={{ transform: `perspective(2600px) translateY(${y}px) rotateY(${ry}deg) rotateX(${rx}deg)`, transformStyle: "preserve-3d" }}>
      {children}
    </div>
  );
};

/** 径向擦除：新配色从中心圆形扩散铺满（主题快切用）。 */
export const RadialWipe: React.FC<{ progress: number; color: string; cx?: number; cy?: number }> = ({
  progress,
  color,
  cx = 50,
  cy = 45,
}) => (
  <AbsoluteFill
    style={{
      pointerEvents: "none",
      background: color,
      clipPath: `circle(${progress * 150}% at ${cx}% ${cy}%)`,
    }}
  />
);

/** 胶片暗角 + 极细颗粒，统一全片质感。 */
export const Grade: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{ pointerEvents: "none" }}>
      <AbsoluteFill style={{ background: "radial-gradient(ellipse at 50% 45%, transparent 55%, rgba(0,0,0,0.42) 100%)" }} />
      <AbsoluteFill
        style={{
          opacity: 0.05,
          backgroundImage:
            "url(\"data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='160' height='160'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2' stitchTiles='stitch'/></filter><rect width='100%' height='100%' filter='url(%23n)'/></svg>\")",
          backgroundPosition: `${(frame * 37) % 160}px ${(frame * 53) % 160}px`,
          mixBlendMode: "overlay",
        }}
      />
    </AbsoluteFill>
  );
};
