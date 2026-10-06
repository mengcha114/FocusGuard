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
ROTATE_BUCKETS = {"小学": [4, 5, 6], "初中": [7, 8, 9], "高中": [10, 11, 12]}


def rotate_grade(level):
    buckets = ROTATE_BUCKETS.get(level, [5])
    i = _tal_seq.get(level, 0)
    _tal_seq[level] = i + 1
    return buckets[i % len(buckets)]


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
    "\\leqslant": "≤", "\\leq": "≤", "\\le": "≤",
    "\\geqslant": "≥", "\\geq": "≥", "\\ge": "≥", "\\neq": "≠",
    "\\begin": "", "\\end": "", "\\cases": "", "\\array": "", "\\matrix": "",
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


def normalize_answer(ans):
    """把答案归一化成纯字母串：['A'] / ["A","B"] / A / ab 等都归一。"""
    if ans is None:
        return ""
    t = str(ans).strip()
    t = t.replace("[", " ").replace("]", " ").replace("{", " ").replace("}", " ")
    t = t.replace("'", " ").replace('"', " ").replace(",", " ").replace("，", " ")
    letters = re.findall(r"[A-Ea-e]", t)
    if letters:
        return "".join(sorted({c.upper() for c in letters}))
    # 不是字母：原样返回（可能数字/文本），由调用方决定是否丢弃
    return t.strip().upper()


def answer_letter_by_content(ans, opts):
    """TAL 的 answer_value 常是"内容"（如 3、45元）⇒ 找出内容匹配的选项字母。"""
    if not ans or not opts:
        return ""
    target = re.sub(r"\s+", "", str(ans)).strip()
    for o in opts:
        body = re.sub(r"^[A-E][.、．)）]\s*", "", str(o))
        if re.sub(r"\s+", "", body) == target:
            m = re.match(r"^([A-E])", str(o).strip())
            if m:
                return m.group(1)
    return ""


def split_inline_options(text):
    """从题干里切出内联选项（支持 A. / A． / A、 / A) / （A） / tab 分隔 / 同一行）。"""
    if not text:
        return None
    t = str(text)
    # 找出所有“字母 + 分隔符”的位置，且字母必须从 A 开始连续
    marks = []
    for m in re.finditer(r"[（(]?\s*([A-E])\s*[.、．:：)）]\s*", t):
        marks.append((m.group(1), m.start(), m.end()))
    if len(marks) < 2:
        return None
    seq = [x for x in marks if x[0] == "A"]
    if not seq:
        return None
    start = seq[0][1]
    picked = []
    expect = ord("A")
    for letter, st, en in marks:
        if st < start:
            continue
        if ord(letter) == expect:
            picked.append((letter, st, en))
            expect += 1
    if len(picked) < 2:
        return None
    head = t[:picked[0][1]].strip()
    opts = []
    for i, (letter, st, en) in enumerate(picked):
        end = picked[i + 1][1] if i + 1 < len(picked) else len(t)
        body = re.sub(r"\s+", " ", t[en:end]).strip()
        if not body:
            return None
        opts.append(letter + ". " + body)
    return head, opts


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

