import React from "react";
import { AbsoluteFill, Audio, Composition, interpolate, useVideoConfig } from "remotion";
import { TransitionSeries, linearTiming } from "@remotion/transitions";
import { fade } from "@remotion/transitions/fade";
import { slide } from "@remotion/transitions/slide";
import { FPS, H, W, ink } from "./theme";
import { musicSrc, snap } from "./data";
import { Brand, Challenge, Detect, Duo, Lock, Opening, Outro, Pain, Strength, Themes } from "./scenes/Scenes";

const TOTAL_SEC = 60;
const T = 12; // 转场帧数

/**
 * 镜头切点（秒）。每个切点吸附到最近的音乐节拍（±0.35s 内），
 * 没有音乐 / 节拍时保持原值。
 */
const CUTS = [4, 9, 13, 21, 30, 37, 42, 50, 55].map(snap);
const SCENES = [Opening, Pain, Brand, Detect, Lock, Challenge, Strength, Themes, Duo, Outro];

const Promo: React.FC = () => {
  const { durationInFrames } = useVideoConfig();
  const bounds = [0, ...CUTS, TOTAL_SEC].map((s) => Math.round(s * FPS));
  // TransitionSeries 中转场会让相邻镜头重叠 T 帧：除最后一个外每个镜头加 T 帧补偿
  const lens = SCENES.map((_, i) => bounds[i + 1] - bounds[i] + (i < SCENES.length - 1 ? T : 0));
  return (
    <AbsoluteFill style={{ background: ink.bg }}>
      <TransitionSeries>
        {SCENES.flatMap((Scene, i) => {
          const items = [
            <TransitionSeries.Sequence key={`s${i}`} durationInFrames={lens[i]}>
              <Scene />
            </TransitionSeries.Sequence>,
          ];
          if (i < SCENES.length - 1) {
            const presentation = i % 3 === 1 ? slide({ direction: "from-bottom" }) : fade();
            items.push(
              <TransitionSeries.Transition key={`t${i}`} presentation={presentation} timing={linearTiming({ durationInFrames: T })} />
            );
          }
          return items;
        })}
      </TransitionSeries>
      {musicSrc && (
        <Audio
          src={musicSrc}
          volume={(f) =>
            interpolate(f, [0, 15, durationInFrames - 60, durationInFrames], [0, 1, 1, 0], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
            })
          }
        />
      )}
    </AbsoluteFill>
  );
};

export const RemotionRoot: React.FC = () => (
  <Composition id="Promo" component={Promo} durationInFrames={TOTAL_SEC * FPS} fps={FPS} width={W} height={H} />
);
