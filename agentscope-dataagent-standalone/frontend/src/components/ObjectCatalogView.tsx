import React, { useEffect, useMemo, useState } from 'react';
import {
  getOntologyModel,
  OntologyModelData,
  OntoObject,
  OntoRelationship,
} from '../api/ontology';

const KIND_COLORS: Record<string, string> = {
  dimension: '#5B8FF9',
  fact: '#F6903D',
  metric: '#61DDAA',
  derived: '#9B59B6',
};

const KIND_LABELS: Record<string, string> = {
  dimension: '维度表',
  fact: '事实表',
  metric: '指标',
};

const S: Record<string, React.CSSProperties> = {
  wrap: { display: 'flex', height: '100%', minHeight: 0, gap: 0 },
  left: {
    width: 260,
    minWidth: 260,
    borderRight: '1px solid #e2e8f0',
    overflowY: 'auto',
    padding: '8px 0',
    background: '#fafbfc',
  },
  right: { flex: 1, overflowY: 'auto', padding: '16px 20px' },
  empty: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    height: '100%',
    color: '#94a3b8',
    fontSize: '0.9rem',
  },
  item: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '7px 12px',
    cursor: 'pointer',
    fontSize: '0.82rem',
    borderBottom: '1px solid transparent',
    transition: 'background 0.15s',
  },
  itemActive: {
    background: '#eef4ff',
    borderRight: '3px solid #5B8FF9',
    fontWeight: 600,
  },
  badge: {
    fontSize: '0.65rem',
    padding: '1px 6px',
    borderRadius: 10,
    color: '#fff',
    whiteSpace: 'nowrap',
  },
  card: {
    border: '1px solid #e2e8f0',
    borderRadius: 10,
    padding: '14px 18px',
    marginBottom: 16,
    background: '#fff',
  },
  cardTitle: {
    fontSize: '0.95rem',
    fontWeight: 600,
    marginBottom: 8,
    color: '#1e293b',
  },
  table: {
    width: '100%',
    borderCollapse: 'collapse' as const,
    fontSize: '0.8rem',
  },
  th: {
    textAlign: 'left' as const,
    padding: '4px 8px',
    borderBottom: '2px solid #e2e8f0',
    color: '#64748b',
    fontWeight: 500,
  },
  td: {
    padding: '4px 8px',
    borderBottom: '1px solid #f1f5f9',
    color: '#334155',
  },
  relRow: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '5px 0',
    fontSize: '0.82rem',
    borderBottom: '1px solid #f1f5f9',
  },
  codeBlock: {
    background: '#f8fafc',
    border: '1px solid #e2e8f0',
    borderRadius: 6,
    padding: '8px 12px',
    fontSize: '0.78rem',
    fontFamily: 'monospace',
    whiteSpace: 'pre-wrap' as const,
    overflowX: 'auto' as const,
    color: '#475569',
    marginTop: 8,
  },
};

interface Props {
  groupId?: string;
}