# ── 章节知识点 → (年级, 学期)。年级：10=高一 11=高二 12=高三；学期 1=上 2=下 0=不限 ──
# 只收**课内**章节；奥数/思维类（拓展思维·能力·思想·素养）不分年级，用 g=0（任意年级）。
CHAPTER_GRADE = {
    # ── 小学课内（两级键）──
    "数与运算|混合运算": (4, 2), "数与运算|加法": (3, 1), "数与运算|乘法": (4, 1),
    "数与运算|除法": (4, 2), "数与运算|数的运算": (4, 2), "数的认识|认、读、写数": (4, 1),
    "数的认识|数的特征": (5, 2), "数的认识|比较大小": (4, 1), "测量|面积": (6, 1),
    "数学广角|鸡兔同笼": (4, 2), "数学广角|鸽巢问题": (6, 2), "数学广角|排列组合": (5, 2),
    "式与方程|简易方程": (5, 1), "式与方程|数量关系": (5, 1), "常见的量|钟表": (2, 2),
    # ── 初中（两级键；一次函数/二次函数/反比例函数属初中，函数图像与性质属高中）──
    "数|有理数": (7, 1), "数|实数": (7, 2), "数与式|数的运算": (7, 1),
    "式|整式的加减": (7, 1), "式|整式的乘除": (8, 1), "式|因式分解": (8, 1),
    "式|分式": (8, 1), "式|二次根式": (8, 2),
    "方程与不等式|一元一次方程": (7, 1), "方程与不等式|二元一次方程（组）": (7, 2),
    "方程与不等式|不等式（组）": (7, 2), "方程与不等式|分式方程": (8, 2),
    "方程与不等式|一元二次方程": (9, 1), "方程与不等式|其他方程": (8, 2),
    "几何图形初步|几何图形": (7, 1), "几何图形初步|直线、射线、线段": (7, 1),
    "几何图形初步|角": (7, 1), "几何图形初步|相交线与平行线": (7, 2),
    "几何图形初步|命题与证明": (8, 1), "三角形|三角形及多边形": (7, 2),
    "三角形|全等三角形": (8, 1), "三角形|等腰三角形": (8, 1), "三角形|直角三角形": (8, 1),
    "三角形|勾股定理及应用": (8, 2), "三角形|相似三角形": (9, 2),
    "三角形|锐角三角函数及解直角三角形": (9, 2), "四边形|特殊平行四边形": (8, 2),
    "四边形|梯形": (8, 2), "四边形|平面向量": (10, 2), "几何变换|轴对称": (8, 1),
    "函数|一次函数": (8, 2), "函数|反比例函数": (9, 2), "函数|二次函数": (9, 1),
    "函数|函数概念和图象": (8, 2), "函数|平面直角坐标系": (7, 2),
    "统计与概率|数据的分析": (8, 2), "统计与概率|概率": (9, 1),
    "综合与实践|规律探究与程序框图": (8, 1), "综合与实践|新定义": (9, 1),
    "圆|圆与多边形": (9, 1), "圆与多边形|正多边形与圆": (9, 1),
    # ── 高中（人教 A 版顺序；两级键）──
    "函数|函数的概念及其表示": (10, 1), "函数|函数的概念": (10, 1),
    "函数|函数的概念与性质": (10, 1), "函数|函数的图像与性质": (10, 1),
    "函数|函数的性质": (10, 1), "函数|单调性": (10, 1), "函数|奇偶性": (10, 1),
    "函数|周期性": (10, 1), "函数|对称性": (10, 1), "函数|函数的应用": (10, 1),
    "函数|函数的零点": (10, 1), "函数|函数的值域": (10, 1), "函数|函数综合": (10, 1),
    "函数的概念与性质|函数的概念及其表示": (10, 1), "函数的概念与性质|函数的性质": (10, 1),
    "函数的应用|函数的实际应用": (10, 1), "函数的应用|函数的零点": (10, 1),
    "基本初等函数|对数函数": (10, 1), "基本初等函数|对数的概念及其运算": (10, 1),
    "基本初等函数|指数函数": (10, 1), "基本初等函数|指对幂函数": (10, 1),
    "三角函数|三角函数的概念": (10, 1), "三角函数|三角函数的图象与性质": (10, 1),
    "三角函数|三角恒等变换": (10, 1), "三角函数|反三角函数": (10, 1),
    "解三角形|正弦定理和余弦定理": (10, 2), "解三角形|三角形面积公式": (10, 2),
    "平面向量|平面向量的运算": (10, 2), "平面向量|平面向量基本定理及其坐标表示": (10, 2),
    "复数|复数的概念及几何意义": (10, 2), "复数|复数的运算": (10, 2),
    "立体几何初步|基本立体图形": (10, 2), "立体几何初步|基本图形位置关系": (10, 2),
    "统计与概率|统计": (10, 2), "统计与概率|随机变量": (12, 1),
    "计数原理|两个基本计数原理": (12, 1), "计数原理|排列与组合": (12, 1),
    "计数原理|二项式定理": (12, 1), "排列组合与概率|排列与组合": (12, 1),
    "数列|等差数列": (11, 2), "数列|等比数列": (11, 2), "数列|数列的概念": (11, 2),
    "数列与数学归纳法|数列的通项与求和": (11, 2), "数列与数学归纳法|等差数列与等比数列": (11, 2),
    "导数模块|导数": (11, 2), "导数模块|积分": (11, 2),
    "直线和圆的方程|直线与方程": (11, 1), "直线和圆的方程|圆与方程": (11, 1),
    "圆锥曲线|椭圆": (11, 1), "圆锥曲线|双曲线": (11, 1), "圆锥曲线|抛物线": (11, 1),
    "解析几何|直线与圆锥曲线": (11, 1), "解析几何|圆与方程": (11, 1),
    "立体几何与空间向量|空间向量": (11, 1), "立体几何与空间向量|空间中的角与距离": (11, 1),
    "集合|集合的概念与运算": (10, 1), "集合|集合的基本关系": (10, 1),
    "等式与不等式|不等式": (10, 1), "等式与不等式|等式": (10, 1),
    "常用逻辑用语|充分条件与必要条件": (10, 1), "多项式与方程|解方程（组）": (11, 1),
}
# 一级键里只有这些是无歧义的（其余一律不许用一级键兜底，避免"函数"这类跨学段误标）
UNAMBIGUOUS_1LEVEL = {
    "集合": (10, 1), "三角函数": (10, 1), "数列": (11, 2), "计数原理": (12, 1),
    "圆锥曲线": (11, 1), "导数模块": (11, 2), "复数与平面向量": (10, 2),
    "立体几何与空间向量": (11, 1), "解析几何": (11, 1), "等式与不等式": (10, 1),
    "立体几何初步": (10, 2), "数列与数学归纳法": (11, 2), "排列组合与概率": (12, 1),
    "平面向量": (10, 2), "复数": (10, 2), "常用逻辑用语": (10, 1), "基本初等函数": (10, 1),
}

