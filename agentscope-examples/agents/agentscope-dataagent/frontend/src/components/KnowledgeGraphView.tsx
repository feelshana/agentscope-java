import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Graph } from '@antv/g6';
import {
  getKgGraph,
  getKgStatus,
  KgGraph,
  KgStatus,
} from '../api/knowledgeGraph';
import KgBuildConfigModal from './KgBuildConfigModal';

const NODE_COLORS = [
  '#3996ae',
  '#5ad8a6',
  '#f6bd16',
  '#f27c7c',
  '#9581cc',
  '#6dc8ec',
  '#ff9d4d',
  '#92d050',
  '#e885ba',
];

const STATUS_LABEL: Record<string, string> = {
  SUCCEEDED: '已完成',
  RUNNING: '解析中',
  FAILED: '解析失败',
  PENDING: '未处理',
};

const STATUS_COLOR: Record<string, string> = {
  SUCCEEDED: '#16a34a',
  RUNNING: '#3b82f6',
  FAILED: '#dc2626',
  PENDING: '#94a3b8',
};

function hash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return Math.abs(h);
}

const wrapStyle: React.CSSProperties = {
  display: 'flex',
  gap: 12,
  width: '100%',
  height: 560,
};

const canvasStyle: React.CSSProperties = {
  position: 'relative',
  flex: 1,
  background: '#fafbfc',
  border: '1px solid #e2e8f0',
  borderRadius: 12,
  overflow: 'hidden',
};

const toolbarStyle: React.CSSProperties = {
  position: 'absolute',
  top: 8,
  left: 8,
  right: 8,
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  zIndex: 2,
};

const toolBtnStyle: React.CSSProperties = {
  background: 'rgba(255,255,255,0.94)',
  border: '1px solid #e2e8f0',
  borderRadius: 6,
  padding: '4px 8px',
  fontSize: '0.72rem',
  color: '#334155',
  cursor: 'pointer',
};

const panelStyle: React.CSSProperties = {
  width: 240,
  background: '#fff',
  border: '1px solid #e2e8f0',
  borderRadius: 12,
  padding: 14,
  fontSize: '0.78rem',
  color: '#334155',
  overflowY: 'auto',
};

/**
 * TC-style semantic knowledge graph for a KB: entity nodes (表/字段/字段类型/取值/业务词) and
 * labelled relation edges extracted by the LLM GraphRAG pipeline, with a live overview panel
 * (实体数/关系边/文档数/处理状态+进度) and search/zoom/refresh/rebuild toolbar. Data from
 * /api/dataset-groups/{id}/kg/*; polled while a build is running.
 */
