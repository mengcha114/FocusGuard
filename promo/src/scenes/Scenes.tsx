import React from "react";
import { AbsoluteFill, interpolate, Img, useCurrentFrame, useVideoConfig, spring, Easing } from "remotion";
import { FONT_SANS, FONT_SERIF, ink, styles } from "../theme";
import { shot } from "../data";
import { Backdrop, Card, Phone, Screen, Shield, useIn } from "../ui/kit";
import { Camera, easeOut, Flash, Float, Particles, RadialWipe, Ripple, RevealText, Sheen, Title as Caption } from "../ui/fx";
import { MockChallenge, MockChat, MockHome, MockLock, MockTimer, Scaled } from "../ui/mock";

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;

/** 手机中的屏幕：优先真实截图，缺失时用网页还原界面。 */
const PhoneScreen: React.FC<{ name: string; width: number; fallback: React.ReactNode; tilt?: number; glow?: string }> = ({
  name,
  width,
  fallback,
  tilt,
  glow,
}) => {
  const inner = width * (1 - 0.056);
  return (
    <Phone width={width} tilt={tilt} glow={glow}>
      <Screen src={shot(name)} fallback={<Scaled width={inner}>{fallback}</Scaled>} />
    </Phone>
  );
};

// ═══ 0–4s 开场 ═══
export const Opening: React.FC = () => {
  const frame = useCurrentFrame();
  const draw = interpolate(frame, [6, 60], [0, 1], { ...clamp, easing: Easing.out(Easing.cubic) });
  const fill = interpolate(frame, [50, 80], [0, 1], clamp);
  const pulse = 1 + 0.04 * Math.sin(frame / 8);
  return (
    <AbsoluteFill>
      <Backdrop intensity={interpolate(frame, [0, 40], [0.2, 1], clamp)} />
      <Particles seed="open" speed={0.6} />
      <Camera push={0.1} drift={10}>
        <AbsoluteFill style={{ alignItems: "center", justifyContent: "center" }}>
          <div style={{ transform: `scale(${pulse})`, filter: `drop-shadow(0 0 ${60 * fill}px ${ink.accent})` }}>
            <Shield size={340} color={ink.accent} draw={draw} fill={fill} />
          </div>
        </AbsoluteFill>
        <Flash at={58} cy={960} size={1400} />
      </Camera>
      <Caption text="你又在刷手机了吗？" delay={30} />
    </AbsoluteFill>
  );
};

// ═══ 4–9s 痛点 ═══
const APPS = ["短视频", "游戏", "直播", "刷剧", "社交", "购物"];
export const Pain: React.FC = () => {
  const frame = useCurrentFrame();
  const minutes = Math.floor(interpolate(frame, [0, 140], [3, 68], clamp));
  return (
    <AbsoluteFill>
      <Backdrop p={{ ...ink, accent: ink.error, glow: "#FB7185" }} />
      <Particles seed="pain" color="#FB7185" count={30} speed={1.6} />
      <AbsoluteFill style={{ alignItems: "center", paddingTop: 260 }}>
        <div style={{ fontFamily: FONT_SANS, fontSize: 44, color: ink.haze }}>今日屏幕使用</div>
        <div
          style={{
            fontFamily: FONT_SERIF, fontWeight: 700, fontSize: 260, lineHeight: 1.1,
            color: minutes > 45 ? ink.error : ink.text,
            transform: `translateX(${minutes > 45 ? Math.sin(frame * 2.3) * 4 : 0}px)`,
            textShadow: minutes > 45 ? `0 0 60px ${ink.error}88` : undefined,
          }}
        >
          {Math.floor(minutes / 60)}:{String(minutes % 60).padStart(2, "0")}
        </div>
      </AbsoluteFill>
      {APPS.map((name, i) => {
        const s = spring({ frame: frame - 8 - i * 9, fps: 30, config: { damping: 11 } });
        const suck = interpolate(frame, [118, 150], [0, 1], { ...clamp, easing: Easing.in(Easing.cubic) });
        const x0 = (i % 3) * 300 + 90;
        const y0 = 900 + Math.floor(i / 3) * 280;
        const x = x0 + (410 - x0) * suck;
        const y = y0 + (850 - y0) * suck;
        const rot = ((i * 37) % 21) - 10 + suck * 180;
        return (
          <div
            key={name}
            style={{
              position: "absolute", left: x, top: y, width: 260, height: 220, borderRadius: 48,
              background: `linear-gradient(145deg, ${ink.card}, ${ink.surface})`, border: `2px solid ${ink.error}66`,
              display: "flex", alignItems: "center", justifyContent: "center",
              fontFamily: FONT_SANS, fontSize: 52, fontWeight: 800, color: ink.text,
              transform: `scale(${s * (1 - 0.85 * suck)}) rotate(${rot * s}deg)`, opacity: s * (1 - suck),
              boxShadow: `0 30px 80px rgba(0,0,0,0.45), 0 0 ${40 * Math.abs(Math.sin(frame / 5 + i))}px ${ink.error}55`,
            }}
          >
            {name}
          </div>
        );
      })}
      <Caption text="一刷就是一小时" delay={40} />
    </AbsoluteFill>
  );
};

