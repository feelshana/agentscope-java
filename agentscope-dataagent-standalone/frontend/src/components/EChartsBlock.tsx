import React, { useEffect, useRef, useState } from 'react';
import * as echarts from 'echarts';
import { apiFetch, getToken } from '../api/auth';
import ErrorNotice from './ErrorNotice';

export interface ChartPayload {
  chart?: string;
  chartType?: string;
  title?: string;
  chartId?: string;
  option?: Record<string, unknown>;
}

/** Load saved charts without re-executing queries; retain a useful fallback on render failures. */
export default function EChartsBlock({ payload, onViewData, onInspect }: {
  payload: ChartPayload; onViewData?: () => void; onInspect?: () => void;
}) {
  const ref = useRef<HTMLDivElement | null>(null);
  const [option, setOption] = useState<Record<string, unknown> | null>(null);
  const [failure, setFailure] = useState<{ message: string; stage: 'load' | 'render' } | null>(null);
  const [loading, setLoading] = useState(false);
  const [attempt, setAttempt] = useState(0);
  // Equal options extracted from streaming messages must not recreate the chart each token.
  const optionJson = payload.option ? JSON.stringify(payload.option) : null;

  useEffect(() => {
    let active = true;
    setFailure(null);
    setOption(null);
    if (optionJson) {
      const inline = JSON.parse(optionJson) as Record<string, unknown>;
      if (Object.keys(inline).length) setOption(inline);
      else setFailure({ message: '图表配置为空', stage: 'render' });
      setLoading(false);
      return;
    }
    if (!payload.chartId) {
      setFailure({ message: '图表记录缺少标识或配置', stage: 'render' });
      setLoading(false);
      return;
    }
    setLoading(true);
    const token = getToken();
    void apiFetch(`/api/charts/${encodeURIComponent(payload.chartId)}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    }).then(async response => {
      if (!response.ok) throw new Error(`图表读取失败，HTTP ${response.status}`);
      const body = await response.json() as { option?: Record<string, unknown> };
      if (!body.option || typeof body.option !== 'object' || Array.isArray(body.option) || !Object.keys(body.option).length) {
        throw new Error('图表记录中没有可显示的配置');
      }
      if (active) setOption(body.option);
    }).catch(error => {
      if (active) setFailure({ message: error instanceof Error ? error.message : String(error), stage: 'load' });
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [optionJson, payload.chartId, attempt]);

  useEffect(() => {
    if (!ref.current || !option) return;
    let chart: echarts.ECharts | undefined;
    let observer: ResizeObserver | undefined;
    try {
      chart = echarts.init(ref.current);
      chart.setOption(option);
      const current = chart;
      observer = new ResizeObserver(() => {
        if (current.isDisposed()) return;
        try { current.resize(); }
        catch (error) { setFailure({ message: error instanceof Error ? error.message : String(error), stage: 'render' }); }
      });
      observer.observe(ref.current);
    } catch (error) {
      setFailure({ message: error instanceof Error ? error.message : String(error), stage: 'render' });
    }
    return () => { observer?.disconnect(); chart?.dispose(); };
  }, [option]);

  return <section className="da-chart-frame" aria-label={payload.title || '图表'}>
    {loading && <div className="da-chart-loading" role="status">正在加载图表…</div>}
    {failure && <ErrorNotice error={failure.message} context="chart">
      {onViewData && <button type="button" className="da-btn da-btn-sm" onClick={onViewData}>查看已有数据</button>}
      {onInspect && <button type="button" className="da-btn da-btn-sm" onClick={onInspect}>查看图表详情</button>}
      {failure.stage === 'load' && payload.chartId && !optionJson && <button type="button" className="da-btn da-btn-sm" onClick={() => setAttempt(value => value + 1)}>重新加载图表</button>}
    </ErrorNotice>}
    <div ref={ref} style={{ display: failure || loading || !option ? 'none' : 'block', width: '100%', height: 360,
      background: 'var(--da-surface)', border: '1px solid var(--da-border)', borderRadius: 10 }} />
  </section>;
}
