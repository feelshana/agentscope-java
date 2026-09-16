import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Graph } from '@antv/g6';
import { SemanticGraphData, SemanticGraphNode, SemanticGraphEdge, getSemanticGraph } from '../api/semantic';
import Markdown from './Markdown';

/** 维度表节点填充色 */
const DIMENSION_COLOR = '#5B8FF9';
/** 事实表节点填充色 */
const FACT_COLOR = '#F6903D';
/** Cube 节点填充色 */
const CUBE_COLOR = '#61DDAA';
/** 高亮边框色 */
const HIGHLIGHT_STROKE = '#EF4444';
/** 关系边颜色 */
const REL_EDGE_COLOR = '#8ea2c0';
/** Cube-base 边颜色 */
const CUBE_BASE_EDGE_COLOR = '#61DDAA';

const S: Record<string, React.CSSProperties> = {
  container: {
    position: 'relative', flex: 1, minWidth: 0, minHeight: 420,
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
  panel: {
    width: 320, flexShrink: 0, minHeight: 0, overflowY: 'auto',
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 12,
    padding: '10px 12px', display: 'flex', flexDirection: 'column', gap: 10,
  },
  panelSection: { display: 'flex', flexDirection: 'column', gap: 6 },
  panelHead: {
    fontSize: '0.78rem', fontWeight: 700, color: '#334155', cursor: 'pointer',
  },
  panelItem: {
    fontSize: '0.74rem', lineHeight: 1.55, color: '#334155',
    background: '#f8fafc', border: '1px solid #eef2f7', borderRadius: 8,
    padding: '6px 8px', display: 'flex', flexDirection: 'column', gap: 2,
  },
  panelCode: {
    fontSize: '0.7rem', color: '#0f766e', background: '#f0fdfa',
    border: '1px solid #ccfbf1', borderRadius: 4, padding: '1px 5px',
    alignSelf: 'flex-start', whiteSpace: 'pre-wrap', wordBreak: 'break-all',
  },
  panelDoc: {
    fontSize: '0.74rem', lineHeight: 1.6, color: '#334155',
    background: '#f8fafc', border: '1px solid #eef2f7', borderRadius: 8, padding: '8px 10px',
  },
};

function nodeColor(type: string, kind?: string): string {
  if (type === 'cube') return CUBE_COLOR;
  return kind === 'fact' ? FACT_COLOR : DIMENSION_COLOR;
}

const TYPE_LABEL: Record<string, string> = {
  model: '逻辑表',
  cube: 'Cube',
};

const KIND_LABEL: Record<string, string> = {
  dimension: '维度表',
  fact: '事实表',
};

interface Props {
  groupId: string;
}

/**
 * 语义模型图谱组件。基于 AntV G6 渲染语义模型的可视化图谱。
 *
 * - 维度表节点：浅蓝圆角矩形
 * - 事实表节点：浅橙圆角矩形
 * - Cube 节点：绿色圆角矩形
 * - 关系边：带标签有向箭头
 * - Cube-base 边：绿色虚线
 */
export default function SemanticGraphView({ groupId }: Props) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const graphRef = useRef<any>(null);
  const [data, setData] = useState<SemanticGraphData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<any | null>(null);

  useEffect(() => {
    if (!groupId) return;
    setSelected(null);
    let cancelled = false;
    getSemanticGraph(groupId)
      .then(g => { if (!cancelled) setData(g); })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); });
    return () => { cancelled = true; };
  }, [groupId]);

  const view = useMemo(() => {
    if (!data) return null;

    const nodes = data.nodes.map((n: SemanticGraphNode) => ({
      id: n.id,
      data: {
        label: n.label,
        type: n.type,
        tableName: n.tableName,
        kind: n.kind,
        baseObject: n.baseObject,
        description: n.description,
        columns: n.columns,
        measures: n.measures,
        dimensions: n.dimensions,
        timeDimensions: n.timeDimensions,
      },
    }));

    const edges = data.edges.map((e: SemanticGraphEdge) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      data: {
        label: e.label,
        type: e.type,
        joinType: e.joinType,
        condition: e.condition,
        isCubeBase: e.type === 'cube-base',
      },
    }));

    return { nodes, edges };
  }, [data]);

  useEffect(() => {
    if (!containerRef.current || !view) return;

    // 销毁旧图
    if (graphRef.current) {
      try { graphRef.current.destroy(); } catch { /* ignore */ }
      graphRef.current = null;
    }

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
        manyBody: { strength: -650, distanceMax: 650 },
        link: { distance: 160, strength: 0.6 },
        collide: { radius: 78 },
      },
      node: {
        type: 'rect',
        style: {
          size: (d: any) => (d.data?.type === 'cube' ? [148, 36] : [148, 42]),
          radius: 10,
          fill: (d: any) => (d.data?.type === 'cube' ? '#f0fdf9' : '#ffffff'),
          stroke: (d: any) =>
            d.data?.highlight
              ? HIGHLIGHT_STROKE
              : nodeColor(d.data?.type ?? 'model', d.data?.kind),
          lineWidth: (d: any) => (d.data?.highlight ? 3 : 1.5),
          shadowBlur: 10,
          shadowColor: 'rgba(15, 23, 42, 0.10)',
          shadowOffsetY: 2,
          labelText: (d: any) => d.data?.label ?? d.id,
          labelPlacement: 'center',
          labelFontSize: (d: any) => (d.data?.type === 'cube' ? 11 : 12),
          labelFontWeight: (d: any) => (d.data?.type === 'cube' ? 'normal' : 600),
          labelFill: '#334155',
        },
      },
      edge: {
        type: 'quadratic',
        style: {
          endArrow: true,
          stroke: (d: any) => d.data?.isCubeBase ? CUBE_BASE_EDGE_COLOR : REL_EDGE_COLOR,
          lineWidth: (d: any) => d.data?.isCubeBase ? 1 : 1.5,
          lineDash: (d: any) => d.data?.isCubeBase ? [4, 4] : undefined,
          labelText: (d: any) => d.data?.label ?? '',
          labelFontSize: 9,
          labelFill: '#94a3b8',
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
                ${d.condition ? `条件：${d.condition}<br/>` : ''}
                ${d.joinType ? `<span style="color:#94a3b8">${d.joinType}</span>` : ''}
              </div>`;
            }
            const typeLabel = d.type === 'cube' ? 'Cube 指标' : (KIND_LABEL[d.kind] ?? d.type);
            let detail = '';
            if (d.type === 'cube') {
              const ms = (d.measures ?? []).map((m: any) => m.name).join(', ');
              const ds = (d.dimensions ?? []).map((dm: any) => dm.name).join(', ');
              if (ms) detail += `度量：${ms}<br/>`;
              if (ds) detail += `维度：${ds}<br/>`;
            } else {
              const cols = (d.columns ?? []).slice(0, 8)
                .map((c: any) => `${c.name} <span style="color:#94a3b8">${c.type}</span>`)
                .join('<br/>');
              if (cols) detail += cols;
            }
            return `<div style="font-size:12px;line-height:1.5">
              <b>${d.label ?? it.id}</b>
              <span style="color:#94a3b8"> · ${typeLabel}</span><br/>
              ${d.tableName ? `<span style="color:#94a3b8">${d.tableName}</span><br/>` : ''}
              ${d.description ? `${d.description}<br/>` : ''}
              ${detail}
            </div>`;
          },
        },
      ],
    });

    graph.render();
    graphRef.current = graph;

    graph.on('node:click', (e: any) => {
      const id = e.target?.id;
      const node = view.nodes.find((n: any) => n.id === id);
      if (node) setSelected(node.data);
    });
    graph.on('canvas:click', () => setSelected(null));

    return () => {
      try { graph.destroy(); } catch { /* ignore */ }
      graphRef.current = null;
    };
  }, [view]);

  // 统计数据
  const modelCount = data?.nodes.filter(n => n.type === 'model').length ?? 0;
  const cubeCount = data?.nodes.filter(n => n.type === 'cube').length ?? 0;
  const relCount = data?.edges.filter(e => e.type === 'relationship').length ?? 0;
  const meta = data?.meta;
  const labelOf = (id: string) => data?.nodes.find(n => n.id === id)?.label ?? id;
  const nodeRelations = selected
    ? (view?.edges ?? []).filter(
        (e: any) =>
          e.data?.type === 'relationship' &&
          (e.source === selected.id || e.target === selected.id),
      )
    : [];

  if (error) {
    return <div style={S.container}><div style={S.empty}>加载失败：{error}</div></div>;
  }

  if (!data) {
    return <div style={S.container}><div style={S.empty}>加载中…</div></div>;
  }

  if (data.nodes.length === 0) {
    return (
      <div style={S.container}>
        <div style={S.empty}>暂无语义模型。请上传 ontology.json 或使用自动建模功能。</div>
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', gap: 12, alignItems: 'stretch', flex: 1, minHeight: 0 }}>
      <div style={{ ...S.container, flex: 1, minWidth: 0 }}>
        <div ref={containerRef} style={{ position: 'absolute', inset: 0 }} />

        <div style={S.stats}>
          <div style={{ fontWeight: 'bold', marginBottom: 4 }}>语义模型</div>
          <div>逻辑表：{modelCount}</div>
          <div>Cube：{cubeCount}</div>
          <div>关系：{relCount}</div>
          {(meta?.ruleCount ?? 0) > 0 && <div>规则：{meta?.ruleCount}</div>}
          {(meta?.limitationCount ?? 0) > 0 && <div>局限：{meta?.limitationCount}</div>}
        </div>

        <div style={S.legend}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: 3, background: '#fff', border: `2px solid ${DIMENSION_COLOR}` }} />
            维度表
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: 3, background: '#fff', border: `2px solid ${FACT_COLOR}` }} />
            事实表
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: 3, background: '#f0fdf9', border: `2px solid ${CUBE_COLOR}` }} />
            Cube
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ display: 'inline-block', width: 16, height: 2, background: REL_EDGE_COLOR }} />
            关系
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ display: 'inline-block', width: 16, height: 2, background: CUBE_BASE_EDGE_COLOR, borderTop: '1px dashed' }} />
            基础模型
          </div>
        </div>
      </div>

      {selected && (
        <div style={S.panel}>
          <div style={S.panelSection}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <span style={{ ...S.panelHead, cursor: 'default' }}>
                  节点详情 · {selected.type === 'cube' ? 'Cube' : (KIND_LABEL[selected.kind] ?? '逻辑表')}
                </span>
                <button
                  onClick={() => setSelected(null)}
                  aria-label="关闭节点详情"
                  style={{
                    marginLeft: 'auto', border: 'none', background: 'transparent',
                    cursor: 'pointer', color: '#94a3b8', fontSize: '0.95rem', lineHeight: 1,
                  }}
                >
                  ×
                </button>
              </div>
              <div style={S.panelItem}>
                <div style={{ fontWeight: 700, fontSize: '0.8rem' }}>{selected.label}</div>
                {selected.tableName && (
                  <div style={{ color: '#94a3b8' }}>{selected.tableName}</div>
                )}
                {selected.description && (
                  <div style={{ color: '#475569' }}>{selected.description}</div>
                )}
              </div>
              {selected.type !== 'cube' && (selected.columns ?? []).length > 0 && (
                <details open style={S.panelSection}>
                  <summary style={S.panelHead}>属性（{selected.columns.length}）</summary>
                  {selected.columns.map((c: any) => (
                    <div key={c.name} style={S.panelItem}>
                      <div>
                        <b>{c.label ?? c.name}</b>
                        <span style={{ color: '#94a3b8' }}> {c.name} · {c.type}</span>
                      </div>
                      {c.description && <div style={{ color: '#475569' }}>{c.description}</div>}
                    </div>
                  ))}
                </details>
              )}
              {selected.type !== 'cube' && nodeRelations.length > 0 && (
                <details open style={S.panelSection}>
                  <summary style={S.panelHead}>关系（{nodeRelations.length}）</summary>
                  {nodeRelations.map((r: any) => (
                    <div key={r.id} style={S.panelItem}>
                      <div>
                        <b>{r.data.label}</b>
                        <span style={{ color: '#94a3b8' }}> · {r.data.joinType ?? ''}</span>
                      </div>
                      <div style={{ color: '#475569' }}>
                        {r.source === selected.id ? '→ ' : '← '}
                        {labelOf(r.source === selected.id ? r.target : r.source)}
                      </div>
                      {r.data.condition && <code style={S.panelCode}>{r.data.condition}</code>}
                    </div>
                  ))}
                </details>
              )}
              {selected.type === 'cube' && (
                <details open style={S.panelSection}>
                  <summary style={S.panelHead}>度量 / 维度</summary>
                  {(selected.measures ?? []).map((m: any) => (
                    <div key={m.name} style={S.panelItem}>
                      <div>
                        <b>Σ {m.name}</b> <code style={S.panelCode}>{m.expression}</code>
                      </div>
                      {m.description && <div style={{ color: '#475569' }}>{m.description}</div>}
                    </div>
                  ))}
                  {(selected.dimensions ?? []).length > 0 && (
                    <div style={S.panelItem}>
                      维度：{selected.dimensions.map((d: any) => d.name).join('、')}
                    </div>
                  )}
                  {(selected.timeDimensions ?? []).length > 0 && (
                    <div style={S.panelItem}>
                      时间维度：{selected.timeDimensions.map((d: any) => d.name).join('、')}
                    </div>
                  )}
                  {selected.baseObject && (
                    <div style={S.panelItem}>基础模型：{labelOf(selected.baseObject)}</div>
                  )}
                </details>
              )}
            </div>
          {meta && meta.rules.length > 0 && (
            <details style={S.panelSection}>
              <summary style={S.panelHead}>业务规则（{meta.rules.length}）</summary>
              {meta.rules.map((r, i) => (
                <div key={r.id ?? i} style={S.panelItem}>
                  <div style={{ fontWeight: 600 }}>{r.name ?? r.id}</div>
                  {r.description && <div style={{ color: '#475569' }}>{r.description}</div>}
                  {r.sqlHint && <code style={S.panelCode}>{r.sqlHint}</code>}
                </div>
              ))}
            </details>
          )}
          {meta && meta.limitations.length > 0 && (
            <details style={S.panelSection}>
              <summary style={S.panelHead}>数据局限（{meta.limitations.length}）</summary>
              {meta.limitations.map((l, i) => (
                <div key={l.id ?? i} style={S.panelItem}>
                  <div style={{ fontWeight: 600 }}>{l.name ?? l.id}</div>
                  {l.description && <div style={{ color: '#475569' }}>{l.description}</div>}
                </div>
              ))}
            </details>
          )}
        </div>
      )}
    </div>
  );
}