// ═══ 9–13s 品牌 ═══
export const Brand: React.FC = () => {
  const frame = useCurrentFrame();
  const s = useIn(4, 12);
  return (
    <AbsoluteFill>
      <Backdrop intensity={1.2} />
      <Particles seed="brand" />
      <Flash at={2} cy={840} />
      <Ripple at={4} cy={840} rings={2} max={900} />
      <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column" }}>
        <div style={{ position: "relative", transform: `scale(${0.6 + 0.4 * s})`, opacity: s, filter: `drop-shadow(0 0 70px ${ink.accent})` }}>
          <Shield size={260} color={ink.accent} />
        </div>
        <div style={{ position: "relative", marginTop: 40, overflow: "hidden" }}>
          <RevealText
            text="专注卫士"
            delay={12}
            stagger={4}
            dur={20}
            style={{ fontFamily: FONT_SANS, fontWeight: 900, fontSize: 150, color: ink.text, letterSpacing: 12 }}
          />
          <Sheen at={46} dur={30} strength={0.55} />
        </div>
        <div
          style={{
            fontFamily: FONT_SANS, fontWeight: 600, fontSize: 54, color: ink.accent, letterSpacing: 18,
            opacity: interpolate(frame, [32, 50], [0, 1], clamp),
            letterSpacing: interpolate(frame, [32, 80], [40, 18], { ...clamp, easing: easeOut }),
          }}
        >
          FOCUSGUARD
        </div>
      </AbsoluteFill>
      <Caption text="让 AI 帮你守住专注" delay={30} />
    </AbsoluteFill>
  );
};

// ═══ 13–21s AI 识别 ═══
export const Detect: React.FC = () => {
  const frame = useCurrentFrame();
  const enter = useIn(0, 15);
  const scanY = interpolate(frame, [20, 120], [0, 1], clamp);
  const steps = [
    ["① 应用名", "短视频 App", 40],
    ["② 屏幕文字", "「推荐」「点赞」", 85],
    ["③ AI 看图", "画面为短视频流", 130],
  ] as const;
  const verdict = useIn(175, 12);
  return (
    <AbsoluteFill>
      <Backdrop />
      <Particles seed="detect" count={28} />
      <AbsoluteFill style={{ alignItems: "center", paddingTop: 150 }}>
        <div style={{ transform: `translateY(${(1 - enter) * 300}px)`, opacity: enter, position: "relative" }}>
          <Float amp={10} tilt={4}>
            <PhoneScreen name="home" width={640} fallback={<MockHome />} />
          </Float>
          {/* 扫描线 */}
          <div
            style={{
              position: "absolute", left: 20, right: 20, top: 30 + scanY * 1280, height: 8,
              background: `linear-gradient(90deg, transparent, ${ink.accent}, transparent)`,
              boxShadow: `0 0 50px 12px ${ink.accent}88`, opacity: frame < 125 ? 1 : 0,
            }}
          />
        </div>
      </AbsoluteFill>
      <div style={{ position: "absolute", left: 70, right: 70, top: 1080, display: "flex", flexDirection: "column", gap: 22 }}>
        {steps.map(([k, v, at]) => {
          const s = spring({ frame: frame - at, fps: 30, config: { damping: 13 } });
          return (
            <Card
              key={k}
              style={{
                padding: "24px 36px", display: "flex", justifyContent: "space-between", alignItems: "center",
                opacity: s, transform: `translateX(${(1 - s) * -120}px)`, background: `${ink.card}F2`,
              }}
            >
              <span style={{ fontSize: 44, fontWeight: 800, color: ink.accent }}>{k}</span>
              <span style={{ fontSize: 40, color: ink.text }}>{v}</span>
            </Card>
          );
        })}
        <div
          style={{
            alignSelf: "center", marginTop: 10, padding: "20px 56px", borderRadius: 999,
            background: ink.error, color: "#fff", fontFamily: FONT_SANS, fontSize: 56, fontWeight: 900,
            transform: `scale(${verdict})`, boxShadow: `0 0 80px ${ink.error}AA`,
          }}
        >
          娱乐 92%
        </div>
      </div>
      <Ripple at={175} cy={1560} color={ink.error} rings={2} max={600} />
      <Caption text="三级识别，看懂你在做什么" delay={14} />
    </AbsoluteFill>
  );
};

