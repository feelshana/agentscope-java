import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Graph } from '@antv/g6';
import { OntologyGraphData, OntologyNode, OntologyEdge } from '../api/ontology';

/** 维度表节点填充色 */
const DIMENSION_COLOR = '#5B8FF9';
/** 事实表节点填充色 */
const FACT_COLOR = '#F6903D';
/** 指标节点填充色 */
const METRIC_COLOR = '#61DDAA';
/** 高亮边框色 */
const HIGHLIGHT_STROKE = '#EF4444';
/** 关系边颜色 */
const EDGE_COLOR = '#8ea2c0';
/** 指标归属边颜色 */
const METRIC_EDGE_COLOR = '#61DDAA';

const S: Record<string, React.CSSProperties> = {
  container: {
    position: 'relative', width: '100%', height: 520,
    background: '#f8fafc', border: '1px solid #e2e8f0',
    borderRadius: 12, overflow: 'hidden',
  },
  stats: {
    position: 'absolute', top: 8, right: 8,
    background: 'rgba(255,255,255,0.92)', border: '1px solid #e2e8f0',
    borderRadius: 8, padding: '8px 12px', fontSize: '0.72rem',
    color: '#475569', boxShadow: '0 2px 8px rgba(15,23,42,0.08)', maxWidth: 220,
  },
  legend: {
    position: 'absolute', bottom: 8, left: 8,
    background: 'rgba(255,255,255,0.92)', border: '1px solid #e2e8f0',
    borderRadius: 8, padding: '8px 10px', fontSize: '0.7rem',
    color: '#475569', boxShadow: '0 2px 8px rgba(15,23,42,0.08)',
    display: 'flex', flexDirection: 'column', gap: 4,
  },
  empty: {
    position: 'absolute', inset: 0,
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    color: '#94a3b8', fontSize: '0.85rem',
  },
};

function nodeColor(kind: string): string {
  switch (kind) {
    case 'fact': return FACT_COLOR;
    case 'metric': return METRIC_COLOR;
    default: return DIMENSION_COLOR;
  }
}

const KIND_LABEL: Record<string, string> = {
  dimension: '维度表',
  fact: '事实表',
  metric: '指标',
};

interface Props {
  groupId?: string;
  data?: OntologyGraphData;
  highlight?: string[];
}

/**
 * 本体模型图谱组件。基于 AntV G6 渲染业务对象、关系和指标的可视化图谱。
 *
 * - 维度表节点：浅蓝圆角矩形
 * - 事实表节点：浅橙圆角矩形
 * - 指标节点：绿色小标签
 * - 关系边：带标签有向箭头
 * - 指标归属边：虚线
 */