# 奥数 / 思维 / 能力类：不分年级也不分学期（g=0 表示"任意年级可用"）
# 高考真题：高三下
GAOKAO_GRADE_SEM = (12, 0)      # 不限学期：高考真题是全学年复习材料

# App 的年级档位（GradeStore.Grade.level）：小学=1 初中=2/3/4 高中=5/6/7 大学=8
# QuestionBank.Item 的注释明确要求 g 与 GradeStore.Grade.level 一致，库内一律用这套档位。
GRADE_TO_APP = {1: 1, 2: 1, 3: 1, 4: 1, 5: 1, 6: 1,
                7: 2, 8: 3, 9: 4,
                10: 5, 11: 6, 12: 7,
                13: 8}          # 13 = 大学及以上（App 的 level 8）

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


# 高中专属内容特征（出现在题干里 ⇒ 该题至少是高中）
SENIOR_ONLY = re.compile(
    r"f\s*\(\s*x\s*\)|f\s*[′']\s*\(|导函数|椭圆|双曲线|抛物线|数学归纳法"
    r"|空间向量|立体几何|正弦定理|余弦定理|对数函数|指数函数|复数[zi]|等比数列的通项"
    # 高中数列记号：a_(n) / S_(n) / aₙ / S₁₀ 这类下标记法小学奥数不用
    r"|[aA]\s*[_₍]\s*\(?\s*n\s*\)?|[sS]\s*[_₍]\s*\(?\s*n\s*\)?|的前\s*n\s*项和"
)


def teach_grade(grade, q):
    """护栏：命中高中专属内容却标在 9 年级及以下 ⇒ 抬到 10 年级（宁高不低，避免发给低年级）。"""
    if grade <= 9 and SENIOR_ONLY.search(q or ""):
        return 10
    return grade


