"""异常检测（季节分解 + 稳健残差阈值）。

用法：python skills/anomaly/anomaly.py --input data/<文件>.csv --params '<json>'

params:
  date         时间列名（必填），取值如 2026-09-20 或 2026-09-20 13:00
  metric       指标列名（必填）
  granularity  day（默认）或 hour
  threshold    残差超过多少倍稳健标准差判为异常，默认 3
  title        图表标题，可选

方法：窗口为 4 个周期的滚动中位数作为趋势（对一周左右的节假日等扰动稳健）。季节项：
按天数据满 4 周时按星期几估计，满 2 周不足 4 周时按工作日 / 周末两组估计（样本少时
按星期几估计不稳定）；按小时数据满 3 天时按小时估计；否则不估计季节项。残差的稳健
标准差 σ = 1.4826 × MAD，|残差| > threshold × σ 判为异常。只读输入 CSV，不联网。
"""

import argparse
import csv
import json
import math
import os
import re
import sys
from datetime import datetime

import numpy as np
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib import font_manager  # noqa: E402

BLUE = "#3B6FB6"
ORANGE = "#E8743B"
GRAY = "#8A9099"
FORMATS = ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d %H", "%Y-%m-%d", "%Y/%m/%d %H:%M:%S",
           "%Y/%m/%d %H:%M", "%Y/%m/%d", "%Y%m%d")


def setup_font():
    names = {f.name for f in font_manager.fontManager.ttflist}
    for name in ("Noto Sans CJK SC", "Noto Sans SC", "Microsoft YaHei", "SimHei", "PingFang SC", "WenQuanYi Zen Hei"):
        if name in names:
            plt.rcParams["font.sans-serif"] = [name, "DejaVu Sans"]
            break
    plt.rcParams["axes.unicode_minus"] = False


def fail(reason):
    print(json.dumps({"ok": False, "method": "异常检测", "reason": reason}, ensure_ascii=False))
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
    value = str(text or "").strip().replace("T", " ")
    value = re.sub(r"\.\d+$", "", value)
    for f in FORMATS:
        try:
            return datetime.strptime(value, f)
        except ValueError:
            continue
    return None


def safe_name(text):
    return re.sub(r"[\\/:*?\"<>|\s]+", "_", text).strip("_")[:60] or "异常检测"


def label(t, hourly):
    return t.strftime("%m-%d %H:00") if hourly else t.strftime("%Y-%m-%d")


