#!/usr/bin/env python3
"""重建学段版题库（离线打包进 APK）。

数据源（全部来自 GitHub，本机 HuggingFace 不可达）：
  1. GAOKAO-Bench 2010-2022 客观题 + GAOKAO-Bench-Updates 2023/2024（Apache-2.0，含 analysis 详解）
  2. TAL-SCQ5K 中文单选 3K+2K（MIT，含 difficulty 与 knowledge_point_routes ⇒ 学段标签）
  3. AGIEval v1_1 gaokao-*（MIT 仓库，数据沿用原源；含 answer/options）

筛选规则（用户要求：偏难但不要离谱 + 必须有详细解题过程 + 手机答得了）：
  - 必须有解析（>=20 字）
  - 只收可作答题型：有 2~4 个连续选项的选择题，或答案 <= 20 字的填空题
  - 不含图片依赖（"如图"/"图1"/"如图所示" 等一律丢弃，App 不渲染图片）
  - 难度档 d：3=单元测中难、4=期末/高考、5=压轴；入库门槛 d>=3，d==5 占比 <= 10%
  - 学段桶：小学=6、初中=9、高中=12（App 侧"借下一级"规则保证相邻年级可用）

输出：app/src/edu/assets/question_bank.json + 统计；许可声明见 question_bank_LICENSE.txt
"""
import json, hashlib, os, re, sys, urllib.request, collections

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app/src/edu/assets/question_bank.json")
TMP = "/tmp/qb"

SUBJECTS = {
    "Physics": "物理", "Chemistry": "化学", "Biology": "生物", "Math": "数学",
    "Math_I": "数学", "Math_II": "数学", "Chinese": "语文", "Chinese_Lang_and_Usage": "语文",
    "Chinese_Modern_Lit": "语文", "English": "英语", "History": "历史",
    "Geography": "地理", "Political_Science": "政治",
}
SCIENCE = {"物理", "化学", "生物"}
HUMANITIES = {"历史", "地理", "政治"}
LONG_TEXT_SUBJECTS = {"语文", "英语", "历史", "地理", "政治"}
IMG_PAT = re.compile(r"如图|图 *\d|如下图所示|见下图|图所示|ImagePath|\[图\]|图表")
OPT_SPLIT = re.compile(r"(?m)^\s*([A-E])[.、．)）]\s*")
TAL_MODULE_GRADE = {
    # 小学奥数模块（按 TAL 知识点路由末段归类）
    "应用题模块": 6, "数论模块": 6, "计数模块": 6, "行程模块": 6, "几何模块": 6,
    "组合模块": 6, "计算模块": 6, "数学广角": 6, "数据处理": 6, "七大能力": 6,
    "运算求解": 6, "对应思想": 6, "枚举思想": 6, "整体思想": 6, "赋值思想": 6,
    "逐步调整思想": 6, "构造模型": 6, "符号代换": 6, "逆向思想": 6, "逻辑分析": 6,
    "分类讨论思想": 6, "转化与化归的思想": 6, "数感认知": 6, "数的认识": 6,
    "数的运算": 6, "数与运算": 6, "式与方程": 6, "实践应用": 6, "综合与实践": 6,
    # 初中模块
    "式": 9, "方程与不等式": 9, "三角形": 9, "几何图形初步": 9, "数": 9,
    "数与式": 9, "四边形": 9, "命题与证明": 9, "几何变换": 9, "统计与概率": 9,
    "配方法": 9, "函数": 9, "不等式": 9, "函数的概念与性质": 12, "数列与数学归纳法": 12,
    "三角函数": 12, "立体几何初步": 12, "计数原理": 12, "复数与平面向量": 12,
    "解析几何": 12, "圆锥曲线": 12, "数列": 12, "集合": 12, "多项式与方程": 12,
    "排列组合与概率": 12, "立体几何与空间向量": 12, "直线和圆的方程": 12,
    "随机现象": 12, "归纳总结": 6, "测量": 6, "图形认知": 6, "数论": 6,
    "组合": 6, "数学": 12, "物理": 12, "化学": 12, "生物": 12,
}
# TAL 知识点路由首段 → 学段（这是原始数据里唯一可靠的学段信号）
TAL_ROOT_LEVEL = {
    "拓展思维": "小学", "知识标签": "小学", "小升初": "小学",
    "课内体系": "初中", "美国amc8": "初中", "美国AMC8": "初中", "海外竞赛体系": "初中",
    "竞赛": "高中", "Overseas Competition": "小学",
}
# 学段 → 年级桶：TAL 的题只在学段内轮转分配（保证 4~6 / 7~9 / 10~12 每个年级都有足够题），
# 高考真题仍固定挂 12 年级（它们本来就是高三卷，不虚标）
LEVEL_BUCKET_LIST = {"小学": [4, 5, 6], "初中": [7, 8, 9], "高中": [10, 11, 12]}
_tal_seq = {}


