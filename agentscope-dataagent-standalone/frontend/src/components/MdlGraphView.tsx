import { useEffect, useMemo, useState } from 'react';
import type { MdlView } from '../api/semanticModeling';
import { getMdlView } from '../api/semanticModeling';
import ReadOnlyRelationGraph from './ReadOnlyRelationGraph';
import type { ReadOnlyGraphEdge, ReadOnlyGraphNode } from './ReadOnlyRelationGraph';

/**
 * MDL 只读图谱（specs/013 M2，ADR 0024 D5）：逻辑模型节点 + 关系边 + Cube 卡列表 + 发布状态条。
 * 只读——无任何编辑交互；数据来自结构化 /mdl/view 端点，前端不解析 YAML。
 */

const MODEL_COLOR = '#5B8FF9';

const JOIN_LABEL: Record<string, string> = {
  MANY_TO_ONE: '多对一',
  ONE_TO_MANY: '一对多',
  ONE_TO_ONE: '一对一',
};

const S: Record<string, React.CSSProperties> = {
  root: { display: 'flex', flexDirection: 'column', gap: 12 },
  statusBar: {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    padding: '8px 12px',
    background: 'var(--da-surface, #fff)',
    border: '1px solid var(--da-border)',
    borderRadius: 10,
    fontSize: 13,
  },
  graph: {
    position: 'relative',
    width: '100%',
    height: 480,
    background: '#f8fafc',
    border: '1px solid #e2e8f0',
    borderRadius: 12,
    overflow: 'hidden',
  },
  legend: {
    position: 'absolute',
    bottom: 8,
    left: 8,
    background: 'rgba(255,255,255,0.92)',
    border: '1px solid #e2e8f0',
    borderRadius: 8,
    padding: '6px 10px',
    fontSize: '0.7rem',
    color: '#475569',
    display: 'flex',
    gap: 12,
  },
  empty: {
    position: 'absolute',
    inset: 0,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    color: '#94a3b8',
    fontSize: '0.85rem',
  },
  cubeCard: {
    background: 'var(--da-surface, #fff)',
    border: '1px solid var(--da-border)',
    borderRadius: 10,
    padding: 12,
  },
  member: { fontSize: 12, color: 'var(--da-text-2, #475569)', lineHeight: 1.7 },
  issue: { fontSize: 12, color: 'var(--da-danger)' },
};

export default function MdlGraphView({ groupId }: { groupId: string }) {
  const [view, setView] = useState<MdlView | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setView(null);
    setError(null);
    const refresh = () => { void getMdlView(groupId)
      .then(v => { if (!cancelled) setView(v); })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); }); };
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) refresh();
    };
    refresh();
    window.addEventListener('modeling:updated', update);
    return () => { cancelled = true; window.removeEventListener('modeling:updated', update); };
  }, [groupId]);

  const modelLabels = useMemo(() => {
    const labels = new Map<string, string>();
    for (const model of view?.models ?? []) {
      const description = model.description?.trim();
      const meaning =
        description && description !== model.datasetName.trim()
          ? description
          : '业务含义待补充';
      labels.set(model.modelName, `${model.datasetName} — ${meaning}`);
    }
    return labels;
  }, [view]);

  const graphData = useMemo(() => {
    if (!view) return null;
    const nodes: ReadOnlyGraphNode[] = view.models.map(m => ({
      id: m.modelName,
      data: {
        label: modelLabels.get(m.modelName) ?? '未知表',
        modelName: m.modelName,
        columnCount: m.columns.length,
        description: m.description ?? '',
      },
    }));
    const edges: ReadOnlyGraphEdge[] = view.relations.map(r => ({
      id: r.name,
      source: r.leftModel,
      target: r.rightModel,
      data: {
        label: `${JOIN_LABEL[r.joinType] ?? r.joinType}`,
        columns: r.sourceColumns.map((c, i) => `${c} = ${r.targetColumns[i] ?? '?'}`).join('、'),
        condition: r.condition,
      },
    }));
    return { nodes, edges };
  }, [modelLabels, view]);

  if (error) {
    return <div style={{ color: 'var(--da-danger)', fontSize: '0.85rem' }}>MDL 视图加载失败：{error}</div>;
  }
  if (!view) {
    return <div style={{ color: '#94a3b8', fontSize: '0.85rem' }}>MDL 视图加载中…</div>;
  }

  return (
    <div style={S.root}>
      {/* 发布状态条 */}
      <div style={S.statusBar}>
        <span className="da-badge">
          {view.state === 'PUBLISHED'
            ? `已发布 v${view.version}`
            : view.state === 'DIRTY'
              ? `有未生效变更（当前 v${view.version}）`
              : view.state === 'INITIALIZING'
                ? '基础模型初始化中'
                : view.state === 'FAILED'
                  ? '基础模型初始化失败'
                  : '暂无基础模型'}
        </span>
        {view.publishedAt && (
          <span className="da-small" style={{ color: 'var(--da-text-3)' }}>
            发布于 {view.publishedAt.slice(0, 19).replace('T', ' ')}
          </span>
        )}
        {view.changed && (
          <span className="da-badge" style={{ color: 'var(--da-warn)', borderColor: 'var(--da-warn)' }}>
            草稿有变更，待发布
          </span>
        )}
        <span className="da-small" style={{ color: 'var(--da-text-3)' }}>
          模型 {view.models.length} · 关系 {view.relations.length} · 视图 {view.views.length}
        </span>
      </div>
      {view.issues.map((i, idx) => (
        <div key={idx} style={S.issue}>
          {i.message}
        </div>
      ))}

      {/* ERD 图 */}
      <div style={S.graph}>
        {graphData && <ReadOnlyRelationGraph data={graphData} />}
        {graphData && graphData.nodes.length === 0 && (
          <div style={S.empty}>暂无逻辑模型，请先上传数据或关联数据表</div>
        )}
        {graphData && graphData.nodes.length > 0 && (
          <div style={S.legend}>
            <span>
              <span
                style={{
                  display: 'inline-block',
                  width: 12,
                  height: 12,
                  borderRadius: 3,
                  background: MODEL_COLOR,
                  marginRight: 4,
                  verticalAlign: -2,
                }}
              />
              逻辑模型
            </span>
            <span>边标签 = 连接类型（悬停看列对）</span>
          </div>
        )}
      </div>

      {view.views.length > 0 && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: 12 }}>
          {view.views.map(v => (
            <div key={v.name} className="da-card">
              <strong>{v.name}</strong>
              {v.description && <p>{v.description}</p>}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
