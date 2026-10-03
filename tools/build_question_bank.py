#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""构建本地题库 app/src/main/assets/question_bank.json.gz
数据源（MIT）：TAL-SCQ5K（好未来，中文数学单选）、AGIEval 高考（微软）。
规则：按真实来源判定年级（判断不了丢弃，宁少勿超纲）；去掉过易与过难；
多空填空丢弃，物理多选保留为字母组合；LaTeX 转可读文本，转不干净丢弃。
用法：python3 tools/build_question_bank.py [缓存目录]"""
import collections, gzip, json, os, random, re, sys, urllib.request

CACHE = sys.argv[1] if len(sys.argv) > 1 else "/tmp/qb"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app/src/edu/assets/question_bank.json")
LIC = os.path.join(ROOT, "app/src/edu/assets/question_bank_LICENSE.txt")
TAL = "https://raw.githubusercontent.com/math-eval/TAL-SCQ5K/main/ch_single_choice_constructed_5K/"
AGI = "https://raw.githubusercontent.com/ruixiangcui/AGIEval/main/data/v1/"
os.makedirs(CACHE, exist_ok=True)


def fetch(url, name):
    path = os.path.join(CACHE, name)
    if not os.path.exists(path):
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=120) as r:
            open(path, "wb").write(r.read())
    return [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]


SUP = str.maketrans("0123456789+-=()nixyk", "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱˣʸᵏ")
SUB = str.maketrans("0123456789+-=()naeijkmoxt", "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₙₐₑᵢⱼₖₘₒₓₜ")
SYM = [
    (r"\leqslant", "≤"), (r"\lt", "<"), (r"\gt", ">"), (r"\geqslant", "≥"), (r"\geqslant", "≥"), (r"\leq", "≤"), (r"\geq", "≥"), (r"\neq", "≠"),
    (r"\le", "≤"), (r"\ge", "≥"), (r"\ne", "≠"), (r"\approx", "≈"), (r"\times", "×"),
    (r"\cdots", "⋯"), (r"\ldots", "…"), (r"\dots", "…"), (r"\cdot", "·"), (r"\div", "÷"),
    (r"\pm", "±"), (r"\infty", "∞"), (r"\notin", "∉"), (r"\in", "∈"), (r"\subseteq", "⊆"),
    (r"\subsetneqq", "⫋"), (r"\subset", "⊂"), (r"\cup", "∪"), (r"\cap", "∩"), (r"\emptyset", "∅"),
    (r"\varnothing", "∅"), (r"\forall", "∀"), (r"\exists", "∃"), (r"\Rightarrow", "⇒"),
    (r"\Leftrightarrow", "⇔"), (r"\rightarrow", "→"), (r"\to", "→"), (r"\perp", "⊥"),
    (r"\parallel", "∥"), (r"\angle", "∠"), (r"\triangle", "△"), (r"\odot", "⊙"), (r"\circ", "°"),
    (r"\degree", "°"), (r"\prime", "′"), (r"\alpha", "α"), (r"\beta", "β"), (r"\gamma", "γ"),
    (r"\delta", "δ"), (r"\Delta", "Δ"), (r"\theta", "θ"), (r"\lambda", "λ"), (r"\mu", "μ"),
    (r"\pi", "π"), (r"\rho", "ρ"), (r"\sigma", "σ"), (r"\varphi", "φ"), (r"\phi", "φ"),
    (r"\omega", "ω"), (r"\Omega", "Ω"), (r"\varepsilon", "ε"), (r"\epsilon", "ε"),
    (r"\because", "∵"), (r"\therefore", "∴"), (r"\cong", "≅"), (r"\sim", "∼"), (r"\equiv", "≡"),
    (r"\lg", "lg"), (r"\ln", "ln"), (r"\log", "log"), (r"\sin", "sin"), (r"\cos", "cos"),
    (r"\tan", "tan"), (r"\max", "max"), (r"\min", "min"), (r"\lim", "lim"), (r"\%", "%"),
    (r"\{", "{"), (r"\}", "}"), (r"\qquad", " "), (r"\quad", " "), (r"\,", " "), (r"\;", " "),
    (r"\!", ""), (r"\ ", " "), (r"\mid", "|"), (r"\vert", "|"), (r"\|", "‖"),
]
WRAP_OK = re.compile(r"[\w.√π′]+")


def brace(s, i):
    depth = 0
    for j in range(i, len(s)):
        if s[j] == "{":
            depth += 1
        elif s[j] == "}":
            depth -= 1
            if depth == 0:
                return s[i + 1:j], j + 1
    raise ValueError("unbalanced")


def arg(s, i):
    while i < len(s) and s[i] == " ":
        i += 1
    if i < len(s) and s[i] == "{":
        return brace(s, i)
    if i < len(s) and s[i] == "\\":
        m = re.match(r"\\[a-zA-Z]+", s[i:])
        if m:
            return s[i:i + m.end()], i + m.end()
    return s[i:i + 1], i + 1


def wrap(x):
    return x if WRAP_OK.fullmatch(x) else "(" + x + ")"


def conv(s):
    """LaTeX 片段 → 可读文本（递归）。"""
    for k in ("\\mathrm", "\\text", "\\mathbf", "\\mathit", "\\boldsymbol", "\\textbf",
              "\\operatorname", "\\mathbb", "\\textrm"):
        while k + "{" in s or k + " {" in s:
            i = s.index(k)
            j = s.index("{", i)
            inner, e = brace(s, j)
            s = s[:i] + inner + s[e:]
    s = s.replace("\\{", "\u2983").replace("\\}", "\u2984")
    for k in ("\\left", "\\right", "\\displaystyle", "\\limits"):
        s = s.replace(k, "")
    out, i = [], 0
    while i < len(s):
        if re.match(r"\\[dt]?frac", s[i:]):
            j = i + re.match(r"\\[dt]?frac", s[i:]).end()
            a, j = arg(s, j)
            b, j = arg(s, j)
            out.append(wrap(conv(a)) + "/" + wrap(conv(b)))
            i = j
        elif s.startswith("\\sqrt", i):
            j, n = i + 5, ""
            if j < len(s) and s[j] == "[":
                k = s.index("]", j)
                n, j = s[j + 1:k].translate(SUP), k + 1
            a, j = arg(s, j)
            root = {"3": "∛", "4": "∜"}.get(n.translate(str.maketrans("³⁴", "34")), n + "√")
            out.append(root + wrap(conv(a)))
            i = j
        elif s.startswith("\\overrightarrow", i) or s.startswith("\\vec", i):
            j = i + (15 if s.startswith("\\overrightarrow", i) else 4)
            a, j = arg(s, j)
            out.append("向量" + conv(a))
            i = j
        elif s.startswith("\\overline", i):
            a, j = arg(s, i + 9)
            out.append(conv(a) + "\u0305")
            i = j
        elif s[i] in "^_":
            a, j = arg(s, i + 1)
            a = conv(a)
            t = a.translate(SUP if s[i] == "^" else SUB)
            plain = not re.search(r"[A-Za-z]", t)
            if plain:
                out.append(t)
            elif s[i] == "^":
                out.append("^" + (a if re.fullmatch(r"[\w]+", a) else "(" + a + ")"))
            else:
                out.append(t if re.fullmatch(r"[\w]", a) else "_" + wrap(a))
            i = j
        elif s[i] == "\\":
            for k, v in SYM:
                if s.startswith(k, i) and not (k[-1].isalpha() and i + len(k) < len(s) and s[i + len(k)].isalpha()):
                    out.append(v)
                    i += len(k)
                    break
            else:
                out.append(s[i])
                i += 1
        elif s[i] in "{}":
            i += 1
        else:
            out.append(s[i])
            i += 1
    return "".join(out)


def to_text(raw):
    """整段文字（含 $...$ 公式）→ 可读文本；转不干净返回 None。"""
    if raw is None:
        return None
    s = str(raw)
    if re.search(r"<img|\\begin|\\includegraphics|\\tikz|\\matrix|\\array|\\hline|如图|下图|图中|图示|图\s*\d|表格|下表", s):
        return None
    s = s.replace("$$", "$").replace("\\\\", "\n").replace("\\(", "$").replace("\\)", "$")
    parts = s.split("$")
    if len(parts) % 2 == 0:
        return None
    try:
        res = "".join(conv(p) if k % 2 else p for k, p in enumerate(parts))
    except (ValueError, IndexError):
        return None
    res = res.replace("（\u3000\u3000）", "（ ）").replace("( )", "（ ）")
    res = re.sub(r"[（(]\s*~\s*~?\s*[)）]", "（ ）", res).replace("~", " ")
    res = res.replace("\u2983", "{").replace("\u2984", "}")
    res = re.sub(r"``(.*?)''", r"“\1”", res).replace("``", "“").replace("''", "”")
    res = re.sub(r"([∠△⊙]) ", r"\1", res)
    res = re.sub(r"[ \t\u3000]+", " ", res)
    res = re.sub(r"\n\s*\n+", "\n", res).strip()
    # 残留反斜杠 = 未识别的 LaTeX 命令；花括号必须成对（集合记号）
    if chr(92) in res or res.count("{") != res.count("}"):
        return None
    return res


# 年级编码与 GradeStore.Grade.level 一致：1 小学 2 初一 3 初二 4 初三 5 高一 6 高二 7 高三 8 大学
SRC_GRADE = [("高三", 7), ("高考", 7), ("高二", 6), ("高一", 5), ("中考", 4), ("初三", 4), ("九年级", 4),
             ("初二", 3), ("八年级", 3), ("初一", 2), ("七年级", 2), ("小升初", 1), ("六年级", 1), ("五年级", 1)]
# 无年级标签时按知识点判定学段（只收能确定的）
KP_GRADE = [("导数", 7), ("圆锥曲线", 6), ("数列与数学归纳法", 6), ("排列组合与概率", 6), ("立体几何", 6),
            ("复数与平面向量", 5), ("三角函数", 5), ("集合", 5), ("基本初等函数", 5), ("函数", 5)]
# 竞赛 / 奥数来源：只在小学保留（奥数是小学常态），初中及以上视为超纲
COMPETITION = re.compile(r"联赛|奥林匹克|奥赛|AMC|IMO|CMO|自主招生")
# 一眼题：单步纯计算
TRIVIAL = re.compile(r"^(计算|求)?[：:]?\s*[\d\s+\-×÷*/().=?？]+[=＝]?\s*[?？（(]?\s*[)）]?$")


def tal_grade(r):
    src = " ".join(r.get("competition_source_list") or [])
    for k, g in SRC_GRADE:
        if k in src:
            return g, src
    kp = " ".join(r.get("knowledge_point_routes") or [])
    if "竞赛->知识点" in kp or "课内体系->知识点" in kp:
        for k, g in KP_GRADE:
            if k in kp:
                return g, src
    return None, src


def topic_of(routes):
    """知识点路径 → 模块名（用于换题互斥）。取第 3 段，如「函数」「数列与数学归纳法」。"""
    if not routes:
        return "综合"
    parts = routes[0].split("->")
    return parts[2] if len(parts) > 2 else parts[-1]


def tal_keep(r, g, src, text):
    d = int(r.get("difficulty") or 0)
    if d == 0 or d >= 4:
        return "difficulty"                       # 过易 / 竞赛压轴
    if g >= 3 and d < 2 and len(text) < 40:
        return "easy_for_grade"                   # 初二起：低难度短题视为过易
    if d == 3 and g not in (1, 4, 7):
        return "too_hard"                         # 难题只在小学奥数 / 高三保留
    if g >= 2 and COMPETITION.search(src) and not re.search(r"期中|期末|月考|单元测试|学年", src):
        return "competition"
    if len(re.sub(r"\s", "", text)) < 18 or TRIVIAL.match(text):
        return "trivial"
    return None


# AGIEval 学科 → (学科名, 选科)；数学所有人都出
AGI_FILES = [("gaokao-mathqa.jsonl", "数学", "ALL"), ("gaokao-physics.jsonl", "物理", "SCIENCE"),
             ("gaokao-chemistry.jsonl", "化学", "SCIENCE"), ("gaokao-biology.jsonl", "生物", "SCIENCE"),
             ("gaokao-geography.jsonl", "地理", "HUMANITIES"), ("gaokao-history.jsonl", "历史", "HUMANITIES")]
# 高二已学的高考数学知识（其余只放高三）
SENIOR2_OK = re.compile(r"数列|等差|等比|椭圆|双曲线|抛物线|直线|圆|向量|三角|sin|cos|tan|集合|不等式|函数")
SENIOR2_NOT = re.compile(r"导数|f′|f'|极值|单调区间|切线|积分|∫|围成|面积为|概率|分布|期望|二项|复数|排列|组合|充分|必要")
# 文综只保留材料推理类
REASONING = re.compile(r"材料|说明|反映|表明|体现|原因|影响|据此|推断|可知|意在|目的")


def opts_of(raw_opts):
    """统一为 ['A. xxx', ...]；任一选项转不干净返回 None。"""
    out = []
    for i, o in enumerate(raw_opts):
        t = to_text(re.sub(r"^\s*\(?[A-H][).．、]\s*", "", o))
        if not t or len(t) > 80:
            return None
        if re.search(r"[a-zA-Z0-9√)]\s*[√a-z]{2,}.*[√)][a-z0-9√]", t) and not re.search(r"[<>≤≥=,，、]", t) and len(t) > 12:
            return None
        out.append(f"{chr(65 + i)}. {t}")
    return out


def load_tal(drop):
    items = []
    for n in ["ch_single_choice_test_2K.jsonl", "ch_single_choice_train_3K.jsonl"]:
        for r in fetch(TAL + n, n):
            g, src = tal_grade(r)
            if g is None:
                drop["no_grade"] += 1; continue
            opts = r.get("answer_option_list") or []
            if not 3 <= len(opts) <= 5:
                drop["option_count"] += 1; continue
            q = to_text(r.get("problem"))
            o = opts_of([x[0]["content"] for x in opts])
            if not q or not o:
                drop["latex"] += 1; continue
            why = tal_keep(r, g, src, q)
            if why:
                drop[why] += 1; continue
            ans = r.get("answer_value", "")
            if len(ans) != 1 or ord(ans) - 65 >= len(o):
                drop["answer"] += 1; continue
            exp = to_text((r.get("answer_analysis") or [""])[0]) or ""
            items.append(dict(g=g, s="数学", st="ALL", t=topic_of(r.get("knowledge_point_routes")),
                              d=int(r["difficulty"]), q=q, o=o, a=ans, e=exp[:300], src="TAL-SCQ5K"))
    return items


def load_agi(drop):
    items = []
    for fname, subj, stream in AGI_FILES:
        for r in fetch(AGI + fname, fname):
            label = r.get("label")
            ans = "".join(sorted(label)) if isinstance(label, list) else (label or "")
            if not re.fullmatch(r"[A-D]{1,4}", ans):
                drop["agi_answer"] += 1; continue
            if r.get("passage"):
                drop["agi_passage"] += 1; continue
            q = to_text(r.get("question"))
            o = opts_of(r.get("options") or [])
            if not q or not o or len(o) != 4:
                drop["agi_latex"] += 1; continue
            if len(q) > 260:
                drop["agi_long"] += 1; continue
            if stream == "HUMANITIES" and not REASONING.search(q):
                drop["agi_recall"] += 1; continue
            # 高二 / 高三：未学内容只进高三
            g = 6 if subj == "数学" and SENIOR2_OK.search(q) and not SENIOR2_NOT.search(q) else 7
            src = (r.get("other") or {}).get("source", "高考")
            exp = to_text(r.get("answer")) or ""
            items.append(dict(g=g, s=subj, st=stream, t=subj, d=2, q=q, o=o, a=ans,
                              e=(exp[:300] or f"本题出自{src}"), src=src))
    return items


def main():
    drop = collections.Counter()
    items = load_tal(drop) + load_agi(drop)
    # 去重
    seen, uniq = set(), []
    for it in items:
        k = re.sub(r"\s", "", it["q"])[:60]
        if k not in seen:
            seen.add(k); uniq.append(it)
    # 难题占比上限：每年级难度 3 不超过 10%
    random.seed(20261002)
    final = []
    for g in range(1, 9):
        rows = [x for x in uniq if x["g"] == g]
        hard = [x for x in rows if x["d"] >= 3]
        keep_hard = hard[:max(0, len([x for x in rows if x["d"] < 3]) // 9)]
        drop["hard_cap"] += len(hard) - len(keep_hard)
        final += [x for x in rows if x["d"] < 3] + keep_hard
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    # 直接写未压缩 JSON：aapt 会把 .gz 资源改名（question_bank.json.gz -> question_bank.json），
    # 曾因此导致运行时按原名打开失败、静默回退到计算题。
    with open(OUT, "w", encoding="utf-8") as fp:
        json.dump(final, fp, ensure_ascii=False, separators=(",", ":"))
    open(LIC, "w", encoding="utf-8").write(
        "本应用答题题库整理自以下开源数据集（MIT License）：\n"
        "1. TAL-SCQ5K，好未来（TAL Education），https://github.com/math-eval/TAL-SCQ5K\n"
        "2. AGIEval（高考部分），Microsoft，https://github.com/ruixiangcui/AGIEval\n"
        "题目经过年级判定、难度筛选与公式格式转换。\n")
    names = {1: "小学", 2: "初一", 3: "初二", 4: "初三", 5: "高一", 6: "高二", 7: "高三", 8: "大学"}
    print("最终题量", len(final), "文件", os.path.getsize(OUT) // 1024, "KB")
    for g in range(1, 9):
        rows = [x for x in final if x["g"] == g]
        print(f"  {names[g]}: {len(rows):4d}  学科{dict(collections.Counter(x['s'] for x in rows))}  "
              f"难度{dict(sorted(collections.Counter(x['d'] for x in rows).items()))}  "
              f"多选{sum(1 for x in rows if len(x['a']) > 1)}")
    print("丢弃原因", dict(drop.most_common()))


if __name__ == "__main__":
    main()
