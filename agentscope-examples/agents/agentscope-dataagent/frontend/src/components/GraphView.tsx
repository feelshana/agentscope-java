import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Graph } from '@antv/g6';
import { getGroupGraph, GraphDto } from '../api/datasets';

const PALETTE = ['#5B8FF9', '#61DDAA', '#65789B', '#F6BD16', '#7262FD', '#78D3F8', '#9661BC', '#F6903D'];

/** Node fill by dataset provenance: uploaded file vs associated external data source. */
const NODE_ORIGIN_COLOR: Record<string, string> = {
  upload: '#5B8FF9',
  datasource: '#61DDAA',
};

/** Edge stroke by discovery origin: live schema heuristic vs persisted structured relation. */
const EDGE_ORIGIN_COLOR: Record<string, string> = {
  'column-heuristic': '#c2c8d5',
  inferred: '#8ea2c0',
  doc: '#7262FD',
  llm: '#F6903D',
};

const NODE_ORIGIN_LABEL: Record<string, string> = {
  upload: '上传数据集',
  datasource: '数据源关联',
};

const containerStyle: React.CSSProperties = {
  position: 'relative',
  width: '100%',
  height: 520,
  background: '#fafbfc',
  border: '1px solid #e2e8f0',
  borderRadius: 12,
  overflow: 'hidden',
};

const statsStyle: React.CSSProperties = {
  position: 'absolute',
  top: 8,
  right: 8,
  background: 'rgba(255,255,255,0.92)',
  border: '1px solid #e2e8f0',
  borderRadius: 8,
  padding: '8px 12px',
  fontSize: '0.72rem',
  color: '#475569',
  boxShadow: '0 2px 8px rgba(15,23,42,0.08)',
  maxWidth: 220,
};

const toolbarStyle: React.CSSProperties = {
  position: 'absolute',
  top: 8,
  left: 8,
  background: 'rgba(255,255,255,0.92)',
  border: '1px solid #e2e8f0',
  borderRadius: 8,
  padding: '6px 10px',
  fontSize: '0.72rem',
  color: '#475569',
  display: 'flex',
  alignItems: 'center',
  gap: 6,
};

const legendStyle: React.CSSProperties = {
  position: 'absolute',
  bottom: 8,
  left: 8,
  background: 'rgba(255,255,255,0.92)',
  border: '1px solid #e2e8f0',
  borderRadius: 8,
  padding: '8px 10px',
  fontSize: '0.7rem',
  color: '#475569',
  boxShadow: '0 2px 8px rgba(15,23,42,0.08)',
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
};

function hash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return Math.abs(h);
}

/**
 * Deterministic dataset-relationship graph (table nodes + inferred relation edges), rendered with
 * AntV G6 v5. Layout/behaviour config ported from Yuxi's GraphCanvas.vue (d3-force + hover/click
 * neighbour activation); data comes from GET /api/dataset-groups/{id}/graph.
 */