def rolling_median(values, window):
    half = window // 2
    out = []
    for i in range(len(values)):
        lo, hi = max(0, i - half), min(len(values), i + half + 1)
        out.append(float(np.median(values[lo:hi])))
    return np.array(out)


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
    hourly = (params.get("granularity") or "day") == "hour"
    threshold = float(params.get("threshold") or 3)
    if not date_col or not metric:
        fail("缺少必填参数：date、metric")
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
    skipped = 0
    for row in rows:
        t, v = parse_time(row.get(date_col)), number(row.get(metric))
        if t is None or v is None:
            skipped += 1
            continue
        key = t.replace(minute=0, second=0) if hourly else t.replace(hour=0, minute=0, second=0)
        series[key] = series.get(key, 0.0) + v
    times = sorted(series)
    if len(times) < 7:
        fail(f"有效数据点只有 {len(times)} 个，异常检测至少需要 7 个")
    values = np.array([series[t] for t in times])
    period = 24 if hourly else 7
    n = len(times)
    trend = rolling_median(values, 4 * period + 1)
    detrended = values - trend
    if hourly:
        season_mode = "hour" if n >= 3 * period else None
    else:
        season_mode = "weekday" if n >= 4 * period else ("workday" if n >= 2 * period else None)
    seasonal = np.zeros(n)
    if season_mode:
        if season_mode == "hour":
            keys = [t.hour for t in times]
        elif season_mode == "weekday":
            keys = [t.weekday() for t in times]
        else:
            keys = ["周末" if t.weekday() >= 5 else "工作日" for t in times]
        effect = {k: float(np.median([d for d, kk in zip(detrended, keys) if kk == k])) for k in set(keys)}
        seasonal = np.array([effect[k] for k in keys])
    expected = trend + seasonal
    residual = values - expected
    mad = float(np.median(np.abs(residual - np.median(residual))))
    sigma = 1.4826 * mad if mad > 0 else float(np.std(residual)) or 1.0
    band = threshold * sigma

    points, anomalies = [], []
    for t, v, e, r in zip(times, values, expected, residual):
        is_anomaly = abs(r) > band
        point = {"time": label(t, hourly), "value": float(v), "expected": round(float(e), 2),
                 "lower": round(float(e - band), 2), "upper": round(float(e + band), 2), "anomaly": bool(is_anomaly)}
        points.append(point)
        if is_anomaly:
            anomalies.append({"time": point["time"], "value": float(v), "expected": point["expected"],
                              "deviation": round(float(r), 2),
                              "deviation_pct": round(float(r / e), 4) if e else None,
                              "sigma_multiple": round(float(abs(r) / sigma), 1),
                              "direction": "偏高" if r > 0 else "偏低"})

    os.makedirs(args.outdir, exist_ok=True)
    title = params.get("title") or f"{metric}与正常范围"
    stem = safe_name(title)
    chart = os.path.join(args.outdir, stem + ".png").replace("\\", "/")
    setup_font()
    fig, ax = plt.subplots(figsize=(10, 5.4), dpi=150)
    x = np.arange(len(times))
    ax.fill_between(x, expected - band, expected + band, color=BLUE, alpha=0.12, label=f"正常范围（±{threshold:g}σ）")
    ax.plot(x, expected, color=GRAY, linestyle="--", linewidth=1.4, label="预期值")
    ax.plot(x, values, color=BLUE, linewidth=2.2, marker="o", markersize=3, label="实际值")
    for i, p in enumerate(points):
        if p["anomaly"]:
            ax.scatter([i], [p["value"]], s=70, color=ORANGE, edgecolor="white", zorder=5)
            pct = p["value"] / p["expected"] - 1 if p["expected"] else None
            text = p["time"][5:] if not hourly else p["time"]
            if pct is not None:
                text += f" {pct * 100:+.0f}%"
            ax.annotate(text, (i, p["value"]), textcoords="offset points",
                        xytext=(0, 10 if p["value"] >= p["expected"] else -16), ha="center",
                        fontsize=9, color="#A04A12", fontweight="bold")
    ax.scatter([], [], color=ORANGE, label="异常点")
    step = max(1, len(times) // 8)
    ax.set_xticks(x[::step])
    ax.set_xticklabels([points[i]["time"][5:] if not hourly else points[i]["time"] for i in x[::step]], fontsize=9)
    ax.set_title(title, fontsize=14, fontweight="bold")
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(axis="y", alpha=0.3)
    ax.set_ylim(bottom=min(0, float(np.min(values))))
    ax.legend(loc="upper left", fontsize=9, frameon=False)
    fig.tight_layout()
    fig.savefig(chart)
    plt.close(fig)

    table = os.path.join(args.outdir, stem + "_结果.csv").replace("\\", "/")
    with open(table, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["时间", "实际值", "预期值", "下限", "上限", "是否异常"])
        for p in points:
            writer.writerow([p["time"], p["value"], p["expected"], p["lower"], p["upper"], "是" if p["anomaly"] else ""])

    season_text = {"hour": "小时季节项", "weekday": "星期季节项", "workday": "工作日 / 周末季节项"}
    method = (f"季节分解（滚动中位数趋势 + {season_text[season_mode]}）+ 稳健残差阈值") \
        if season_mode else "滚动中位数 + 稳健残差阈值（数据不足以估计季节项）"
    notes = []
    if skipped:
        notes.append(f"有 {skipped} 行的时间或指标无法解析，已跳过。")
    print(json.dumps({
        "ok": True,
        "method": method,
        "metric": metric,
        "granularity": "hour" if hourly else "day",
        "points_count": len(points),
        "sigma": round(sigma, 2),
        "threshold": threshold,
        "anomalies": anomalies,
        "points": points,
        "files": {"chart": chart, "table": table},
        "notes": notes,
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
