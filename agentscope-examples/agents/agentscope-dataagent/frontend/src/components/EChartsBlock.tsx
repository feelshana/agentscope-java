import React, { useEffect, useRef, useState } from 'react';
import * as echarts from 'echarts';
import { getToken } from '../api/auth';

export interface ChartPayload {
  chart?: string;
  chartType?: string;
  title?: string;
  chartId?: string;
  option?: Record<string, unknown>;
}

/**
 * Renders a server-built ECharts chart. Accepts either an inline option or a chartId; the latter
 * is fetched from GET /api/charts/{id} so charts survive harness tool-output truncation and
 * restarts (live and history both resolve through the same endpoint).
 */
export default function EChartsBlock({ payload }: { payload: ChartPayload }) {
  const ref = useRef<HTMLDivElement | null>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);
  const [option, setOption] = useState<Record<string, unknown> | null>(payload.option ?? null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (payload.option) {
      setOption(payload.option);
      return;
    }
    if (!payload.chartId) {
      setError('chart payload missing option and chartId');
      return;
    }
    let cancelled = false;
    fetch(`/api/charts/${encodeURIComponent(payload.chartId)}`, {
      headers: getToken() ? { Authorization: `Bearer ${getToken()}` } : {},
    })
      .then(r => {
        if (!r.ok) throw new Error(`chart fetch failed: ${r.status}`);
        return r.json();
      })
      .then(body => {
        if (!cancelled) setOption((body as { option?: Record<string, unknown> }).option ?? null);
      })
      .catch(e => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [payload]);

  useEffect(() => {
    if (!ref.current || !option) return;
    const chart = echarts.init(ref.current);
    chartRef.current = chart;
    try {
      chart.setOption(option as any);
    } catch {
      /* invalid option: leave empty frame */
    }
    const ro = new ResizeObserver(() => {
      try {
        chart.resize();
      } catch {
        /* ignore during unmount */
      }
    });
    ro.observe(ref.current);
    return () => {
      ro.disconnect();
      chart.dispose();
      chartRef.current = null;
    };
  }, [option]);

  if (error) {
    return (
      <div
        style={{
          background: '#fffbeb',
          border: '1px solid #fcd34d',
          borderRadius: 9,
          margin: '0.5rem 0',
          padding: '0.6rem 0.9rem',
          color: '#92400e',
          fontSize: '0.8rem',
        }}
      >
        chart could not be loaded: {error}
      </div>
    );
  }

  return (
    <div
      ref={ref}
      style={{
        width: '100%',
        height: 360,
        background: 'var(--da-surface)',
        border: '1px solid var(--da-border)',
        borderRadius: 10,
        margin: '0.5rem 0',
      }}
    />
  );
}