def make(grade, subject, q, opts, ans, exp, diff, src, module="", sem=0):
    if not q or not ans:
        return None
    if src != "CMMLU":
        # 非 CMMLU：必须带解析（用户要求"必须有详细的解题过程"）
        if not exp or len(exp) < 20:
            return None
    # CMMLU 无解析：按用户拍板放行（App 侧会提供"用 AI 生成解析"）
    cap = 1200 if subject in LONG_TEXT_SUBJECTS else 500
    if len(q) > cap:
        return None
    if IMG_PAT.search(q) or IMG_PAT.search(exp):
        return None
    # 残留的表格/环境标记会让题干读不懂（如 "tabular|c|c|c| & 目的 & 操作" / "方程组cases x+y=12"）
    unreadable = re.compile(r"tabular|cases|\\begin|\\end|\|c\|\||\|l\||\|r\||&\s*&|mathord|hfill")
    if unreadable.search(q) or unreadable.search(str(ans)):
        return None
    if "\\frac" in q or "\\dfrac" in q or "\\tfrac" in q or "\\frac" in exp:
        return None                                  # 还有没转干净的公式：宁缺勿滥
    if diff < 3:
        return None      # 只收中难以上（用户要求"偏难"，不要基础题）
    # App 的难度语义是 1 易 / 2 中 / 3 难（ChallengeGenerator 传 maxDifficulty=3），
    # 我们的 3=中难、4=期末/高考、5=压轴 统一压到 2/3 两档，否则 4/5 永远抽不到
    diff = 2 if diff <= 3 else 3
    st = "SCIENCE" if subject in SCIENCE else ("HUMANITIES" if subject in HUMANITIES else "ALL")
    # t = 知识点/主题（App 的 QuestionBank 读它当 topic；题型由 App 按 o/a 自行判定）
    topic = module if module else subject
    # 答案归一化 + 与选项一致性校验（有选项 ⇒ 答案必须是选项字母）
    # 判断题（只有 2 个选项）太简单，直接丢掉
    if opts and len(opts) < 3:
        return None
    # 理科"纯考概念且过简单"的题：既没有数字/运算符，题干又很短 ⇒ 丢弃
    if subject in ("数学", "物理", "化学", "生物") and opts:
        has_number = bool(re.search(r"\d", q))
        has_symbol = any(sym in q for sym in ("=", "+", "-", "×", "÷", "≤", "≥", "√", "^", "/", "%"))
        if not has_number and not has_symbol and len(q) < 40:
            return None
    if opts:
        norm = normalize_answer(ans)
        if not re.fullmatch(r"[A-E]{1,5}", norm):
            return None
        letters_in_opts = {re.match(r"^([A-E])", str(o).strip()).group(1)
                           for o in opts if re.match(r"^([A-E])", str(o).strip())}
        if not set(norm) <= letters_in_opts:
            return None
        ans = norm
    else:
        norm = normalize_answer(ans)
        ans = norm
    grade = teach_grade(grade, q)
    grade = GRADE_TO_APP.get(grade, 1)
    return {
        "g": grade, "sem": sem, "s": subject, "st": st, "t": topic, "d": diff,
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
                split = split_inline_options(q)
                if split:
                    head, opts = split
                else:
                    na = normalize_answer(ans)
                    if not re.fullmatch(r"[0-9A-Za-z+\-*/.,%=() ]{1,20}", na):
                        continue          # 无选项又敲不出来 ⇒ 丢弃
                    head, opts = q, []
            m = make(GAOKAO_GRADE_SEM[0], subject, head, opts, ans, exp, 4,
                     "GAOKAO-Bench", sem=GAOKAO_GRADE_SEM[1])
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
            # 真实年级：先按"章节(两级/一级)"查表，查不到再看是不是奥数/思维类
            key2 = (flat[2] + "|" + flat[3]) if len(flat) > 3 else ""
            key1 = flat[2] if len(flat) > 2 else ""
            hit = CHAPTER_GRADE.get(key2) or UNAMBIGUOUS_1LEVEL.get(key1)
            if hit:
                grade, sem = hit
            elif root in ("拓展思维", "美国amc8", "美国AMC8", "海外竞赛体系", "Overseas Competition"):
                # 奥数/竞赛：没有学期、也不该发给低年级 ⇒ 用学段中位年级
                # （小学=5、初中=8；配合 App 的"借下一级"覆盖 4~6 / 7~9）
                grade, sem = rotate_grade(level), 0
            elif root == "小升初":
                grade, sem = 6, 2
            elif flat and flat[-1] in ("思想", "能力", "七大能力", "素养", "学习能力"):
                grade, sem = rotate_grade(level), 0
            else:
                grade, sem = {"小学": 5, "初中": 8, "高中": 11}.get(level, 5), 0
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
            # 英文竞赛原题（Math League / AMC / AIME / Think Cup 等）与中文占比过低的题一律丢弃
            if cjk_count(problem) < 15 or re.search(
                r"Math League|Math kangaroo|Mathematical Olympiad|Question *#|World Mathematical"
                r"|\bAMC\b|AMC *\d|\bAIME\b|Think Cup|Purple Comet|MathCounts|\bCEMC\b"
                r"|\bSASMO\b|\bWMO\b|\bKangaroo\b",
                problem, re.I,
            ) or cjk_count(problem) / max(1, len(problem)) < 0.25:
                continue
            if opts and not re.fullmatch(r"[A-E]{1,5}", normalize_answer(ans)):
                alt = answer_letter_by_content(ans, opts)
                if alt:
                    ans = alt
            m = make(grade, "数学", problem, opts if opts else None,
                     ans, analysis, diff, "TAL-SCQ5K", module, sem)
            if m:
                items.append(m)
    return items


# CMMLU 科目 → (中文学科名, 学段组)：小学=1；高中=按 5/6/7 轮转；大学=8
CMMLU_MAP = {
    # —— 小学（level 1）——
    "elementary_chinese": ("语文", "小学"),
    "elementary_mathematics": ("数学", "小学"),
    "elementary_commonsense": ("常识", "小学"),
    "elementary_information_and_technology": ("信息技术", "小学"),
    # —— 高中课内（level 5/6/7 轮转）——
    "high_school_mathematics": ("数学", "高中"),
    "high_school_physics": ("物理", "高中"),
    "high_school_chemistry": ("化学", "高中"),
    "high_school_biology": ("生物", "高中"),
    "high_school_geography": ("地理", "高中"),
    "high_school_politics": ("政治", "高中"),
    # —— 高中语文/历史/政治/通识类 ——
    "ancient_chinese": ("语文", "高中"), "modern_chinese": ("语文", "高中"),
    "chinese_literature": ("语文", "高中"), "chinese_history": ("历史", "高中"),
    "world_history": ("历史", "高中"), "chinese_foreign_policy": ("政治", "高中"),
    "marxist_theory": ("政治", "高中"), "legal_and_moral_basis": ("政治", "高中"),
    "philosophy": ("政治", "高中"), "logical": ("数学", "高中"),
    "global_facts": ("常识", "高中"), "chinese_food_culture": ("常识", "高中"),
    "sports_science": ("生物", "高中"), "arts": ("常识", "高中"),
    "sociology": ("政治", "高中"), "ethnology": ("历史", "高中"),
    "journalism": ("语文", "高中"), "public_relations": ("语文", "高中"),
    "conceptual_physics": ("物理", "高中"), "world_religions": ("历史", "高中"),
    # —— 大学及以上（level 8）——
    "college_mathematics": ("高等数学", "大学"), "college_education": ("教育学", "大学"),
    "college_law": ("法学", "大学"), "college_medicine": ("医学", "大学"),
    "college_actuarial_science": ("精算", "大学"), "college_engineering_hydrology": ("水利工程", "大学"),
    "college_medical_statistics": ("医学统计", "大学"),
    "agronomy": ("农学", "大学"), "anatomy": ("解剖学", "大学"),
    "astronomy": ("天文学", "大学"), "business_ethics": ("商业伦理", "大学"),
    "clinical_knowledge": ("临床医学", "大学"), "computer_science": ("计算机", "大学"),
    "computer_security": ("网络安全", "大学"), "construction_project_management": ("工程管理", "大学"),
    "economics": ("经济学", "大学"), "electrical_engineering": ("电气工程", "大学"),
    "genetics": ("遗传学", "大学"), "international_law": ("国际法", "大学"),
    "management": ("管理学", "大学"), "marketing": ("市场营销", "大学"),
    "nutrition": ("营养学", "大学"), "professional_accounting": ("会计学", "大学"),
    "professional_law": ("法律职业", "大学"), "professional_medicine": ("临床职业", "大学"),
    "professional_psychology": ("心理学", "大学"), "security_study": ("安全研究", "大学"),
    "traditional_chinese_medicine": ("中医学", "大学"), "virology": ("病毒学", "大学"),
    "food_science": ("食品科学", "大学"), "machine_learning": ("机器学习", "大学"),
    "jurisprudence": ("法理学", "大学"), "human_sexuality": ("人类性学", "大学"),
    "chinese_teacher_qualification": ("教师资格", "大学"), "education": ("教育学", "大学"),
}
# 只保留"课本知识"科目（用户反馈：出现不属于课本知识的内容，比如生物里的杂学）
CMMLU_KEEP = {
    "elementary_chinese", "elementary_mathematics", "elementary_commonsense",
    "elementary_information_and_technology",
    "high_school_mathematics", "high_school_physics", "high_school_chemistry",
    "high_school_biology", "high_school_geography", "high_school_politics",
    "ancient_chinese", "modern_chinese", "chinese_literature", "chinese_history",
    "world_history", "chinese_foreign_policy", "marxist_theory",
    "legal_and_moral_basis", "philosophy", "logical", "conceptual_physics",
}
# 明确排除：非课内 / 专业 / 通识杂学 / 驾考与公务员考试
CMMLU_EXCLUDE = {"chinese_driving_rule", "chinese_civil_service_exam"}
def cmmlu_keep(key):
    return key in CMMLU_KEEP
CMMLU_SCIENCE = {"物理", "化学", "生物", "科学", "医学", "解剖学", "病毒学", "遗传学",
                 "临床医学", "中医学", "营养学", "农学", "天文学", "水利工程"}
CMMLU_HUMANITIES = {"历史", "地理", "政治", "法学", "法理学", "国际法", "法律职业",
                    "经济学", "管理学", "市场营销", "会计学", "商业伦理", "社会学"}


def load_cmmlu():
    """CMMLU：GitHub 仓库就是其 HF 数据集的镜像（本机只能走 GitHub）。无解析，按用户拍板放行。"""
    api = "https://api.github.com/repos/haonan-li/CMMLU/git/trees/master?recursive=1"
    paths = []
    try:
        tree = json.loads(http_get(api) or "{}")
        paths = [e["path"] for e in tree.get("tree", [])
                 if e.get("path", "").startswith("data/test/") and e["path"].endswith(".csv")]
    except Exception:
        paths = []
    if not paths:      # 兜底：目录接口（tree 接口偶发失败时）
        try:
            lst = json.loads(http_get(
                "https://api.github.com/repos/haonan-li/CMMLU/contents/data/test") or "[]")
            paths = ["data/test/" + x["name"] for x in lst if x["name"].endswith(".csv")]
        except Exception:
            paths = []
    if not paths:
        print("    （CMMLU：目录获取失败）")
        return []
    # 先在本地缓存里补齐缺的 CSV（8 线程并行；串行 67 个太慢）
    cache_dir = os.path.join(TMP, "cmmlu")
    os.makedirs(cache_dir, exist_ok=True)

    def ensure_csv(path_item):
        name = path_item.split("/")[-1].replace(".csv", "")
        cp = os.path.join(cache_dir, name + ".csv")
        if os.path.exists(cp) and os.path.getsize(cp) > 200:
            return
        raw_text = http_get("https://raw.githubusercontent.com/haonan-li/CMMLU/master/" + path_item)
        if raw_text:
            try:
                with open(cp, "w", encoding="utf-8") as fh:
                    fh.write(raw_text)
            except Exception:
                pass

    try:
        import concurrent.futures as _cf
        with _cf.ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(ensure_csv, paths))
    except Exception:
        for pth in paths:
            ensure_csv(pth)

    out, skipped, idx = [], [], 0
    for path in sorted(paths):
        key = path.split("/")[-1].replace(".csv", "")
        if key in CMMLU_EXCLUDE or not cmmlu_keep(key):
            skipped.append(key)
            continue
        info = CMMLU_MAP.get(key)
        if info is None:
            skipped.append(key)
            continue
        cn, band = info
        # 注意：这里的年级是"12 级标尺"，make() 里会统一映射到 App 档位（1~8）
        if band == "小学":
            grade, sem, diff = 2, 0, 2          # 小学课内偏基础 ⇒ 见下方难度门槛会被剔除
        elif band == "高中":
            grade, sem, diff = (10, 11, 12)[idx % 3], 0, 4   # 高中课内/高考风格 ⇒ 难
            idx += 1
        else:
            grade, sem, diff = 13, 0, 4         # 大学及以上 ⇒ App level 8

        raw = http_get("https://raw.githubusercontent.com/haonan-li/CMMLU/master/" + path)
        if not raw:
            skipped.append(key + "(下载失败)")
            continue
        for row in csv_reader(raw):
            q = clean(row.get("Question", ""))
            if len(q) < 8:
                continue
            opts = []
            for L in ("A", "B", "C", "D"):
                body = clean(row.get(L, ""))
                if body:
                    opts.append(L + ". " + body)
            if len(opts) < 2:
                continue
            m = make(grade, cn, q, opts, row.get("Answer", ""), "", diff,
                     "CMMLU", module="", sem=sem)
            if m:
                out.append(m)
    if skipped:
        print("    （CMMLU 跳过 %d 个科目：%s）" % (len(skipped), "、".join(sorted(skipped)[:8])))
    return out