def fetch(url, path):
    if os.path.exists(path) and os.path.getsize(path) > 200:
        return path
    os.makedirs(os.path.dirname(path), exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "FocusGuard-bank-builder"})
    with urllib.request.urlopen(req, timeout=90) as r, open(path, "wb") as f:
        f.write(r.read())
    return path



# ── LaTeX → 纯文本（App 不渲染公式：锁机答题界面是纯文本 + 自绘键盘）──
LATEX_SYMBOLS = {
    "\\times": "×", "\\div": "÷", "\\cdot": "·", "\\pm": "±", "\\mp": "∓",
    "\\leq": "≤", "\\le": "≤", "\\geq": "≥", "\\ge": "≥", "\\neq": "≠",
    "\\approx": "≈", "\\equiv": "≡", "\\infty": "∞", "\\propto": "∝",
    "\\alpha": "α", "\\beta": "β", "\\gamma": "γ", "\\delta": "δ",
    "\\theta": "θ", "\\lambda": "λ", "\\mu": "μ", "\\pi": "π",
    "\\varphi": "φ", "\\rho": "ρ", "\\sigma": "σ", "\\omega": "ω",
    "\\Delta": "Δ", "\\Omega": "Ω", "\\triangle": "△", "\\angle": "∠",
    "\\perp": "⊥", "\\parallel": "∥", "\\sim": "∽", "\\cong": "≌",
    "\\sum": "∑", "\\prod": "∏", "\\int": "∫", "\\in": "∈", "\\notin": "∉",
    "\\subseteq": "⊆", "\\subset": "⊂", "\\cup": "∪", "\\cap": "∩",
    "\\emptyset": "∅", "\\varnothing": "∅", "\\rightarrow": "→", "\\to": "→",
    "\\leftarrow": "←", "\\Rightarrow": "⇒", "\\Leftrightarrow": "⇔",
    "\\because": "∵", "\\therefore": "∴", "\\degree": "°",
    "\\circ": "°", "\\ldots": "…", "\\cdots": "…", "\\dots": "…",
    "\\quad": " ", "\\qquad": "  ", "\\!": "", "\\,": " ", "\\;": " ",
    "\\left": "", "\\right": "", "\\displaystyle": "", "\\limits": "",
}
SUP = str.maketrans("0123456789+-=()n", "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿ")
SUB = str.maketrans("0123456789+-=()", "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎")


def _script(body, table):
    if body and all(ch.translate(table) != ch for ch in body):
        return body.translate(table)
    return None


def _read_group(t, i):
    """从 t[i] == '{' 起读出配平的 {...}（支持嵌套），返回 (内容, 结束下标)。"""
    if i >= len(t) or t[i] != "{":
        return None, i
    depth = 0
    for j in range(i, len(t)):
        if t[j] == "{":
            depth += 1
        elif t[j] == "}":
            depth -= 1
            if depth == 0:
                return t[i + 1:j], j + 1
    return None, i


def _convert_fracs(t):
    """\\frac{a}{b} → (a)/(b)，支持嵌套与反复出现。"""
    out, guard = t, 0
    while guard < 40:
        guard += 1
        m = re.search(r"\\[dt]?frac", out)
        if not m:
            break
        i = m.end()
        while i < len(out) and out[i] == " ":
            i += 1
        a, i2 = _read_group(out, i)
        if a is None:
            break
        while i2 < len(out) and out[i2] == " ":
            i2 += 1
        b, i3 = _read_group(out, i2)
        if b is None:
            break
        out = out[:m.start()] + "(" + a + ")/(" + b + ")" + out[i3:]
    return out