export default function KnowledgeGraphView({ groupId }: { groupId: string }) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const graphRef = useRef<any>(null);
  const [status, setStatus] = useState<KgStatus | null>(null);
  const [graph, setGraph] = useState<KgGraph | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [configOpen, setConfigOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [zoomPct, setZoomPct] = useState(100);

  const loadStatus = useCallback(async (): Promise<KgStatus | null> => {
    try {
      return await getKgStatus(groupId);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      return null;
    }
  }, [groupId]);

  const loadGraph = useCallback(async () => {
    try {
      setGraph(await getKgGraph(groupId));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [groupId]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const s = await loadStatus();
      if (cancelled) return;
      setStatus(s);
      await loadGraph();
    })();
    return () => {
      cancelled = true;
    };
  }, [loadStatus, loadGraph]);

  const isActive = (s: KgStatus | null) =>
    !!s &&
    (s.taskStatus === 'RUNNING' ||
      s.units.some(u => u.status === 'RUNNING' || u.status === 'PENDING'));

  const lastSigRef = useRef<string>('');

  useEffect(() => {
    if (!isActive(status)) return;
    const t = setInterval(async () => {
      const s = await loadStatus();
      if (!s) return;
      setStatus(s);
      const sig = `${s.stats.processed}|${s.stats.failed}|${s.stats.entityCount}`;
      // Only re-render when new entities/relations actually landed, or the build just finished.
      if (sig !== lastSigRef.current || !isActive(s)) {
        lastSigRef.current = sig;
        await loadGraph();
      }
    }, 3000);
    return () => clearInterval(t);
  }, [status, loadStatus, loadGraph]);

  useEffect(() => {
    if (!containerRef.current || !graph) return;
    const degree: Record<string, number> = {};
    for (const e of graph.edges) {
      degree[e.source] = (degree[e.source] ?? 0) + 1;
      degree[e.target] = (degree[e.target] ?? 0) + 1;
    }
    const nodes = graph.nodes.map(n => ({
      id: n.id,
      data: { label: n.text, type: n.label, degree: degree[n.id] ?? 0, attributes: n.attributes },
    }));
    const edges = graph.edges.map(e => ({
      id: e.id,
      source: e.source,
      target: e.target,
      data: { label: e.label, text: e.text ?? '' },
    }));
    const g = new Graph({
      container: containerRef.current,
      autoFit: 'view',
      data: { nodes, edges },
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
          fill: (d: any) => NODE_COLORS[hash(d.data?.type ?? 'Entity') % NODE_COLORS.length],
          labelText: (d: any) => d.data?.label ?? d.id,
          labelFontSize: 11,
          labelFill: '#0f172a',
        },
      },
      edge: {
        type: 'quadratic',
        style: {
          endArrow: true,
          stroke: '#c2c8d5',
          lineWidth: 1,
          labelText: (d: any) => d.data?.label ?? '',
          labelFontSize: 9,
          labelFill: '#94a3b8',
          labelBackground: true,
          labelBackgroundFill: '#fafbfc',
        },
      },
      behaviors: ['drag-canvas', 'zoom-canvas', 'drag-element', 'hover-activate', 'click-select'],
    } as any);
    g.render();
    graphRef.current = g;
    try {
      setZoomPct(Math.round((g.getZoom?.() ?? 1) * 100));
    } catch {
      /* zoom read is best-effort */
    }
    const ro = new ResizeObserver(() => {
      try {
        g.resize();
      } catch {
        /* ignore resize during unmount */
      }
    });
    ro.observe(containerRef.current);
    return () => {
      ro.disconnect();
      g.destroy();
      graphRef.current = null;
    };
  }, [graph]);

  useEffect(() => {
    const g = graphRef.current;
    if (!g || !graph) return;
    try {
      if (!search.trim()) {
        for (const n of graph.nodes) g.setElementState?.(n.id, []);
        return;
      }
      const q = search.trim().toLowerCase();
      const matches = graph.nodes.filter(n => n.text.toLowerCase().includes(q));
      for (const n of graph.nodes) {
        g.setElementState?.(n.id, matches.includes(n) ? ['selected'] : []);
      }
      if (matches.length > 0) g.focusElement?.(matches[0].id);
    } catch {
      /* highlight is best-effort */
    }
  }, [search, graph]);

  function zoom(factor: number) {
    const g = graphRef.current;
    if (!g) return;
    try {
      const cur = g.getZoom?.() ?? 1;
      g.zoomTo?.(cur * factor);
      setZoomPct(Math.round(cur * factor * 100));
    } catch {
      /* zoom is best-effort */
    }
  }

  const stats = status?.stats;
  const hasBuild = !!status && (status.taskStatus !== null || status.units.length > 0);

  return (
    <div style={wrapStyle}>
      <div style={canvasStyle}>
        <div ref={containerRef} style={{ width: '100%', height: '100%' }} />
        <div style={toolbarStyle}>
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="搜索实体"
            style={{
              ...toolBtnStyle,
              width: 160,
              outline: 'none',
            }}
          />
          <button style={toolBtnStyle} onClick={() => zoom(1.2)} title="放大">
            ＋
          </button>
          <button style={toolBtnStyle} onClick={() => zoom(1 / 1.2)} title="缩小">
            －
          </button>
          <span style={{ ...toolBtnStyle, cursor: 'default' }}>{zoomPct}%</span>
          <button
            style={toolBtnStyle}
            onClick={() => {
              try {
                graphRef.current?.fitView?.();
              } catch {
                /* ignore */
              }
            }}
          >
            适应
          </button>
          <button
            style={toolBtnStyle}
            onClick={() => {
              loadStatus().then(setStatus);
              loadGraph();
            }}
          >
            刷新
          </button>
          <button
            style={{ ...toolBtnStyle, color: '#2563eb' }}
            onClick={() => setConfigOpen(true)}
          >
            {hasBuild ? '重建图谱' : '开始构建图谱'}
          </button>
        </div>
        {error && (
          <div
            style={{
              position: 'absolute',
              bottom: 8,
              left: '50%',
              transform: 'translateX(-50%)',
              color: '#b91c1c',
              fontSize: '0.75rem',
              background: 'rgba(255,255,255,0.94)',
              padding: '4px 10px',
              borderRadius: 6,
            }}
          >
            {error}
          </div>
        )}
        {status && !hasBuild && !error && (
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
            尚未构建知识图谱，点击右上角"开始构建图谱"
          </div>
        )}
        {graph && graph.nodes.length === 0 && hasBuild && !isActive(status) && (
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
            暂无抽取到的实体（构建完成后自动连线）
          </div>
        )}
      </div>

      <div style={panelStyle}>
        <div style={{ fontWeight: 600, color: '#0f172a', marginBottom: 10, borderBottom: '2px solid #2563eb', display: 'inline-block', paddingBottom: 2 }}>
          图谱概览
        </div>
        <Row label="实体数" value={stats?.entityCount ?? 0} />
        <Row label="关系边" value={stats?.relationCount ?? 0} />
        <Row label="文档数量" value={stats ? `${stats.docCount} 篇` : 0} />
        <Row
          label="已处理文档占比"
          value={
            stats && stats.docCount > 0
              ? `${stats.processed + stats.failed}/${stats.docCount}`
              : '0/0'
          }
        />
        <div
          style={{
            height: 6,
            background: '#e2e8f0',
            borderRadius: 3,
            margin: '8px 0 12px',
            overflow: 'hidden',
          }}
        >
          <div
            style={{
              height: '100%',
              width: `${Math.round((status?.progress ?? 0) * 100)}%`,
              background: '#16a34a',
              transition: 'width .3s',
            }}
          />
        </div>
        {(
          [
            ['SUCCEEDED', stats?.processed ?? 0],
            ['RUNNING', stats?.running ?? 0],
            ['FAILED', stats?.failed ?? 0],
            ['PENDING', stats?.pending ?? 0],
          ] as [string, number][]
        ).map(([k, v]) => (
          <div key={k} style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
            <span
              style={{
                width: 8,
                height: 8,
                borderRadius: 2,
                background: STATUS_COLOR[k],
                display: 'inline-block',
              }}
            />
            <span style={{ flex: 1 }}>{STATUS_LABEL[k]}</span>
            <span>{v}</span>
          </div>
        ))}
        {status && status.units.length > 0 && (
          <>
            <div style={{ fontWeight: 600, color: '#0f172a', margin: '12px 0 6px' }}>解析明细</div>
            {status.units.map((u, i) => (
              <div key={`${u.unitName}-${i}`} style={{ marginBottom: 6 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <span
                    style={{
                      width: 8,
                      height: 8,
                      borderRadius: 2,
                      background: STATUS_COLOR[u.status] ?? '#94a3b8',
                      display: 'inline-block',
                    }}
                  />
                  <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {u.unitName}
                  </span>
                  <span style={{ color: STATUS_COLOR[u.status] ?? '#94a3b8' }}>
                    {STATUS_LABEL[u.status] ?? u.status}
                  </span>
                </div>
                {u.status === 'FAILED' && u.errorMsg && (
                  <div style={{ color: '#dc2626', fontSize: '0.7rem', marginLeft: 14, marginTop: 2 }}>
                    {u.errorMsg}
                  </div>
                )}
              </div>
            ))}
          </>
        )}
        {graph && Object.keys(graph.typeDist).length > 0 && (
          <>
            <div style={{ fontWeight: 600, color: '#0f172a', margin: '12px 0 6px' }}>实体类型</div>
            {Object.entries(graph.typeDist).map(([t, c]) => (
              <div key={t} style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
                <span
                  style={{
                    width: 8,
                    height: 8,
                    borderRadius: '50%',
                    background: NODE_COLORS[hash(t) % NODE_COLORS.length],
                    display: 'inline-block',
                  }}
                />
                <span style={{ flex: 1 }}>{t}</span>
                <span>{c}</span>
              </div>
            ))}
          </>
        )}
      </div>

      <KgBuildConfigModal
        groupId={groupId}
        open={configOpen}
        onClose={() => setConfigOpen(false)}
        onSaved={() => {
          loadStatus().then(setStatus);
        }}
      />
    </div>
  );
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
      <span style={{ color: '#64748b' }}>{label}</span>
      <span style={{ color: '#0f172a', fontWeight: 600 }}>{value}</span>
    </div>
  );
}