def csv_reader(text):
    """把 CMMLU 的 CSV 文本解析成 dict 列表（支持引号内的逗号/换行）。"""
    import csv as _csv
    import io
    try:
        return list(_csv.DictReader(io.StringIO(text)))
    except Exception:
        return []


def http_get(url):
    """构建期用的普通 GET（只读）。"""
    import urllib.request
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "FocusGuard-bank-builder"})
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.read().decode("utf-8-sig", errors="replace")
    except Exception:
        return ""


def load_legacy_agieval():
    """从历史题库里捞回的 AGIEval 高考真题（带解析）。年级=高三、不限学期、难度=难。"""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "legacy_agieval.json")
    if not os.path.exists(path):
        return []
    out = []
    try:
        legacy = json.load(open(path, encoding="utf-8"))
    except Exception:
        return []
    for x in legacy:
        m = make(12, x.get("s", "数学"), clean(x.get("q", "")),
                 (x.get("o") or None), x.get("a", ""), clean(x.get("e", "")), 4,
                 "AGIEval", sem=0)
        if m:
            out.append(m)
    return out


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
    for loader, name in ((load_gaokao_bench, "GAOKAO-Bench"), (load_tal, "TAL-SCQ5K"),
                         (load_legacy_agieval, "AGIEval(历史库)"),
                         (load_cmmlu, "CMMLU")):
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
    sem = collections.Counter("%d%s" % (x["g"], {0: "不限", 1: "上", 2: "下"}[x.get("sem", 0)])
                              for x in dedup if x["g"] != 0)
    print("  年纪+学期 Top12:", sem.most_common(12))
    print("  任意年级(g=0):", sum(1 for x in dedup if x["g"] == 0))
    s = collections.Counter(x["s"] for x in dedup)
    print("  学科:", dict(sorted(s.items(), key=lambda kv: -kv[1])))
    d = collections.Counter(x["d"] for x in dedup)
    print("  难度:", dict(sorted(d.items())))
    print("  来源:", dict(collections.Counter(x["src"] for x in dedup)))
    print("  主题 Top8:", collections.Counter(x["t"] for x in dedup).most_common(8))


if __name__ == "__main__":
    main()
