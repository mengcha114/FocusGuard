import { staticFile } from "remotion";
// 构建脚本生成：public/ 下实际存在的截图、音乐、节拍点。缺失时为默认值。
import manifest from "./manifest.json";

type Manifest = { shots: string[]; music: string | null; beats: number[] };
const m = manifest as Manifest;

/** 真实截图（存在时返回 URL，否则 null → 场景改用网页还原界面）。 */
export const shot = (name: string): string | null =>
  m.shots.includes(name) ? staticFile(`shots/${name}.png`) : null;

export const musicSrc: string | null = m.music ? staticFile(m.music) : null;

/** 节拍点（秒）。用于把镜头切换吸附到最近的强拍。 */
export const beats: number[] = m.beats;

/** 把时间 t（秒）吸附到 ±0.35s 内最近的节拍点。 */
export const snap = (t: number): number => {
  let best = t;
  let bestD = 0.35;
  for (const b of beats) {
    const d = Math.abs(b - t);
    if (d < bestD) {
      best = b;
      bestD = d;
    }
  }
  return best;
};
