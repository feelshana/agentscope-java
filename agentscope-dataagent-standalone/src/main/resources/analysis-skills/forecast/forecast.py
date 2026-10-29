"""趋势预测（Holt-Winters 指数平滑）。

用法：python skills/forecast/forecast.py --input data/<文件>.csv --params '<json>'

params:
  date         时间列名（必填）
  metric       指标列名（必填）
  granularity  day（默认）/ week / month
  horizon      预测期数，默认 7，最多 60
  title        图表标题，可选

数据要求：按日至少 14 个点、按周至少 8 个、按月至少 12 个，不足时不预测并说明原因。
方法：加法趋势；按日数据（≥14 点）带周季节项，按月数据（≥24 点）带年季节项。平滑
参数在固定网格上按一步预测误差平方和最小选取，结果确定。预测区间由一步残差的标准差
近似估计（80% / 95%），历史数据全部非负时区间下限截断为 0。只读输入 CSV，不联网。
"""

import argparse
import calendar
import csv
import json
import math
import os
import re
import sys
from datetime import datetime, timedelta

import numpy as np
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib import font_manager  # noqa: E402

BLUE = "#3B6FB6"
ORANGE = "#E8743B"
DARK_ORANGE = "#C2541B"
GRAY = "#8A9099"
MIN_POINTS = {"day": 14, "week": 8, "month": 12}
FORMATS = ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d", "%Y/%m/%d", "%Y-%m", "%Y/%m", "%Y%m%d", "%Y%m")
GRID = (0.1, 0.3, 0.5, 0.7, 0.9)
TREND_GRID = (0.01, 0.1, 0.3)


def setup_font():
    names = {f.name for f in font_manager.fontManager.ttflist}
    for name in ("Noto Sans CJK SC", "Noto Sans SC", "Microsoft YaHei", "SimHei", "PingFang SC", "WenQuanYi Zen Hei"):
        if name in names:
            plt.rcParams["font.sans-serif"] = [name, "DejaVu Sans"]
            break
    plt.rcParams["axes.unicode_minus"] = False


def fail(reason, **extra):
    print(json.dumps({"ok": False, "method": "趋势预测", "reason": reason, **extra}, ensure_ascii=False))
    sys.exit(0)


def number(text):
    value = str(text if text is not None else "").strip().replace(",", "")
    if value == "":
        return None
    try:
        result = float(value)
    except ValueError:
        return None
    return result if math.isfinite(result) else None


def parse_time(text):
    value = re.sub(r"\.\d+$", "", str(text or "").strip().replace("T", " "))
    for f in FORMATS:
        try:
            return datetime.strptime(value, f)
        except ValueError:
            continue
    return None


def bucket(t, grain):
    t = t.replace(hour=0, minute=0, second=0, microsecond=0)
    if grain == "week":
        return t - timedelta(days=t.weekday())
    if grain == "month":
        return t.replace(day=1)
    return t


def advance(t, grain, steps):
    if grain == "day":
        return t + timedelta(days=steps)
    if grain == "week":
        return t + timedelta(weeks=steps)
    month = t.month - 1 + steps
    year = t.year + month // 12
    month = month % 12 + 1
    return t.replace(year=year, month=month, day=min(t.day, calendar.monthrange(year, month)[1]))


def label(t, grain):
    return t.strftime("%Y-%m") if grain == "month" else t.strftime("%Y-%m-%d")


def safe_name(text):
    return re.sub(r"[\\/:*?\"<>|\s]+", "_", text).strip("_")[:60] or "趋势预测"