export default function GraphView({ groupId }: { groupId: string }) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const graphRef = useRef<any>(null);
  const [data, setData] = useState<GraphDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [highOnly, setHighOnly] = useState(false);

  useEffect(() => {
    let cancelled = false;
    getGroupGraph(groupId)
      .then(g => {
        if (!cancelled) setData(g);
      })
      .catch(e => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [groupId]);

  const view = useMemo(() => {
    if (!data) return null;
    const edges = highOnly ? data.edges.filter(e => e.confidence >= 0.5) : data.edges;
    const degree: Record<string, number> = {};
    for (const e of edges) {
      degree[e.source] = (degree[e.source] ?? 0) + 1;
      degree[e.target] = (degree[e.target] ?? 0) + 1;
    }
    const nodes = data.nodes.map(n => ({
      id: n.id,
      data: {
        label: n.label,
        type: n.type,
        origin: n.origin ?? 'upload',
        degree: degree[n.id] ?? 0,
        fields: n.fields,
      },
    }));
    const gEdges = edges.map(e => ({
      id: e.id,
      source: e.source,
      target: e.target,
      data: {
        label: `${e.relationType} ${e.confidence.toFixed(1)}`,
        detail: e.label ?? '',
        relationType: e.relationType,
        confidence: e.confidence,
        origin: e.origin ?? 'column-heuristic',
      },
    }));
    const typeDist: Record<string, number> = {};
    for (const e of edges) typeDist[e.relationType] = (typeDist[e.relationType] ?? 0) + 1;
    return { nodes, edges: gEdges, typeDist, nodeCount: nodes.length, edgeCount: gEdges.length };
  }, [data, highOnly]);

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
        manyBody: { strength: -400, distanceMax: 600 },
        link: { distance: 100, strength: 0.8 },
        collide: { radius: 40 },
      },
      node: {
        type: 'circle',
        style: {
          size: (d: any) => Math.min(50, 15 + (d.data?.degree ?? 0) * 5),
          fill: (d: any) =>
            NODE_ORIGIN_COLOR[d.data?.origin ?? 'upload']
            ?? PALETTE[hash(d.data?.type ?? 'table') % PALETTE.length],
          labelText: (d: any) => d.data?.label ?? d.id,
          labelFontSize: 11,
          labelFill: '#0f172a',
        },
      },
      edge: {
        type: 'quadratic',
        style: {
          endArrow: true,
          stroke: (d: any) => EDGE_ORIGIN_COLOR[d.data?.origin ?? 'column-heuristic'] ?? '#c2c8d5',
          lineWidth: (d: any) => (d.data?.origin === 'doc' ? 2 : 1),
          labelText: (d: any) => d.data?.label ?? '',
          labelFontSize: 9,
          labelFill: '#94a3b8',
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
                <b>${d.relationType ?? ''}</b> · 置信度 ${(d.confidence ?? 0).toFixed(1)}<br/>
                ${d.detail ? `关联列：${d.detail}<br/>` : ''}
                <span style="color:#94a3b8">来源：${d.origin ?? ''}</span>
              </div>`;
            }
            const fields = (d.fields ?? [])
              .slice(0, 8)
              .map((f: any) => `${f.name} <span style="color:#94a3b8">${f.sqlType}</span>`)
              .join('<br/>');
            return `<div style="font-size:12px;line-height:1.5">
              <b>${d.label ?? it.id}</b>
              <span style="color:#94a3b8"> · ${NODE_ORIGIN_LABEL[d.origin] ?? d.origin ?? ''}</span><br/>
              ${fields}
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
      try {
        graph.resize();
      } catch {
        /* ignore resize during unmount */
      }
    });
    ro.observe(containerRef.current);
    return () => {
      ro.disconnect();
      graph.destroy();
      graphRef.current = null;
    };
  }, [view]);

  if (error) {
    return <div style={{ color: '#b91c1c', fontSize: '0.85rem' }}>图谱加载失败：{error}</div>;
  }
  if (!data) {
    return <div style={{ color: '#94a3b8', fontSize: '0.85rem' }}>图谱加载中…</div>;
  }

  return (
    <div style={containerStyle}>
      <div ref={containerRef} style={{ width: '100%', height: '100%' }} />
      {view && view.nodeCount === 0 && (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#94a3b8',
            fontSize: '0.85rem',
          }}
        >
          暂无数据集（上传含同名列/外键列的多张表后自动连线）
        </div>
      )}
      {view && view.nodeCount > 0 && view.edgeCount === 0 && (
        <div
          style={{
            position: 'absolute',
            bottom: 8,
            left: '50%',
            transform: 'translateX(-50%)',
            color: '#94a3b8',
            fontSize: '0.75rem',
          }}
        >
          暂无可推断的表间关系（上传含同名列/外键列的多张表后自动连线）
        </div>
      )}
      <div style={toolbarStyle}>
        <label style={{ display: 'flex', alignItems: 'center', gap: 4, cursor: 'pointer' }}>
          <input
            type="checkbox"
            checked={highOnly}
            onChange={e => setHighOnly(e.target.checked)}
          />
          仅高置信度(≥0.5)
        </label>
      </div>
      {view && view.nodeCount > 0 && (
        <div style={legendStyle}>
          <div style={{ fontWeight: 600, color: '#334155', marginBottom: 2 }}>图例</div>
          {Array.from(new Set(view.nodes.map(n => n.data.origin))).map(o => (
            <div key={`n-${o}`} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <span
                style={{
                  width: 10,
                  height: 10,
                  borderRadius: '50%',
                  background: NODE_ORIGIN_COLOR[o] ?? '#94a3b8',
                  display: 'inline-block',
                }}
              />
              {NODE_ORIGIN_LABEL[o] ?? o}
            </div>
          ))}
          {Array.from(new Set(view.edges.map(e => e.data.origin))).map(o => (
            <div key={`e-${o}`} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <span
                style={{
                  width: 14,
                  height: 0,
                  borderTop: `2px solid ${EDGE_ORIGIN_COLOR[o] ?? '#c2c8d5'}`,
                  display: 'inline-block',
                }}
              />
              {o === 'column-heuristic'
                ? '列启发式'
                : o === 'doc'
                  ? '关系文档'
                  : o === 'llm'
                    ? 'LLM 抽取'
                    : '规则推断'}
            </div>
          ))}
        </div>
      )}
      {view && (
        <div style={statsStyle}>
          <div>
            节点 {view.nodeCount} · 边 {view.edgeCount}
          </div>
          {Object.entries(view.typeDist).map(([t, c]) => (
            <div key={t}>
              {t}: {c}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
