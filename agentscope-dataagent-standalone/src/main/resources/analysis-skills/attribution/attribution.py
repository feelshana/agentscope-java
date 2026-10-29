"""指标归因（加法分解）。

用法：python skills/attribution/attribution.py --input data/<文件>.csv --params '<json>'

params:
  metric      指标列名（必填）
  period      区分两期的列名（必填）
  current     当前期在 period 列中的取值（必填）
  base        对比期在 period 列中的取值（必填）
  dimensions  归因维度列名列表，1-4 个（必填）
  depth       下钻层数 1 或 2，默认 2
  top         每个维度最多列出的维度值个数，默认 8，其余合并为“其他”
  title       图表标题，可选
  additive    指标能否按维度值相加，默认 true；去重计数（人数、用户数）填 false，此时输入为长表：
              period 列、dimension_column（默认“维度”）、value_column（默认“维度值”）、指标列，
              每个维度单独去重计数，维度为“合计”的行给出两期总数；不做加和拆解和自动下钻

只读输入 CSV，不联网；相同输入得到相同输出。标准输出为一段 JSON 摘要，
图片和结果表写入 outputs/。
"""

import argparse
import csv
import json
import math
import os
import re
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib import font_manager  # noqa: E402

BLUE = "#3B6FB6"
ORANGE = "#E8743B"
GREEN = "#2E8B57"
GRAY = "#8A9099"


def setup_font():
    names = {f.name for f in font_manager.fontManager.ttflist}
    for name in ("Noto Sans CJK SC", "Noto Sans SC", "Microsoft YaHei", "SimHei", "PingFang SC", "WenQuanYi Zen Hei"):
        if name in names:
            plt.rcParams["font.sans-serif"] = [name, "DejaVu Sans"]
            break
    plt.rcParams["axes.unicode_minus"] = False


def fail(reason):
    print(json.dumps({"ok": False, "method": "指标归因", "reason": reason}, ensure_ascii=False))
    sys.exit(0)


def number(text):
    if text is None:
        return None
    value = str(text).strip().replace(",", "")
    if value == "":
        return None
    try:
        result = float(value)
    except ValueError:
        return None
    return result if math.isfinite(result) else None


def safe_name(text):
    return re.sub(r"[\\/:*?\"<>|\s]+", "_", text).strip("_")[:60] or "指标归因"


def fmt(value):
    return f"{value:,.0f}" if abs(value - round(value)) < 1e-9 else f"{value:,.2f}"