// ═══ 21–30s 锁机 ═══
export const Lock: React.FC = () => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();
  const enter = useIn(0, 13);
  const zoom = interpolate(frame, [0, durationInFrames], [1, 1.08]);
  const total = 45 * 60;
  const secs = 36 * 60 + 12 - frame / 30;
  const src = shot("lock");
  return (
    <AbsoluteFill>
      <Backdrop />
      <Particles seed="lock" count={24} speed={0.5} />
      <Ripple at={6} cy={900} rings={3} />
      <AbsoluteFill style={{ alignItems: "center", paddingTop: 110 }}>
        <div style={{ position: "relative", transform: `scale(${zoom * (0.85 + 0.15 * enter)})`, opacity: enter }}>
          <Float amp={8} tilt={3} seed={2}>
          <Phone width={780}>
            {src ? (
              <Img src={src} style={{ width: "100%", height: "100%", objectFit: "cover" }} />
            ) : (
              <Scaled width={780 * 0.944}>
                <MockLock seconds={Math.ceil(secs)} progress={secs / total} roll={1 - (secs % 1)} />
              </Scaled>
            )}
            <Sheen at={34} dur={34} />
          </Phone>
          </Float>
        </div>
      </AbsoluteFill>
      <Caption text="娱乐超时，自动锁机" sub="手势、返回键、最近任务都退不出去" delay={10} />
    </AbsoluteFill>
  );
};

// ═══ 30–37s 答题解锁 ═══
const ANSWER = "1761855";
export const Challenge: React.FC = () => {
  const frame = useCurrentFrame();
  const enter = useIn(0, 14);
  const typedLen = Math.max(0, Math.min(ANSWER.length, Math.floor((frame - 30) / 9)));
  const typed = ANSWER.slice(0, typedLen);
  const pressed = typedLen > 0 && (frame - 30) % 9 < 4 ? ANSWER[typedLen - 1] : undefined;
  const correct = Math.min(5, Math.max(1, Math.floor(interpolate(frame, [110, 200], [1, 5.99], clamp))));
  const src = shot("challenge");
  return (
    <AbsoluteFill>
      <Backdrop />
      <AbsoluteFill style={{ alignItems: "center", paddingTop: 130 }}>
        <div style={{ opacity: enter, transform: `translateY(${(1 - enter) * 200}px)` }}>
          <Float amp={8} tilt={3} seed={4}>
          <Phone width={720}>
            {src ? (
              <Img src={src} style={{ width: "100%", height: "100%", objectFit: "cover" }} />
            ) : (
              <Scaled width={720 * 0.944}>
                <MockChallenge typed={typed} correct={correct} pressed={pressed} />
              </Scaled>
            )}
          </Phone>
          </Float>
        </div>
      </AbsoluteFill>
      <div
        style={{
          position: "absolute", top: 90, right: 70, padding: "16px 34px", borderRadius: 999,
          background: `${ink.card}F2`, border: `2px solid ${ink.accent}`, fontFamily: FONT_SERIF,
          fontSize: 60, fontWeight: 700, color: ink.accent,
        }}
      >
        {correct} / 5
      </div>
      <Ripple at={110} cy={170} cx={900} color={ink.success} rings={2} max={420} />
      <Ripple at={200} cy={170} cx={900} color={ink.success} rings={3} max={700} />
      <Caption text="想解锁？先答对题" sub="小学到大学，按年级出题" delay={10} />
    </AbsoluteFill>
  );
};

