import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Graph } from '@antv/g6';
import {
  getKgGraph,
  getKgStatus,
  KgGraph,
  KgStatus,
} from '../api/knowledgeGraph';
import KgBuildConfigModal from './KgBuildConfigModal';
import EmptyIllustration from './EmptyIllustration';
import Icon from './Icon';

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
  FAILED: 'var(--da-danger)',
  PENDING: 'var(--da-text-muted)',
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
  flex: 1,
  minHeight: 0,
  padding: 16,
};

const canvasStyle: React.CSSProperties = {
  position: 'relative',
  flex: 1,
  minHeight: 0,
  background: 'var(--da-surface)',
  border: '1px solid var(--da-border)',
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
  border: '1px solid var(--da-border)',
  borderRadius: 6,
  padding: '4px 8px',
  fontSize: '0.72rem',
  color: 'var(--da-text-2)',
  cursor: 'pointer',
};

const panelStyle: React.CSSProperties = {
  width: 260,
  flexShrink: 0,
  alignSelf: 'stretch',
  background: 'var(--da-surface)',
  border: '1px solid var(--da-border)',
  borderRadius: 12,
  padding: 14,
  fontSize: '0.78rem',
  color: 'var(--da-text-2)',
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
          labelFill: '#1a1d23',
          labelBackground: true,
          labelBackgroundFill: '#ffffff',
          labelBackgroundOpacity: 0.85,
        },
      },
      edge: {
        type: 'quadratic',
        style: {
          endArrow: true,
          stroke: '#cbd5e1',
          lineWidth: 1,
          labelText: (d: any) => d.data?.label ?? '',
          labelFontSize: 9,
          labelFill: '#6b7280',
          labelBackground: true,
          labelBackgroundFill: '#ffffff',
          labelBackgroundOpacity: 0.85,
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
        {!status && (
          <div
            style={{
              position: 'absolute',
              inset: 0,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 24,
            }}
          >
            <div className="da-skeleton" style={{ width: 64, height: 64, borderRadius: 999 }} />
            <div className="da-skeleton" style={{ width: 88, height: 88, borderRadius: 999 }} />
            <div className="da-skeleton" style={{ width: 56, height: 56, borderRadius: 999 }} />
          </div>
        )}
        <div className="da-toolbar" style={{ position: 'absolute', top: 12, left: 12, right: 12 }}>
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="搜索实体"
            className="da-input"
            style={{ width: 180 }}
          />
          <button className="da-btn da-btn-sm" onClick={() => zoom(1.2)} title="放大">
            <Icon name="zoomIn" size="sm" />
          </button>
          <button className="da-btn da-btn-sm" onClick={() => zoom(1 / 1.2)} title="缩小">
            <Icon name="zoomOut" size="sm" />
          </button>
          <span className="da-small" style={{ minWidth: 40, textAlign: 'center' }}>{zoomPct}%</span>
          <button
            className="da-btn da-btn-sm"
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
            className="da-btn da-btn-sm"
            onClick={() => {
              loadStatus().then(setStatus);
              loadGraph();
            }}
          >
            <Icon name="refresh" size="sm" /> 刷新
          </button>
          <button className="da-btn da-btn-primary da-btn-sm" onClick={() => setConfigOpen(true)}>
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
              color: 'var(--da-danger)',
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
            }}
          >
            <EmptyIllustration variant="graph" caption="尚未构建知识图谱，点击「开始构建图谱」" />
          </div>
        )}
        {graph && graph.nodes.length === 0 && hasBuild && !isActive(status) && (
          <div
            style={{
              position: 'absolute',
              bottom: 8,
              left: '50%',
              transform: 'translateX(-50%)',
              color: 'var(--da-text-muted)',
              fontSize: '0.75rem',
            }}
          >
            暂无抽取到的实体（构建完成后自动连线）
          </div>
        )}
      </div>

      <div style={panelStyle}>
        <div className="da-accent-title" style={{ marginBottom: 10 }}>图谱概览</div>
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
            background: 'var(--da-border)',
            borderRadius: 3,
            margin: '8px 0 12px',
            overflow: 'hidden',
          }}
        >
          <div
            style={{
              height: '100%',
              width: `${Math.round((status?.progress ?? 0) * 100)}%`,
              background: 'var(--da-success)',
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
            <div style={{ fontWeight: 600, color: 'var(--da-text)', margin: '12px 0 6px' }}>解析明细</div>
            {status.units.map((u, i) => (
              <div key={`${u.unitName}-${i}`} style={{ marginBottom: 6 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <span
                    style={{
                      width: 8,
                      height: 8,
                      borderRadius: 2,
                      background: STATUS_COLOR[u.status] ?? 'var(--da-text-muted)',
                      display: 'inline-block',
                    }}
                  />
                  <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {u.unitName}
                  </span>
                  <span style={{ color: STATUS_COLOR[u.status] ?? 'var(--da-text-muted)' }}>
                    {STATUS_LABEL[u.status] ?? u.status}
                  </span>
                </div>
                {u.status === 'FAILED' && u.errorMsg && (
                  <div style={{ color: 'var(--da-danger)', fontSize: '0.7rem', marginLeft: 14, marginTop: 2 }}>
                    {u.errorMsg}
                  </div>
                )}
              </div>
            ))}
          </>
        )}
        {graph && Object.keys(graph.typeDist).length > 0 && (
          <>
            <div style={{ fontWeight: 600, color: 'var(--da-text)', margin: '12px 0 6px' }}>实体类型</div>
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
    <div className="da-stat-row">
      <span>{label}</span>
      <b className="da-num">{value}</b>
    </div>
  );
}