def latex_to_text(s):
    """把常见 LaTeX 写法转成可读纯文本（不追求完美，只求人能看懂）。"""
    if s is None:
        return ""
    t = str(s)
    t = _convert_fracs(t)                               # 分数（支持嵌套）
    t = re.sub(r"\\sqrt\s*\{([^{}]*)\}", r"√(\1)", t)
    t = re.sub(r"\\[a-zA-Z]*(?:mathrm|text|mathbf|mathit|mathbb|operatorname)\s*\{([^{}]*)\}",
               r"\1", t)
    def sup(m):
        r = _script(m.group(1), SUP)
        return r if r is not None else "^(" + m.group(1) + ")"
    def sub(m):
        r = _script(m.group(1), SUB)
        return r if r is not None else "_(" + m.group(1) + ")"
    t = re.sub(r"\^\s*\{([^{}]*)\}", sup, t)
    t = re.sub(r"_\s*\{([^{}]*)\}", sub, t)
    t = re.sub(r"\^\s*([0-9])", lambda m: _script(m.group(1), SUP) or m.group(0), t)
    # 长键优先：否则 \le 会先吃掉 \left 的前缀（生成 "≤ft"）、\cdot 吃掉 \cdots（"·s"）
    for k in sorted(LATEX_SYMBOLS, key=len, reverse=True):
        t = t.replace(k, LATEX_SYMBOLS[k])
    t = re.sub(r"\\[a-zA-Z]+", "", t)                   # 其余未知命令直接去掉
    t = t.replace("$$", "").replace("$", "")
    t = t.replace("\\", " ").replace("\n\n\n", "\n\n")
    t = t.replace("{", "").replace("}", "")              # 残留花括号一律去掉
    t = re.sub(r"[ \t]{2,}", " ", t)
    t = re.sub(r"^[（(]?[★☆]+[)）]?\s*", "", t)          # 去掉题首的 (★★) 难度标记
    return t.strip()


def option_text(o):
    """TAL 的选项是对象（含 aoVal / content）；GAOKAO 侧是字符串。统一取出正文。"""
    if o is None:
        return ""
    if isinstance(o, str):
        return o
    if isinstance(o, dict):
        for k in ("content", "text", "option", "value", "body"):
            if o.get(k) not in (None, ""):
                return str(o[k])
        return ""
    if isinstance(o, (list, tuple)) and o:
        return option_text(o[-1])
    return str(o)
CJK_PAT = re.compile(r"[\u4e00-\u9fff]")


def cjk_count(s):
    return len(CJK_PAT.findall(str(s or "")))


def clean(text):
    if not text:
        return ""
    s = latex_to_text(text).replace("\r", "")
    s = re.sub(r"\n{3,}", "\n\n", s)
    return s.strip()


def parse_options(question):
    """把内联选项（A. … B. …）拆出来，返回 (题干, [选项]) 或 (题干, None) 表示解析失败。"""
    parts = OPT_SPLIT.split(question)
    if len(parts) < 5:          # 至少要 A~D 四个选项：前导文本 + 4*(字母+内容)
        return question, None
    head = parts[0].strip()
    opts, letters = [], []
    for i in range(1, len(parts) - 1, 2):
        letter, body = parts[i], parts[i + 1]
        opts.append(letter + ". " + clean(body).replace("\n", " "))
        letters.append(letter)
    if len(opts) < 2 or len(opts) > 5:
        return question, None
    if letters != [chr(ord("A") + i) for i in range(len(letters))]:
        return question, None
    if len(head) < 8:
        return question, None
    return head, opts


