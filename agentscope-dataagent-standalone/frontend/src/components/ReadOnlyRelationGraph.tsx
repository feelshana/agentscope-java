import { escapeHtml } from '../utils/html';
import { useEffect, useRef } from 'react';
import { Graph } from '@antv/g6';

export interface ReadOnlyGraphNode {
  id: string;
  data: Record<string, unknown>;
}

export interface ReadOnlyGraphEdge {
  id: string;
  source: string;
  target: string;
  data: Record<string, unknown>;
}

export interface ReadOnlyGraphData {
  nodes: ReadOnlyGraphNode[];
  edges: ReadOnlyGraphEdge[];
}

/** Shared read-only relationship graph used by the MDL view and HITL proposal cards. */
export default function ReadOnlyRelationGraph({
  data,
  compact = false,
}: {
  data: ReadOnlyGraphData;
  compact?: boolean;
}) {
  const containerRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!containerRef.current) return;
    const graph = new Graph({
      container: containerRef.current,
      autoFit: 'view',
      data,
      // Small schemas read best as a layered layout; force layout only spreads large ones.
      layout: compact || data.nodes.length <= 12
        ? { type: 'antv-dagre', rankdir: compact ? 'LR' : 'TB', ranksep: compact ? 50 : 90, nodesep: compact ? 20 : 60 }
        : {
            type: 'd3-force',
            alphaDecay: 0.1,
            alphaMin: 0.01,
            velocityDecay: 0.6,
            center: { strength: 0.1 },
            manyBody: { strength: -500, distanceMax: 700 },
            link: { distance: 140, strength: 0.8 },
            collide: { radius: 50 },
          },
      node: {
        type: 'rect',
        style: {
          size: (d: any) => {
            const text = `${d.data?.label ?? d.id}`;
            return [Math.min(260, Math.max(compact ? 118 : 150, text.length * (compact ? 7 : 8) + 36)), compact ? 38 : 52];
          },
          radius: 10,
          fill: '#5B8FF9',
          stroke: '#4a7ae0',
          lineWidth: 1,
          shadowColor: 'rgba(15,23,42,0.12)',
          shadowBlur: 8,
          shadowOffsetY: 2,
          labelText: (d: any) => compact || d.data?.columnCount === undefined
            ? `${d.data?.label ?? d.id}` : `${d.data?.label ?? d.id}\n${d.data.columnCount} 个字段`,
          labelPlacement: 'center',
          labelFontSize: compact ? 11 : 13,
          labelLineHeight: 18,
          labelFontWeight: 600,
          labelFill: '#ffffff',
          labelWordWrap: true,
          labelMaxLines: 2,
          labelMaxWidth: '100%',
        },
      },
      edge: {
        type: compact ? 'line' : 'quadratic',
        style: {
          endArrow: true,
          stroke: '#8ea2c0',
          lineWidth: 1.5,
          labelText: (d: any) => d.data?.label ?? '',
          labelFontSize: 11,
          labelFill: '#475569',
          labelMaxWidth: 160,
          labelAutoRotate: false,
          labelBackground: true,
          labelBackgroundFill: 'rgba(255,255,255,0.9)',
          labelBackgroundLineWidth: 0,
          labelBackgroundRadius: 3,
          labelPadding: [2, 4, 2, 4],
        },
      },
      plugins: compact
        ? []
        : [
            {
              type: 'tooltip',
              getContent: (_e: any, items: any[]) => {
                const item = items?.[0];
                if (!item) return '';
                const d = item.data ?? {};
                if (item.source && item.target) {
                  return `<div style="font-size:12px;line-height:1.5"><b>${escapeHtml(d.label ?? '')}</b><br/>${
                    d.columns ? `关联列：${escapeHtml(d.columns)}<br/>` : ''
                  }<span style="color:#94a3b8">${escapeHtml(d.condition ?? '')}</span></div>`;
                }
                return `<div style="font-size:12px;line-height:1.5"><b>${escapeHtml(d.label ?? item.id)}</b><br/>${
                  d.modelName ? `逻辑模型：${escapeHtml(d.modelName)}<br/>` : ''
                }列数：${escapeHtml(d.columnCount ?? 0)}${d.description ? `<br/>${escapeHtml(d.description)}` : ''}</div>`;
              },
            },
          ],
      behaviors: compact ? [] : ['drag-canvas', 'zoom-canvas', 'hover-activate'],
    } as any);
    void graph.render();
    const observer = new ResizeObserver(() => {
      try {
        graph.resize();
      } catch {
        // The graph may already be disposed while a resize callback is queued.
      }
    });
    observer.observe(containerRef.current);
    return () => {
      observer.disconnect();
      graph.destroy();
    };
  }, [compact, data]);

  return <div ref={containerRef} style={{ width: '100%', height: '100%' }} />;
}