export default function ObjectCatalogView({ groupId }: Props) {
  const [model, setModel] = useState<OntologyModelData | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!groupId) return;
    let cancelled = false;
    const load = async () => {
      const m = await getOntologyModel(groupId);
      if (cancelled) return;
      setModel(m);
      if (m && m.objects) {
        const keys = Object.keys(m.objects);
        if (keys.length > 0) setSelected(keys[0]);
      }
    };
    load().catch(e => {
      if (!cancelled) setError(e instanceof Error ? e.message : String(e));
    });
    return () => {
      cancelled = true;
    };
  }, [groupId]);

  const objectKeys = useMemo(() => (model ? Object.keys(model.objects) : []), [model]);

  const relCountMap = useMemo(() => {
    if (!model?.relationships) return {};
    const counts: Record<string, number> = {};
    for (const r of Object.values(model.relationships)) {
      counts[r.from] = (counts[r.from] ?? 0) + 1;
      counts[r.to] = (counts[r.to] ?? 0) + 1;
    }
    return counts;
  }, [model?.relationships]);

  if (error) return <div style={S.empty}>加载失败：{error}</div>;
  if (!model) return <div style={S.empty}>加载本体数据…</div>;
  if (objectKeys.length === 0) {
    return (
      <div style={S.empty}>
        尚未生成本体模型。可在知识图谱页点击"生成本体图谱"（按数据表或按本体文件）。
      </div>
    );
  }

  const selObj: OntoObject | undefined = selected ? model.objects[selected] : undefined;
  const selRels: [string, OntoRelationship][] = model.relationships
    ? Object.entries(model.relationships).filter(
        ([, r]) => r.from === selected || r.to === selected,
      )
    : [];

  return (
    <div style={S.wrap}>
      {/* ---------- left: object list ---------- */}
      <div style={S.left}>
        {objectKeys.map(key => {
          const obj = model.objects[key];
          const kind = obj.kind ?? 'dimension';
          const count = relCountMap[key] ?? 0;
          return (
            <div
              key={key}
              style={{
                ...S.item,
                ...(selected === key ? S.itemActive : {}),
              }}
              onClick={() => setSelected(key)}
            >
              <span
                style={{
                  ...S.badge,
                  background: KIND_COLORS[kind] ?? '#94a3b8',
                }}
              >
                {KIND_LABELS[kind] ?? kind}
              </span>
              <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {obj.label ?? key}
              </span>
              {count > 0 && (
                <span style={{ fontSize: '0.68rem', color: '#94a3b8' }}>{count}↔</span>
              )}
            </div>
          );
        })}
      </div>

      {/* ---------- right: detail ---------- */}
      <div style={S.right}>
        {!selObj ? (
          <div style={S.empty}>← 请从左侧选择一个对象</div>
        ) : (
          <>
            {/* 基本信息 */}
            <div style={S.card}>
              <div style={S.cardTitle}>
                {selObj.label ?? selected}
                <span style={{ color: '#94a3b8', fontWeight: 400, marginLeft: 8 }}>
                  {selected}
                </span>
              </div>
              <div style={{ fontSize: '0.8rem', color: '#64748b', lineHeight: 1.6 }}>
                {selObj.table && (
                  <div>
                    <b>物理表：</b>
                    <code style={{ background: '#f1f5f9', padding: '1px 5px', borderRadius: 3 }}>
                      {selObj.table}
                    </code>
                  </div>
                )}
                {selObj.kind && <div><b>类型：</b>{KIND_LABELS[selObj.kind] ?? selObj.kind}</div>}
                {selObj.identity && <div><b>身份键：</b>{selObj.identity}</div>}
                {selObj.time_field && <div><b>时间字段：</b>{selObj.time_field}</div>}
                {selObj.description && <div><b>描述：</b>{selObj.description}</div>}
              </div>
            </div>

            {/* 属性表 */}
            <div style={S.card}>
              <div style={S.cardTitle}>属性（{Object.keys(selObj.properties ?? {}).length}）</div>
              <table style={S.table}>
                <thead>
                  <tr>
                    <th style={S.th}>列名</th>
                    <th style={S.th}>标签</th>
                    <th style={S.th}>类型</th>
                    <th style={S.th}>备注</th>
                  </tr>
                </thead>
                <tbody>
                  {Object.entries(selObj.properties ?? {}).map(([name, prop]) => (
                    <tr key={name}>
                      <td style={S.td}>
                        <code style={{ background: '#f1f5f9', padding: '0 4px', borderRadius: 3 }}>
                          {prop.column ?? name}
                        </code>
                      </td>
                      <td style={S.td}>{prop.label ?? name}</td>
                      <td style={{ ...S.td, color: '#64748b', fontFamily: 'monospace' }}>
                        {prop.type ?? 'string'}
                      </td>
                      <td style={{ ...S.td, color: '#94a3b8', fontSize: '0.78rem' }}>
                        {prop.note ?? ''}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* 关系列表 */}
            {selRels.length > 0 && (
              <div style={S.card}>
                <div style={S.cardTitle}>关系（{selRels.length}）</div>
                {selRels.map(([id, rel]) => {
                  const isSource = rel.from === selected;
                  const other = isSource ? rel.to : rel.from;
                  const joinText =
                    rel.join && rel.join.length > 0
                      ? rel.join.map(j => `${j.left ?? '?'}=${j.right ?? '?'}`).join(', ')
                      : rel.note ?? '?';
                  return (
                    <div key={id} style={S.relRow}>
                      <span style={{ color: '#64748b', fontSize: '0.75rem' }}>
                        {isSource ? '→' : '←'}
                      </span>
                      <span
                        style={{
                          cursor: 'pointer',
                          color: '#3b82f6',
                          textDecoration: 'underline',
                        }}
                        onClick={() => {
                          if (model.objects[other]) setSelected(other);
                        }}
                      >
                        {model.objects[other]?.label ?? other}
                      </span>
                      <span style={{ color: '#94a3b8', fontSize: '0.75rem' }}>
                        [{rel.cardinality ?? 'many_to_many'}]
                      </span>
                      <span style={{ fontSize: '0.78rem', color: '#64748b' }}>{joinText}</span>
                      {rel.label && (
                        <span style={{ marginLeft: 'auto', fontSize: '0.78rem', color: '#475569' }}>
                          {rel.label}
                        </span>
                      )}
                    </div>
                  );
                })}
              </div>
            )}

            {/* 指标（附属于本对象的） */}
            {model.metrics &&
              Object.entries(model.metrics)
                .filter(([, m]) => m.object === selected)
                .length > 0 && (
                <div style={S.card}>
                  <div style={S.cardTitle}>指标</div>
                  {Object.entries(model.metrics)
                    .filter(([, m]) => m.object === selected)
                    .map(([key, m]) => (
                      <div key={key} style={{ fontSize: '0.82rem', padding: '4px 0' }}>
                        <b>{m.label ?? key}</b> = {m.aggregate}({m.column ?? '*'})
                        {m.unit && <span style={{ color: '#94a3b8' }}> [{m.unit}]</span>}
                        {m.description && (
                          <div style={{ color: '#64748b', fontSize: '0.78rem' }}>{m.description}</div>
                        )}
                      </div>
                    ))}
                </div>
              )}
          </>
        )}
      </div>
    </div>
  );
}