def make(grade, subject, q, opts, ans, exp, diff, src, module=""):
    if not q or not ans or not exp:
        return None
    if len(exp) < 20:
        return None
    cap = 1200 if subject in LONG_TEXT_SUBJECTS else 500
    if len(q) > cap:
        return None
    if IMG_PAT.search(q) or IMG_PAT.search(exp):
        return None
    if "\\frac" in q or "\\dfrac" in q or "\\tfrac" in q or "\\frac" in exp:
        return None                                  # 还有没转干净的公式：宁缺勿滥
    if diff < 3:
        return None
    st = "SCIENCE" if subject in SCIENCE else ("HUMANITIES" if subject in HUMANITIES else "ALL")
    # t = 知识点/主题（App 的 QuestionBank 读它当 topic；题型由 App 按 o/a 自行判定）
    topic = module if module else subject
    return {
        "g": grade, "s": subject, "st": st, "t": topic, "d": diff,
        "q": q, "o": opts or [], "a": str(ans).strip(), "e": exp,
        "src": src,
    }


def load_gaokao_bench():
    items = []
    files = []
    for name in sorted(os.listdir(TMP)):
        if name.endswith(".json") and ("MCQs" in name or "Cloze" in name or "Reading" in name or "Fill_in" in name or "Modern_Lit" in name):
            files.append(os.path.join(TMP, name))
    for d in ("up2023", "up2024"):
        p = os.path.join(TMP, d)
        if os.path.isdir(p):
            files += [os.path.join(p, f) for f in sorted(os.listdir(p)) if f.endswith(".json")]
    for path in files:
        try:
            data = json.load(open(path, encoding="utf-8"))
        except Exception:
            continue
        key = os.path.basename(path).replace(".json", "")
        subj_key = key.split("_", 1)[-1] if key[:4].isdigit() else key
        subj_key = re.sub(r"^(20\d\d-20\d\d|20\d\d)_", "", key)
        subject = None
        for k, v in SUBJECTS.items():
            if subj_key.startswith(k):
                subject = v
                break
        if not subject:
            continue
        for ex in data.get("example", []):
            q = clean(ex.get("question", ""))
            ans = clean(ex.get("answer", ""))
            exp = clean(ex.get("analysis", "") or ex.get("explanation", ""))
            head, opts = parse_options(q)
            if opts is None:
                if not ans or len(ans) > 20:
                    continue
                head, opts = q, []
            m = make(12, subject, head, opts, ans, exp, 4, "GAOKAO-Bench")
            if m:
                items.append(m)
    return items


def load_tal():
    items = []
    for name in ("ch_single_choice_train_3K.jsonl", "ch_single_choice_test_2K.jsonl"):
        path = fetch(f"https://raw.githubusercontent.com/math-eval/TAL-SCQ5K/main/ch_single_choice_constructed_5K/{name}",
                     os.path.join(TMP, name))
        for line in open(path, encoding="utf-8"):
            try:
                it = json.loads(line)
            except Exception:
                continue
            routes = it.get("knowledge_point_routes") or []
            flat = []
            for r in routes:
                # TAL 的路径用 "->" 分隔（也兼容 "/"）
                flat += [p.strip() for p in re.split(r"->|/", str(r)) if p.strip()]
            root = flat[0].strip() if flat else ""
            level = TAL_ROOT_LEVEL.get(root)
            if level is None:                              # 首段认不出就不收（宁缺勿滥）
                continue
            module = next((p for p in flat if p.endswith("模块")), "")
            if not module:
                module = next((p for p in reversed(flat) if p in TAL_MODULE_GRADE), "数学")
            diff = min(int(it.get("difficulty") or 0) + 2, 5)   # TAL 0–4 → 2–6，取 >=3
            buckets = LEVEL_BUCKET_LIST.get(level, [6])
            idx = _tal_seq.get(level, 0)
            _tal_seq[level] = idx + 1
            grade = buckets[idx % len(buckets)]
            opts = []
            for i, raw in enumerate(it.get("options") or it.get("answer_option_list") or []):
                letter = ""
                if isinstance(raw, dict):
                    letter = str(raw.get("aoVal") or raw.get("key") or "").strip()
                txt = clean(option_text(raw))
                if not txt:
                    continue
                if not letter:
                    letter = chr(ord("A") + len(opts))
                if not re.match(r"^[A-E][.、．)）]\s*", txt):
                    txt = letter + ". " + txt
                opts.append(txt)
            if not opts and len(clean(it.get("problem", ""))) > 12:
                head, parsed = parse_options(clean(it.get("problem", "")))
                if parsed:
                    opts = parsed
            ans = it.get("answer_value") or it.get("answer") or ""
            if not opts:                                   # 无选项 ⇒ 当填空题（答案要短）
                if len(clean(ans)) > 20:
                    continue
            problem = clean(it.get("problem", ""))
            analysis = clean(it.get("answer_analysis", ""))
            # TAL 数据集里混有 Math League / WMO 等纯英文原题（中文用户答不了），
            # 题干与解析都几乎没有中文的一律丢弃
            # TAL 里混有 Math League / Math kangaroo / WMO 等纯英文原题（中文用户答不了）
            if cjk_count(problem) < 15 or re.search(
                r"Math League|Math kangaroo|Mathematical Olympiad|Question *#|World Mathematical",
                problem, re.I,
            ):
                continue
            m = make(grade, "数学", problem, opts if opts else None,
                     ans, analysis, diff, "TAL-SCQ5K", module)
            if m:
                items.append(m)
    return items


