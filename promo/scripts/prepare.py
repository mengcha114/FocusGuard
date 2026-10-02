#!/usr/bin/env python3
"""
渲染前准备：
1. 把截图复制到 public/shots/（只收录存在且非空的文件）
2. 找到 assets/ 下的音乐文件，复制到 public/，并用 ffmpeg 解码成单声道 wav
3. 用能量包络检测节拍点（onset），写入 manifest.json，供镜头切点吸附

用法：prepare.py <截图目录或空字符串>
"""
import json
import shutil
import subprocess
import sys
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PUBLIC = ROOT / "public"
SHOTS_OUT = PUBLIC / "shots"
ASSETS = ROOT / "assets"
MANIFEST = ROOT / "src" / "manifest.json"

REAL = ASSETS / "real"  # 用户提供的真机截图，优先级最高

SHOT_NAMES = [
    "home", "home_styled", "chat", "timer", "settings", "settings_theme", "lock", "strength4", "challenge",
    "lock_ink", "lock_ocean", "lock_sakura", "lock_aurora", "lock_sunset", "lock_light",
]


def collect_shots(src_dir: str) -> list:
    """
    每个镜头的截图来源优先级：真机截图（assets/real）> 模拟器截图 > 无（网页还原界面）。
    统一转成 PNG 存入 public/shots/<name>.png。
    """
    from PIL import Image

    SHOTS_OUT.mkdir(parents=True, exist_ok=True)
    found, real_used = [], []
    src = Path(src_dir) if src_dir else None
    for name in SHOT_NAMES:
        picked = None
        for d in [REAL, src]:
            if d is None:
                continue
            for ext in ("png", "jpg", "jpeg", "webp"):
                f = d / f"{name}.{ext}"
                if f.exists() and f.stat().st_size > 10_000:
                    picked = f
                    break
            if picked:
                break
        if not picked:
            continue
        Image.open(picked).convert("RGB").save(SHOTS_OUT / f"{name}.png")
        found.append(name)
        if picked.parent == REAL:
            real_used.append(name)
    if real_used:
        print(f"[prepare] 使用真机截图：{', '.join(real_used)}")
    return found


def find_music():
    for ext in ("mp3", "wav", "m4a", "flac", "ogg"):
        hits = sorted(ASSETS.glob(f"*.{ext}"))  # 只看 assets/ 顶层，不进 real/
        if hits:
            return hits[0]
    return None


def detect_beats(music: Path) -> list:
    """能量包络 onset 检测：20ms 帧能量的正向差分，取局部峰值，最小间隔 0.3s。"""
    wav = PUBLIC / "_analysis.wav"
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(music), "-ac", "1", "-ar", "22050", "-t", "62", str(wav)],
        check=True,
    )
    import numpy as np

    with wave.open(str(wav)) as w:
        sr = w.getframerate()
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
    wav.unlink()

    hop = int(sr * 0.02)
    n = len(x) // hop
    if n < 10:
        return []
    energy = np.sqrt(np.mean(x[: n * hop].reshape(n, hop) ** 2, axis=1))
    flux = np.maximum(0.0, np.diff(energy, prepend=energy[0]))
    # 自适应阈值：局部均值 + 1.5 倍标准差
    win = 25
    pad = np.pad(flux, (win, win), mode="edge")
    mean = np.convolve(pad, np.ones(2 * win + 1) / (2 * win + 1), mode="valid")
    thr = mean + 1.5 * flux.std()
    beats, last = [], -1.0
    for i in range(1, n - 1):
        t = i * 0.02
        if flux[i] > thr[i] and flux[i] >= flux[i - 1] and flux[i] >= flux[i + 1] and t - last >= 0.3:
            beats.append(round(t, 3))
            last = t
    return beats


def main():
    shots = collect_shots(sys.argv[1] if len(sys.argv) > 1 else "")
    music = find_music()
    music_name, beats = None, []
    if music:
        music_name = "music" + music.suffix.lower()
        shutil.copy(music, PUBLIC / music_name)
        try:
            beats = detect_beats(music)
        except Exception as e:  # 节拍检测失败不影响渲染，只是切点不吸附
            print(f"[prepare] 节拍检测失败：{e}")
    MANIFEST.write_text(json.dumps({"shots": shots, "music": music_name, "beats": beats}, ensure_ascii=False))
    print(f"[prepare] 截图 {len(shots)}/{len(SHOT_NAMES)}：{', '.join(shots) or '无（全部使用网页还原界面）'}")
    print(f"[prepare] 音乐：{music_name or '无（静音音轨）'}，节拍点 {len(beats)} 个")


if __name__ == "__main__":
    main()