// ═══ 37–42s 强度 4 ═══
export const Strength: React.FC = () => {
  const frame = useCurrentFrame();
  const levels = [
    ["强度 1", "答对 1 题"],
    ["强度 2", "连对 5 题"],
    ["强度 3", "朋友辅助"],
    ["强度 4", "不可解锁"],
  ];
  const pick = interpolate(frame, [20, 70], [0, 3], { ...clamp, easing: Easing.inOut(Easing.cubic) });
  const red = interpolate(frame, [70, 85], [0, 1], clamp);
  return (
    <AbsoluteFill>
      <Backdrop p={{ ...ink, accent: red > 0.5 ? ink.error : ink.accent }} intensity={1 + red * 0.4} />
      <div style={{ position: "absolute", top: 240, left: 80, right: 80, display: "flex", flexDirection: "column", gap: 30 }}>
        {levels.map(([k, v], i) => {
          const active = Math.round(pick) === i;
          const enterK = spring({ frame: frame - i * 4, fps: 30, config: { damping: 14 } });
          const isFour = i === 3;
          const col = isFour && red > 0.5 ? ink.error : ink.accent;
          return (
            <div
              key={k}
              style={{
                padding: "44px 56px", borderRadius: 44, display: "flex", justifyContent: "space-between",
                alignItems: "center", fontFamily: FONT_SANS,
                background: active ? `${col}33` : `${ink.card}CC`,
                border: `3px solid ${active ? col : ink.line}`,
                transform: `translateX(${(1 - enterK) * 160}px) scale(${active ? 1.06 : 1})`,
                opacity: enterK,
                boxShadow: active ? `0 0 90px ${col}77` : undefined,
              }}
            >
              <span style={{ fontSize: 60, fontWeight: 900, color: active ? col : ink.text }}>{k}</span>
              <span style={{ fontSize: 50, color: active ? ink.text : ink.haze }}>{v}</span>
            </div>
          );
        })}
      </div>
      <div
        style={{
          position: "absolute", top: 1150, left: 0, right: 0, textAlign: "center", fontFamily: FONT_SANS,
          fontSize: 44, color: ink.error, opacity: red,
        }}
      >
        不能暂停 · 改时间、清后台都没用
      </div>
      <Ripple at={72} cy={890} color={ink.error} rings={3} max={1100} />
      <Caption text="最强一档：只能等时间结束" delay={60} />
    </AbsoluteFill>
  );
};

// ═══ 42–50s 主题快切 ═══
export const Themes: React.FC = () => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();
  const per = Math.floor(durationInFrames / styles.length);
  const idx = Math.min(styles.length - 1, Math.floor(frame / per));
  const local = frame - idx * per;
  const pop = spring({ frame: local, fps: 30, config: { damping: 12 } });
  const st = styles[idx];
  const shotName = `lock_${st.id}`;
  const src = shot(shotName);
  return (
    <AbsoluteFill>
      <Backdrop p={st.p} intensity={1.3} />
      {idx > 0 && local < 14 && (
        <RadialWipe progress={interpolate(local, [0, 14], [0, 1], { ...clamp, easing: easeOut })} color={`${st.p.accent}33`} />
      )}
      <Particles seed={`theme${idx}`} color={st.p.glow} count={30} />
      <AbsoluteFill style={{ alignItems: "center", paddingTop: 120 }}>
        <div style={{ transform: `scale(${0.9 + 0.1 * pop}) rotateY(${(1 - pop) * 18}deg)`, opacity: 0.4 + 0.6 * pop }}>
          <Phone width={720} glow={st.p.accent}>
            {src ? (
              <Img src={src} style={{ width: "100%", height: "100%", objectFit: "cover" }} />
            ) : (
              <Scaled width={720 * 0.944}>
                <MockLock p={st.p} seconds={30 * 60 - frame / 30} progress={0.68} />
              </Scaled>
            )}
            <Sheen at={idx * per + 4} dur={22} />
          </Phone>
        </div>
      </AbsoluteFill>
      <div
        style={{
          position: "absolute", top: 70, left: 0, right: 0, textAlign: "center", fontFamily: FONT_SANS,
          fontWeight: 900, fontSize: 64, color: st.p.accent, letterSpacing: 8,
          opacity: pop, transform: `translateY(${(1 - pop) * -20}px)`,
        }}
      >
        {st.label}
      </div>
      <Caption text="8 种风格，随心自定义" sub="背景图 · 光晕 · 卡片 · 圆角 · 字号" delay={8} />
    </AbsoluteFill>
  );
};