def holt_winters(y, m, alpha, beta, gamma):
    """Additive Holt-Winters; returns one-step fitted values, final level/trend/season."""
    n = len(y)
    if m:
        level = float(np.mean(y[:m]))
        trend = float((np.mean(y[m:2 * m]) - np.mean(y[:m])) / m) if n >= 2 * m else 0.0
        season = [float(v - level) for v in y[:m]]
    else:
        level, trend, season = float(y[0]), float(y[1] - y[0]) if n > 1 else 0.0, []
    fitted = np.zeros(n)
    for i in range(n):
        s = season[i % m] if m else 0.0
        fitted[i] = level + trend + s
        last_level = level
        level = alpha * (y[i] - s) + (1 - alpha) * (level + trend)
        trend = beta * (level - last_level) + (1 - beta) * trend
        if m:
            season[i % m] = gamma * (y[i] - level) + (1 - gamma) * s
    return fitted, level, trend, season


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
    date_col, metric = params.get("date"), params.get("metric")
    grain = params.get("granularity") or "day"
    horizon = int(params.get("horizon") or 7)
    if not date_col or not metric:
        fail("缺少必填参数：date、metric")
    if grain not in MIN_POINTS:
        fail("granularity 只能是 day、week 或 month")
    if horizon < 1 or horizon > 60:
        fail("horizon 需在 1–60 之间")
    if not os.path.isfile(args.input):
        fail(f"找不到输入文件：{args.input}")
    with open(args.input, encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        fail("输入数据为空")
    missing = [c for c in (date_col, metric) if c not in rows[0]]
    if missing:
        fail("输入数据缺少列：" + "、".join(missing) + "；现有列：" + "、".join(rows[0].keys()))
    series = {}
    for row in rows:
        t, v = parse_time(row.get(date_col)), number(row.get(metric))
        if t is not None and v is not None:
            key = bucket(t, grain)
            series[key] = series.get(key, 0.0) + v
    times = sorted(series)
    need = MIN_POINTS[grain]
    if len(times) < need:
        fail(f"历史数据只有 {len(times)} 个点，按{ {'day': '日', 'week': '周', 'month': '月'}[grain] }预测至少需要 {need} 个",
             points=len(times), required=need)
    y = np.array([series[t] for t in times])
    m = 7 if grain == "day" else (12 if grain == "month" and len(y) >= 24 else 0)

    best = None
    for alpha in GRID:
        for beta in TREND_GRID:
            for gamma in (GRID if m else (0.0,)):
                fitted, *_ = holt_winters(y, m, alpha, beta, gamma)
                start = m if m else 1
                sse = float(np.sum((y[start:] - fitted[start:]) ** 2))
                if best is None or sse < best[0]:
                    best = (sse, alpha, beta, gamma)
    _, alpha, beta, gamma = best
    fitted, level, trend, season = holt_winters(y, m, alpha, beta, gamma)
    start = m if m else 1
    resid = y[start:] - fitted[start:]
    sigma = float(np.std(resid, ddof=1)) if len(resid) > 1 else 0.0
    nonneg = bool(np.all(y >= 0))

    forecast = []
    for h in range(1, horizon + 1):
        s = season[(len(y) + h - 1) % m] if m else 0.0
        value = level + h * trend + s
        spread = sigma * math.sqrt(1 + (h - 1) * alpha ** 2)
        point = {"time": label(advance(times[-1], grain, h), grain), "value": round(value, 2)}
        for name, z in (("80", 1.2816), ("95", 1.96)):
            lo, hi = value - z * spread, value + z * spread
            point["lo" + name] = round(max(0.0, lo) if nonneg else lo, 2)
            point["hi" + name] = round(hi, 2)
        if nonneg:
            point["value"] = round(max(0.0, value), 2)
        forecast.append(point)

    os.makedirs(args.outdir, exist_ok=True)
    unit = {"day": "天", "week": "周", "month": "个月"}[grain]
    title = params.get("title") or f"{metric}历史与未来 {horizon} {unit}预测"
    stem = safe_name(title)
    chart = os.path.join(args.outdir, stem + ".png").replace("\\", "/")
    setup_font()
    fig, ax = plt.subplots(figsize=(10, 5.4), dpi=150)
    n = len(y)
    hx = np.arange(n)
    fx = np.arange(n - 1, n + horizon)
    joint = lambda key: [float(y[-1])] + [p[key] for p in forecast]  # noqa: E731
    ax.fill_between(fx, joint("lo95"), joint("hi95"), color=ORANGE, alpha=0.14, label="95% 区间")
    ax.fill_between(fx, joint("lo80"), joint("hi80"), color=ORANGE, alpha=0.26, label="80% 区间")
    ax.plot(hx, y, color=BLUE, linewidth=2.2, marker="o", markersize=3, label="历史（SQL）")
    ax.plot(fx, joint("value"), color=DARK_ORANGE, linewidth=2.2, linestyle="--", label="预测值")
    ax.axvline(n - 0.5, color=GRAY, linestyle=":", linewidth=1)
    ax.annotate("预测起点", xy=(n - 0.5, 1), xycoords=("data", "axes fraction"), xytext=(4, -12),
                textcoords="offset points", fontsize=9, color=GRAY)
    labels = [label(t, grain) for t in times] + [p["time"] for p in forecast]
    step = max(1, len(labels) // 8)
    ticks = list(range(0, len(labels), step))
    ax.set_xticks(ticks)
    ax.set_xticklabels([labels[i][5:] if grain == "day" else labels[i] for i in ticks], fontsize=9)
    ax.set_title(title, fontsize=14, fontweight="bold")
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(axis="y", alpha=0.3)
    if nonneg:
        ax.set_ylim(bottom=0)
    ax.legend(loc="upper left", fontsize=9, frameon=False)
    fig.tight_layout()
    fig.savefig(chart)
    plt.close(fig)

    table = os.path.join(args.outdir, stem + "_结果.csv").replace("\\", "/")
    with open(table, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["时间", "预测值", "80%下限", "80%上限", "95%下限", "95%上限"])
        for p in forecast:
            writer.writerow([p["time"], p["value"], p["lo80"], p["hi80"], p["lo95"], p["hi95"]])

    season_text = {7: "周季节项", 12: "年季节项"}.get(m, "无季节项")
    print(json.dumps({
        "ok": True,
        "method": f"Holt-Winters 指数平滑（加法趋势，{season_text}；区间为近似估计）",
        "metric": metric,
        "granularity": grain,
        "history_points": n,
        "parameters": {"alpha": alpha, "beta": beta, "gamma": gamma if m else None, "season_length": m or None},
        "residual_sigma": round(sigma, 2),
        "forecast": forecast,
        "total": round(sum(p["value"] for p in forecast), 2),
        "files": {"chart": chart, "table": table},
        "notes": ["历史数据中的节假日等特殊时期会影响季节项估计，必要时剔除后再预测。"],
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