def read_rows(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def breakdown(rows, dim, metric, period, current, base):
    totals = {}
    for row in rows:
        key = row.get(dim) or "（空）"
        value = number(row.get(metric))
        if value is None:
            continue
        pair = totals.setdefault(key, [0.0, 0.0])
        if row.get(period) == base:
            pair[0] += value
        elif row.get(period) == current:
            pair[1] += value
    return totals


def items_of(totals, delta, top):
    items = [
        {"value": k, "base": b, "current": c, "delta": c - b}
        for k, (b, c) in totals.items()
    ]
    items.sort(key=lambda i: (-abs(i["delta"]), i["value"]))
    if len(items) > top:
        rest = items[top:]
        items = items[:top] + [{
            "value": "其他（合计）",
            "base": sum(i["base"] for i in rest),
            "current": sum(i["current"] for i in rest),
            "delta": sum(i["delta"] for i in rest),
        }]
    for item in items:
        item["contribution"] = round(item["delta"] / delta, 4) if delta else None
    return items


def score(totals):
    """Jensen-Shannon divergence between the two periods' shares (Adtributor's surprise):
    the dimension whose mix changed most explains the change best, regardless of how many
    values it has."""
    base_sum = sum(b for b, _ in totals.values())
    current_sum = sum(c for _, c in totals.values())
    if base_sum <= 0 or current_sum <= 0:
        return 0.0
    total = 0.0
    for b, c in totals.values():
        p, q = max(b, 0.0) / base_sum, max(c, 0.0) / current_sum
        m = (p + q) / 2
        if p > 0:
            total += 0.5 * p * math.log(p / m)
        if q > 0:
            total += 0.5 * q * math.log(q / m)
    return total


MAX_LABEL_CHARS = 16
SHORT_LABEL_CHARS = 6


def short(text, limit=MAX_LABEL_CHARS):
    text = str(text).replace("\n", " ")
    return text if len(text) <= limit else text[: limit - 1] + "…"


def period_text(value, role):
    """“2026-09-24 至 2026-09-30” → “09-24 至 09-30（对比期）”; keeps a label that already says the role."""
    text = str(value)
    if role in text:
        return text
    m = re.fullmatch(r"\s*(\d{4})-(\d{2}-\d{2})\s*至\s*(\d{4})-(\d{2}-\d{2})\s*", text)
    if m and m.group(1) == m.group(3):
        text = f"{m.group(2)} 至 {m.group(4)}"
    return f"{text}（{role}）"


def change_chart(path, title, base_label, current_label, names, base_total, steps, current_total):
    """Long names or many bars read best horizontally; short, few labels stay a column waterfall."""
    vertical = len(names) <= 6 and all(len(str(n)) <= SHORT_LABEL_CHARS for n in names)
    if vertical:
        waterfall(path, title,
                  [base_label.replace("（", "\n（")] + [str(n) for n in names] + [current_label.replace("（", "\n（")],
                  base_total, steps, current_total)
    else:
        horizontal_waterfall(path, title, [base_label] + [short(n) for n in names] + [current_label],
                             base_total, steps, current_total)


def horizontal_waterfall(path, title, labels, base_total, steps, current_total):
    """Bars run left to right, one row per label read top to bottom, so long names never overlap."""
    setup_font()
    rows = len(steps) + 2
    fig, ax = plt.subplots(figsize=(10, max(3.6, 0.48 * rows + 1.6)), dpi=150)
    starts, widths, colors = [0.0], [base_total], [BLUE]
    running = base_total
    for step in steps:
        starts.append(running if step >= 0 else running + step)
        widths.append(abs(step))
        colors.append(GREEN if step >= 0 else ORANGE)
        running += step
    starts.append(0.0)
    widths.append(current_total)
    colors.append(BLUE)
    ys = list(range(rows))[::-1]
    ax.barh(ys, widths, left=starts, color=colors, height=0.6)
    ends = [s + w for s, w in zip(starts, widths)]
    low = min(min(starts[1:-1] or [base_total]), current_total, base_total)
    high = max(ends)
    floor = 0.0
    if low > 0:
        raw = max(0.0, low - (high - low) * 0.6)
        unit = 10 ** max(0, int(math.log10(raw)) - 1) if raw >= 10 else 1
        floor = math.floor(raw / unit) * unit
    span = high - floor
    for i, y in enumerate(ys):
        if i in (0, rows - 1):
            text, color = fmt(widths[i]), "#1F2329"
        else:
            step = steps[i - 1]
            text, color = ("+" if step >= 0 else "−") + fmt(abs(step)), (GREEN if step >= 0 else "#A04A12")
        ax.text(ends[i] + span * 0.01, y, text, va="center", ha="left", fontsize=9, color=color)
        if i < rows - 1:
            level = sum([base_total] + steps[:i])
            ax.plot([level, level], [y - 0.3, y - 0.7], color=GRAY, linestyle="--", linewidth=0.8)
    ax.set_yticks(ys)
    ax.set_yticklabels(labels, fontsize=10)
    ax.set_xlim(floor, high + span * 0.14)
    ax.set_title(title, fontsize=14, fontweight="bold")
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(axis="x", alpha=0.3)
    if floor > 0:
        ax.text(1, 1.01, f"横轴自 {fmt(floor)} 起", transform=ax.transAxes,
                ha="right", va="bottom", fontsize=9, color=GRAY)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def waterfall(path, title, labels, base_total, steps, current_total):
    setup_font()
    fig, ax = plt.subplots(figsize=(10, 5.4), dpi=150)
    running = base_total
    xs = list(range(len(steps) + 2))
    lows = [base_total, current_total]
    ax.bar(0, base_total, color=BLUE, width=0.6)
    ax.text(0, base_total, fmt(base_total), ha="center", va="bottom", fontsize=10)
    for i, step in enumerate(steps, start=1):
        bottom = running if step >= 0 else running + step
        ax.bar(i, abs(step), bottom=bottom, color=GREEN if step >= 0 else ORANGE, width=0.6)
        label_y = running + step if step >= 0 else running + step
        ax.text(i, label_y, ("+" if step >= 0 else "−") + fmt(abs(step)),
                ha="center", va="bottom" if step >= 0 else "top", fontsize=10,
                color=GREEN if step >= 0 else "#A04A12")
        ax.plot([i - 1 + 0.3, i - 0.3], [running, running], color=GRAY, linestyle="--", linewidth=0.8)
        running += step
        lows.append(running)
    last = len(steps) + 1
    ax.plot([last - 1 + 0.3, last - 0.3], [running, running], color=GRAY, linestyle="--", linewidth=0.8)
    ax.bar(last, current_total, color=BLUE, width=0.6)
    ax.text(last, current_total, fmt(current_total), ha="center", va="bottom", fontsize=10)
    low = min(lows)
    high = max(max(lows), base_total, current_total)
    floor = 0.0
    if low > 0:
        raw = max(0.0, low - (high - low) * 0.6)
        unit = 10 ** max(0, int(math.log10(raw)) - 1) if raw >= 10 else 1
        floor = math.floor(raw / unit) * unit
    ax.set_ylim(floor, high + (high - floor) * 0.12)
    ax.set_xticks(xs)
    ax.set_xticklabels(labels, fontsize=10)
    ax.set_title(title, fontsize=14, fontweight="bold")
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(axis="y", alpha=0.3)
    if floor > 0:
        ax.text(1, 1.01, f"纵轴自 {fmt(floor)} 起", transform=ax.transAxes,
                ha="right", va="bottom", fontsize=9, color=GRAY)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def drill_chart(path, title, items):
    setup_font()
    shown = list(reversed(items))
    fig, ax = plt.subplots(figsize=(10, max(2.6, 0.5 * len(shown) + 1.4)), dpi=150)
    values = [i["delta"] for i in shown]
    ax.barh(range(len(shown)), values, color=[GREEN if v >= 0 else ORANGE for v in values])
    ax.set_yticks(range(len(shown)))
    ax.set_yticklabels([short(i["value"]) for i in shown], fontsize=10)
    for y, item in enumerate(shown):
        share = "" if item["contribution"] is None else f" · {item['contribution'] * 100:.1f}%"
        ax.text(item["delta"], y, (" +" if item["delta"] >= 0 else " −") + fmt(abs(item["delta"])) + share,
                va="center", ha="left" if item["delta"] >= 0 else "right", fontsize=9)
    ax.axvline(0, color=GRAY, linewidth=0.8)
    ax.set_title(title, fontsize=13, fontweight="bold")
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(axis="x", alpha=0.3)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def distinct_main(args, params):
    """去重指标（人数、用户数等 COUNT(DISTINCT)）：各维度值的去重数相加不等于总数，不做加和拆解。

    输入为长表：period 列、维度名列、维度值列、指标列；每个维度单独按“期间 × 维度值”去重计数，
    维度名为“合计”的行给出两期总数（同样在 SQL 中去重）。
    """
    metric, period = params.get("metric"), params.get("period")
    current, base = params.get("current"), params.get("base")
    dim_col = params.get("dimension_column") or "维度"
    value_col = params.get("value_column") or "维度值"
    wanted = params.get("dimensions") or []
    top = int(params.get("top") or 10)
    if not all([metric, period, current, base]):
        fail("缺少必填参数：metric、period、current、base")
    if not os.path.isfile(args.input):
        fail(f"找不到输入文件：{args.input}")
    rows = read_rows(args.input)
    if not rows:
        fail("输入数据为空")
    missing = [c for c in (metric, period, dim_col, value_col) if c not in rows[0]]
    if missing:
        fail("去重指标需要长表（期间、维度、维度值、指标）；缺少列：" + "、".join(missing)
             + "；现有列：" + "、".join(rows[0].keys()))
    totals = {"合计": {}}
    for row in rows:
        name, value = row.get(dim_col) or "", row.get(value_col) or "（空）"
        v = number(row.get(metric))
        if v is None or row.get(period) not in (current, base):
            continue
        pair = totals.setdefault(name, {}).setdefault(value, [0.0, 0.0])
        pair[0 if row.get(period) == base else 1] = v
    total = next(iter(totals["合计"].values()), None)
    if total is None:
        fail("缺少维度为“合计”的总数行：去重指标的两期总数必须由 SQL 单独去重计算，不能由各维度值相加得到")
    total_base, total_current = total
    delta = total_current - total_base
    dims = [d for d in totals if d != "合计" and (not wanted or d in wanted)]
    if not dims:
        fail("输入数据中没有可用于归因的维度")

    def items_for(values):
        items = [{"value": k, "base": b, "current": c, "delta": c - b,
                  "contribution": round((c - b) / delta, 4) if delta else None}
                 for k, (b, c) in values.items()]
        items.sort(key=lambda i: (-abs(i["delta"]), i["value"]))
        return items[:top]

    scores = {d: round(score(totals[d]), 6) for d in dims}
    main_dim = sorted(dims, key=lambda d: (-scores[d], dims.index(d)))[0]
    items = items_for(totals[main_dim])

    os.makedirs(args.outdir, exist_ok=True)
    title = params.get("title") or f"{metric}变化（按{main_dim}，各项不可相加）"
    stem = safe_name(title)
    chart = os.path.join(args.outdir, stem + ".png").replace("\\", "/")
    # Shares are not additive here, so the chart shows absolute changes only.
    drill_chart(chart, title, [dict(i, contribution=None) for i in items])
    table = os.path.join(args.outdir, stem + "_结果.csv").replace("\\", "/")
    with open(table, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["维度", "维度值", "对比期", "当前期", "变化", "变化占总变化"])
        for i in items:
            writer.writerow([main_dim, i["value"], i["base"], i["current"], i["delta"], i["contribution"]])
    print(json.dumps({
        "ok": True,
        "method": "去重指标归因（各维度值单独去重计数，按两期结构差异选择维度；不做加和拆解）",
        "additive": False,
        "metric": metric,
        "periods": {"current": current, "base": base},
        "total": {"base": total_base, "current": total_current, "delta": delta,
                  "delta_pct": round(delta / total_base, 4) if total_base else None},
        "dimension": main_dim,
        "dimension_scores": scores,
        "items": items,
        "drilldown": None,
        "checks": {"additive": False},
        "files": {"chart": chart, "table": table},
        "notes": ["去重指标：同一用户可能同时属于多个维度值，各项变化之和不等于总变化；"
                  "“变化占总变化”只用于排序参考，不是可相加的贡献度。",
                  "去重指标不做自动下钻；如需下钻，可对变化最大的维度值加过滤条件再归因一次。"],
    }, ensure_ascii=False))


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--params", required=True)
    parser.add_argument("--outdir", default="outputs")
    args = parser.parse_args(argv)
    try:
        params = json.loads(args.params)
    except json.JSONDecodeError as e:
        fail(f"params 不是合法 JSON：{e}")
    if params.get("additive") is False:
        distinct_main(args, params)
        return
    metric = params.get("metric")
    period = params.get("period")
    current = params.get("current")
    base = params.get("base")
    dims = params.get("dimensions") or []
    depth = int(params.get("depth") or 2)
    top = int(params.get("top") or 8)
    if not all([metric, period, current, base]) or not dims:
        fail("缺少必填参数：metric、period、current、base、dimensions")
    if len(dims) > 4:
        fail("归因维度最多 4 个")
    if not os.path.isfile(args.input):
        fail(f"找不到输入文件：{args.input}")
    rows = read_rows(args.input)
    if not rows:
        fail("输入数据为空")
    missing = [c for c in [metric, period] + list(dims) if c not in rows[0]]
    if missing:
        fail("输入数据缺少列：" + "、".join(missing) + "；现有列：" + "、".join(rows[0].keys()))
    labels = {r.get(period) for r in rows}
    if current not in labels or base not in labels:
        fail(f"period 列中找不到当前期“{current}”或对比期“{base}”，现有取值：" + "、".join(sorted(str(v) for v in labels)))

    total_base = sum(number(r.get(metric)) or 0.0 for r in rows if r.get(period) == base)
    total_current = sum(number(r.get(metric)) or 0.0 for r in rows if r.get(period) == current)
    delta = total_current - total_base
    scores = {}
    by_dim = {}
    for dim in dims:
        totals = breakdown(rows, dim, metric, period, current, base)
        by_dim[dim] = items_of(totals, delta, top)
        scores[dim] = round(score(totals), 6)
    main_dim = sorted(dims, key=lambda d: (-scores[d], dims.index(d)))[0]
    items = by_dim[main_dim]

    drill = None
    leader = next((i for i in items if i["value"] != "其他（合计）"), None)
    rest_dims = [d for d in dims if d != main_dim]
    if depth >= 2 and leader and rest_dims and leader["delta"]:
        subset = [r for r in rows if (r.get(main_dim) or "（空）") == leader["value"]]
        best = None
        for dim in rest_dims:
            sub_totals = breakdown(subset, dim, metric, period, current, base)
            s = score(sub_totals)
            if best is None or s > best[0]:
                best = (s, dim, items_of(sub_totals, leader["delta"], top))
        drill = {"parent_dimension": main_dim, "parent_value": leader["value"],
                 "dimension": best[1], "items": best[2]}

    os.makedirs(args.outdir, exist_ok=True)
    title = params.get("title") or f"{metric}变化拆解（按{main_dim}）"
    stem = safe_name(title)
    chart = os.path.join(args.outdir, stem + ".png").replace("\\", "/")
    shown = items[:6] + ([{"value": "其他（合计）", "delta": sum(i["delta"] for i in items[6:])}] if len(items) > 6 else [])
    change_chart(chart, title, period_text(base, "对比期"), period_text(current, "当前期"),
                 [i["value"] for i in shown], total_base, [i["delta"] for i in shown], total_current)
    files = {"chart": chart}
    if drill:
        drill_path = os.path.join(args.outdir, stem + "_下钻.png").replace("\\", "/")
        drill_chart(drill_path, f"下钻：{drill['parent_value']} → {drill['dimension']}", drill["items"])
        files["drill_chart"] = drill_path
    table = os.path.join(args.outdir, stem + "_结果.csv").replace("\\", "/")
    with open(table, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["层级", "维度", "维度值", "对比期", "当前期", "变化", "贡献度"])
        for i in items:
            writer.writerow([1, main_dim, i["value"], i["base"], i["current"], i["delta"], i["contribution"]])
        for i in (drill or {}).get("items", []):
            writer.writerow([2, drill["dimension"], i["value"], i["base"], i["current"], i["delta"], i["contribution"]])
    files["table"] = table

    result = {
        "ok": True,
        "method": "指标归因（加法分解：贡献度 = 维度值变化 ÷ 总变化）",
        "metric": metric,
        "periods": {"current": current, "base": base},
        "total": {"base": total_base, "current": total_current, "delta": delta,
                  "delta_pct": round(delta / total_base, 4) if total_base else None},
        "dimension": main_dim,
        "dimension_scores": scores,
        "items": items,
        "drilldown": drill,
        "checks": {"items_sum_to_total_delta": abs(sum(i["delta"] for i in items) - delta) < 1e-6},
        "files": files,
        "notes": [] if delta else ["两期总量相同，贡献度无法按比例计算，仅列出各维度值的变化量。"],
    }
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