// ═══ 50–55s 番茄钟 + AI 对话 ═══
export const Duo: React.FC = () => {
  const frame = useCurrentFrame();
  const l = useIn(0, 14);
  const r = useIn(8, 14);
  const hl = interpolate(frame, [50, 70, 110, 140], [0, 1, 1, 0.6], clamp);
  return (
    <AbsoluteFill>
      <Backdrop />
      <Particles seed="duo" count={26} />
      <AbsoluteFill style={{ flexDirection: "row", alignItems: "center", justifyContent: "center", gap: 40, paddingBottom: 300 }}>
        <div style={{ opacity: l, transform: `translateX(${(1 - l) * -200}px) rotate(-4deg)` }}>
          <Float amp={12} tilt={4} seed={1}>
            <PhoneScreen name="timer" width={470} fallback={<MockTimer />} tilt={10} />
          </Float>
        </div>
        <div style={{ opacity: r, transform: `translateX(${(1 - r) * 200}px) rotate(4deg)` }}>
          <Float amp={12} tilt={4} seed={3.2}>
            <PhoneScreen name="chat" width={470} fallback={<MockChat highlight={hl} />} tilt={-10} />
          </Float>
        </div>
      </AbsoluteFill>
      <Caption text="番茄钟 · AI 对话 · 待办" sub="对 AI 说「锁我 30 分钟」就能锁机" delay={10} />
    </AbsoluteFill>
  );
};

// ═══ 55–60s 收尾 ═══
export const Outro: React.FC = () => {
  const frame = useCurrentFrame();
  const s = useIn(22, 12);
  const t = useIn(46, 16);
  const fadeOut = interpolate(frame, [120, 150], [1, 0], clamp);
  return (
    <AbsoluteFill style={{ opacity: fadeOut }}>
      <Backdrop intensity={interpolate(frame, [0, 60], [1.4, 0.8], clamp)} />
      <Particles seed="outro" count={60} converge={interpolate(frame, [0, 26], [0, 1], { ...clamp, easing: Easing.in(Easing.cubic) })} cy={700} />
      <Flash at={24} cy={700} />
      <Ripple at={26} cy={700} rings={2} max={800} />
      <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column", paddingBottom: 120 }}>
        <div style={{ position: "relative", transform: `scale(${s})`, filter: `drop-shadow(0 0 80px ${ink.accent})` }}>
          <Shield size={240} color={ink.accent} />
        </div>
        <div style={{ position: "relative", marginTop: 40, overflow: "hidden" }}>
          <RevealText
            text="专注卫士"
            delay={30}
            stagger={4}
            dur={18}
            style={{ fontFamily: FONT_SANS, fontWeight: 900, fontSize: 130, color: ink.text, letterSpacing: 10 }}
          />
          <Sheen at={66} dur={28} strength={0.5} />
        </div>
        <div style={{ fontFamily: FONT_SANS, fontSize: 50, color: ink.accent, marginTop: 16, opacity: t, letterSpacing: 6 }}>
          现在开始专注
        </div>
        <div
          style={{
            marginTop: 90, padding: "22px 44px", borderRadius: 999, background: `${ink.card}E6`,
            border: `2px solid ${ink.line}`, fontFamily: FONT_SANS, fontSize: 38, color: ink.text,
            opacity: interpolate(frame, [30, 50], [0, 1], clamp),
          }}
        >
          github.com/mengcha114/FocusGuard
        </div>
        <div style={{ marginTop: 26, fontFamily: FONT_SANS, fontSize: 36, color: ink.haze, opacity: interpolate(frame, [40, 60], [0, 1], clamp) }}>
          开源 · MIT · Android
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
