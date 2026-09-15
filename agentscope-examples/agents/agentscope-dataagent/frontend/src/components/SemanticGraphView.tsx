import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Graph } from '@antv/g6';
import { SemanticGraphData, SemanticGraphNode, SemanticGraphEdge, getSemanticGraph } from '../api/semantic';

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

  useEffect(() => {
    if (!groupId) return;
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
        manyBody: { strength: -500, distanceMax: 700 },
        link: { distance: 140, strength: 0.8 },
        collide: { radius: 55 },
      },
      node: {
        type: 'rect',
        style: {
          size: (d: any) => d.data?.type === 'cube' ? [100, 32] : [120, 40],
          radius: 8,
          fill: (d: any) => nodeColor(d.data?.type ?? 'model', d.data?.kind),
          stroke: (d: any) => d.data?.highlight ? HIGHLIGHT_STROKE : 'transparent',
          lineWidth: (d: any) => d.data?.highlight ? 3 : 0,
          labelText: (d: any) => d.data?.label ?? d.id,
          labelFontSize: (d: any) => d.data?.type === 'cube' ? 10 : 12,
          labelFontWeight: (d: any) => d.data?.type === 'cube' ? 'normal' : 'bold',
          labelFill: '#fff',
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
              ${d.description ? `${d.description}<br/>` : ''}
              ${detail}
            </div>`;
          },
        },
      ],
    });

    graph.render();
    graphRef.current = graph;

    return () => {
      try { graph.destroy(); } catch { /* ignore */ }
      graphRef.current = null;
    };
  }, [view]);

  // 统计数据
  const modelCount = data?.nodes.filter(n => n.type === 'model').length ?? 0;
  const cubeCount = data?.nodes.filter(n => n.type === 'cube').length ?? 0;
  const relCount = data?.edges.filter(e => e.type === 'relationship').length ?? 0;

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
    <div style={S.container}>
      <div ref={containerRef} style={{ width: '100%', height: '100%' }} />

      <div style={S.stats}>
        <div style={{ fontWeight: 'bold', marginBottom: 4 }}>语义模型</div>
        <div>逻辑表：{modelCount}</div>
        <div>Cube：{cubeCount}</div>
        <div>关系：{relCount}</div>
      </div>

      <div style={S.legend}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: 3, background: DIMENSION_COLOR }} />
          维度表
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: 3, background: FACT_COLOR }} />
          事实表
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: 3, background: CUBE_COLOR }} />
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
  );
}