export default function OntologyGraphView({ groupId, data: externalData, highlight }: Props) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const graphRef = useRef<any>(null);
  const [data, setData] = useState<OntologyGraphData | null>(externalData ?? null);
  const [error, setError] = useState<string | null>(null);

  // 如果未传入 data，则从 API 获取
  useEffect(() => {
    if (externalData) {
      setData(externalData);
      return;
    }
    if (!groupId) return;
    let cancelled = false;
    import('../api/ontology').then(({ getOntologyGraph }) =>
      getOntologyGraph(groupId)
        .then(g => { if (!cancelled) setData(g); })
        .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); }),
    );
    return () => { cancelled = true; };
  }, [groupId, externalData]);

  const view = useMemo(() => {
    if (!data) return null;
    const highlightSet = new Set(highlight ?? []);

    const nodes = data.nodes.map(n => ({
      id: n.id,
      data: {
        label: n.label,
        kind: n.kind,
        table: n.table,
        highlight: n.highlight || highlightSet.has(n.id),
        properties: n.properties,
        unit: n.unit,
      },
    }));

    const edges = data.edges.map(e => ({
      id: e.id,
      source: e.source,
      target: e.target,
      data: {
        label: e.label,
        cardinality: e.cardinality,
        joinColumns: e.joinColumns,
        isMetricEdge: e.id.startsWith('metric_edge:'),
      },
    }));

    return { nodes, edges };
  }, [data, highlight]);

  useEffect(() => {
    if (!containerRef.current || !view) return;
    const graph = new Graph({
      container: containerRef.current,
      autoFit: 'view',
      data: { nodes: view.nodes, edges: view.edges },
      layout: {
        type: 'd3-force',
        alphaDecay: 0.1,
        alphaMin: 0.01,
        velocityDecay: 0.6,
        center: { strength: 0.1 },
        manyBody: { strength: -500, distanceMax: 700 },
        link: { distance: 120, strength: 0.8 },
        collide: { radius: 50 },
      },
      node: {
        type: 'rect',
        style: {
          size: (d: any) => d.data?.kind === 'metric' ? [80, 28] : [120, 40],
          radius: 8,
          fill: (d: any) => nodeColor(d.data?.kind ?? 'dimension'),
          stroke: (d: any) => d.data?.highlight ? HIGHLIGHT_STROKE : 'transparent',
          lineWidth: (d: any) => d.data?.highlight ? 3 : 0,
          labelText: (d: any) => d.data?.label ?? d.id,
          labelFontSize: (d: any) => d.data?.kind === 'metric' ? 10 : 12,
          labelFontWeight: (d: any) => d.data?.kind === 'metric' ? 'normal' : 'bold',
          labelFill: '#fff',
        },
      },
      edge: {
        type: 'quadratic',
        style: {
          endArrow: true,
          stroke: (d: any) => d.data?.isMetricEdge ? METRIC_EDGE_COLOR : EDGE_COLOR,
          lineWidth: (d: any) => d.data?.isMetricEdge ? 1 : 1.5,
          lineDash: (d: any) => d.data?.isMetricEdge ? [4, 4] : undefined,
          labelText: (d: any) => d.data?.label ?? '',
          labelFontSize: 9,
          labelFill: '#64748b',
          labelBackground: true,
          labelBackgroundFill: 'rgba(255,255,255,0.85)',
          labelBackgroundStroke: 'transparent',
          labelBackgroundLineWidth: 0,
          labelBackgroundRadius: 3,
          labelPadding: [2, 4, 2, 4],
        },
      },
      plugins: [
        {
          type: 'tooltip',
          getContent: (_e: any, items: any[]) => {
            const it = items?.[0];
            if (!it) return '';
            const d = it.data ?? {};
            if (it.source && it.target) {
              return `<div style="font-size:12px;line-height:1.5">
                <b>${d.label ?? ''}</b><br/>
                ${d.joinColumns ? `关联列：${d.joinColumns}<br/>` : ''}
                <span style="color:#94a3b8">${d.cardinality ?? ''}</span>
              </div>`;
            }
            const props = (d.properties ?? [])
              .slice(0, 10)
              .map((p: any) => `${p.label} <span style="color:#94a3b8">${p.type}</span>`)
              .join('<br/>');
            const kindLabel = KIND_LABEL[d.kind] ?? d.kind;
            return `<div style="font-size:12px;line-height:1.5">
              <b>${d.label ?? it.id}</b>
              <span style="color:#94a3b8"> · ${kindLabel}</span><br/>
              ${d.table ? `表：${d.table}<br/>` : ''}
              ${d.unit ? `单位：${d.unit}<br/>` : ''}
              ${props}
            </div>`;
          },
        },
      ],
      behaviors: [
        'drag-canvas',
        'zoom-canvas',
        'drag-element',
        'hover-activate',
        { type: 'click-select', degree: 1, neighborState: 'selected' },
      ],
    } as any);
    graph.render();
    graphRef.current = graph;

    const ro = new ResizeObserver(() => {
      try { graph.resize(); } catch { /* ignore */ }
    });
    ro.observe(containerRef.current);
    return () => {
      ro.disconnect();
      graph.destroy();
      graphRef.current = null;
    };
  }, [view]);

  if (error) {
    return <div style={{ color: '#ef4444', fontSize: '0.85rem' }}>本体图谱加载失败：{error}</div>;
  }
  if (!data) {
    return <div style={{ color: '#94a3b8', fontSize: '0.85rem' }}>本体图谱加载中…</div>;
  }

  return (
    <div style={S.container}>
      <div ref={containerRef} style={{ width: '100%', height: '100%' }} />
      {view && view.nodes.length === 0 && (
        <div style={S.empty}>
          暂无本体模型（上传 model.yaml 或自动生成本体后展示）
        </div>
      )}
      {view && view.nodes.length > 0 && (
        <div style={S.legend}>
          <div style={{ fontWeight: 600, color: '#334155', marginBottom: 2 }}>图例</div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 12, height: 12, borderRadius: 3, background: DIMENSION_COLOR, display: 'inline-block' }} />
            维度表
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 12, height: 12, borderRadius: 3, background: FACT_COLOR, display: 'inline-block' }} />
            事实表
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 12, height: 12, borderRadius: 3, background: METRIC_COLOR, display: 'inline-block' }} />
            指标
          </div>
          {highlight && highlight.length > 0 && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <span style={{ width: 12, height: 12, borderRadius: 3, border: `2px solid ${HIGHLIGHT_STROKE}`, display: 'inline-block' }} />
              查询涉及
            </div>
          )}
        </div>
      )}
      {data && (
        <div style={S.stats}>
          <div>对象 {data.objectCount} · 关系 {data.relationshipCount} · 指标 {data.metricCount}</div>
        </div>
      )}
    </div>
  );
}
