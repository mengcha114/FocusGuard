// 与应用 FocusColors.kt 同源的设计令牌（ink 调色板为主，另含主题组配色）。
export const ink = {
  bg: "#111622",
  surface: "#18202F",
  card: "#1F293D",
  line: "#334155",
  accent: "#F59E0B",
  accentDeep: "#D97706",
  text: "#F8FAFC",
  haze: "#94A3B8",
  faint: "#64748B",
  error: "#F87171",
  success: "#34D399",
  glow: "#FBBF24",
};

export type Palette = typeof ink;

export const styles: { id: string; label: string; p: Palette }[] = [
  { id: "ink", label: "纸墨", p: ink },
  {
    id: "ocean", label: "深海",
    p: { ...ink, bg: "#0C1322", surface: "#131D33", card: "#1B2947", line: "#2A3F6D", accent: "#38BDF8", accentDeep: "#0284C7", haze: "#7DD3FC", glow: "#818CF8" },
  },
  {
    id: "sakura", label: "樱夜",
    p: { ...ink, bg: "#1A131F", surface: "#241A2B", card: "#2E2237", line: "#4A3757", accent: "#F472B6", accentDeep: "#DB2777", haze: "#F9A8D4", glow: "#C084FC" },
  },
  {
    id: "aurora", label: "极光",
    p: { ...ink, bg: "#0F1626", surface: "#17223B", card: "#1F2F52", line: "#334A7D", accent: "#2DD4BF", accentDeep: "#0D9488", haze: "#99F6E4", glow: "#A78BFA" },
  },
  {
    id: "sunset", label: "日落",
    p: { ...ink, bg: "#1C1310", surface: "#281A16", card: "#35231D", line: "#54372E", accent: "#FB923C", accentDeep: "#EA580C", haze: "#FED7AA", glow: "#FDE047" },
  },
  {
    id: "light", label: "纸墨 · 浅色",
    p: { ...ink, bg: "#F8FAFC", surface: "#F1F5F9", card: "#E2E8F0", line: "#CBD5E1", accent: "#D97706", accentDeep: "#B45309", text: "#0F172A", haze: "#475569", faint: "#64748B", glow: "#FBBF24" },
  },
];

export const FONT_SANS = '"Noto Sans CJK SC", "Noto Sans SC", "PingFang SC", sans-serif';
export const FONT_SERIF = '"Noto Serif CJK SC", "Noto Serif SC", serif';

export const FPS = 30;
export const W = 1080;
export const H = 1920;