def load_agieval():
    items = []
    base = "https://raw.githubusercontent.com/ruixiangcui/AGIEval/main/data/v1_1/"
    tasks = {
        "gaokao-physics": "物理", "gaokao-chemistry": "化学", "gaokao-biology": "生物",
        "gaokao-history": "历史", "gaokao-geography": "地理", "gaokao-mathcloze": "数学",
        "gaokao-mathqa": "数学", "gaokao-chinese": "语文", "gaokao-english": "英语",
    }
    for task, subject in tasks.items():
        path = os.path.join(TMP, "agieval", task + ".jsonl")
        try:
            fetch(base + task + ".jsonl", path)
        except Exception:
            continue
        for line in open(path, encoding="utf-8"):
            try:
                it = json.loads(line)
            except Exception:
                continue
            q = clean(it.get("question", ""))
            if it.get("passage"):
                q = clean(it["passage"]) + "\n\n" + q
            opts = [clean(o) for o in (it.get("options") or []) if clean(o)]
            ans = it.get("answer")
            if ans is None:
                lab = it.get("label")
                ans = chr(ord("A") + int(lab)) if isinstance(lab, int) else lab
            exp = clean(it.get("analysis", "") or it.get("explanation", ""))
            m = make(12, subject, q, opts if opts else None, ans, exp, 4, "AGIEval")
            if m:
                items.append(m)
    return items


def main():
    all_items = []
    for loader, name in ((load_gaokao_bench, "GAOKAO-Bench"), (load_tal, "TAL-SCQ5K"), (load_agieval, "AGIEval")):
        got = loader()
        print("  %-14s %d 条" % (name, len(got)))
        all_items += got
    # 去重
    seen, dedup = set(), []
    for it in all_items:
        h = hashlib.sha1((it["q"][:400] + it["a"]).encode("utf-8")).hexdigest()
        if h in seen:
            continue
        seen.add(h)
        dedup.append(it)
    # 难度 5 档限流（<=10%）
    five = [x for x in dedup if x["d"] == 5]
    limit = int(len(dedup) * 0.10)
    if len(five) > limit:
        keep5 = set(id(x) for x in five[:limit])
        dedup = [x for x in dedup if x["d"] != 5 or id(x) in keep5]
    dedup.sort(key=lambda x: (x["g"], x["s"], x["q"][:30]))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump(dedup, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    print("  写出 %d 条 → %s（%.2f MB）" % (len(dedup), OUT, os.path.getsize(OUT) / 1048576))
    g = collections.Counter(x["g"] for x in dedup)
    print("  年级:", dict(sorted(g.items())))
    s = collections.Counter(x["s"] for x in dedup)
    print("  学科:", dict(sorted(s.items(), key=lambda kv: -kv[1])))
    d = collections.Counter(x["d"] for x in dedup)
    print("  难度:", dict(sorted(d.items())))
    print("  来源:", dict(collections.Counter(x["src"] for x in dedup)))
    print("  主题 Top8:", collections.Counter(x["t"] for x in dedup).most_common(8))


if __name__ == "__main__":
    main()
