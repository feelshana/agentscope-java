import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import BackToChatHeader from '../../components/BackToChatHeader';
import {
  Dataset,
  getGroupDetail,
  getPreview,
  Preview,
  updateColumns,
} from '../../api/datasets';

const panelStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflow: 'auto',
  padding: 24,
  display: 'flex',
  flexDirection: 'column',
  gap: 20,
};

const cardStyle: React.CSSProperties = {
  background: '#ffffff',
  border: '1px solid #e2e8f0',
  borderRadius: 12,
  padding: 16,
};

const badgeStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '3px 10px',
  borderRadius: 999,
  background: '#ecfdf5',
  border: '1px solid #a7f3d0',
  color: '#047857',
  fontSize: '0.72rem',
  fontWeight: 600,
};

const buttonStyle: React.CSSProperties = {
  padding: '8px 16px',
  borderRadius: 8,
  border: '1px solid #2563eb',
  background: '#2563eb',
  color: '#ffffff',
  fontSize: '0.85rem',
  fontWeight: 600,
  cursor: 'pointer',
};

const ghostButtonStyle: React.CSSProperties = {
  padding: '6px 12px',
  borderRadius: 8,
  border: '1px solid #cbd5e1',
  background: '#ffffff',
  color: '#475569',
  fontSize: '0.8rem',
  cursor: 'pointer',
};

const thStyle: React.CSSProperties = {
  textAlign: 'left',
  padding: '8px 10px',
  borderBottom: '1px solid #e2e8f0',
  fontSize: '0.75rem',
  color: '#64748b',
  fontWeight: 600,
};

const tdStyle: React.CSSProperties = {
  padding: '8px 10px',
  borderBottom: '1px solid #f1f5f9',
  fontSize: '0.8rem',
  color: '#0f172a',
  maxWidth: 260,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '6px 8px',
  borderRadius: 6,
  border: '1px solid #cbd5e1',
  fontSize: '0.78rem',
  color: '#0f172a',
  boxSizing: 'border-box',
};

export default function DatasetDetailPage() {
  const { groupId = '', datasetId = '' } = useParams();
  const navigate = useNavigate();
  const [dataset, setDataset] = useState<Dataset | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const detail = await getGroupDetail(groupId);
      const ds = detail.datasets.find(d => d.id === datasetId) ?? null;
      setDataset(ds);
      if (ds) {
        setPreview(await getPreview(ds.id, 20));
        const init: Record<string, string> = {};
        for (const c of ds.columns) init[c.name] = c.description ?? c.originalName ?? '';
        setEdits(init);
      }
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [groupId, datasetId]);

  useEffect(() => {
    load();
  }, [load]);

  async function handleSave() {
    if (!dataset) return;
    setBusy(true);
    setSaved(false);
    try {
      const updates = dataset.columns.map(c => ({
        name: c.name,
        description: edits[c.name] ?? '',
      }));
      await updateColumns(dataset.id, updates);
      setSaved(true);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const hasLongText = (dataset?.columns ?? []).some(c => c.sqlType.startsWith('VARCHAR'));

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <BackToChatHeader title={dataset?.name ?? '数据集'} subtitle={dataset?.tableName ?? ''} />
      <div style={{ padding: '10px 24px 0' }}>
        <button style={ghostButtonStyle} onClick={() => navigate(`/configure/datasets/${groupId}`)}>
          ← 返回知识库
        </button>
        {error && (
          <span style={{ color: '#b91c1c', marginLeft: 12, fontSize: '0.8rem' }}>{error}</span>
        )}
        {saved && (
          <span style={{ color: '#047857', marginLeft: 12, fontSize: '0.8rem' }}>
            已保存，agent 立即可见新语义
          </span>
        )}
      </div>
      <div style={panelStyle}>
        <div style={cardStyle}>
          <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
            <span style={badgeStyle}>✓ 表格结构化 → NL2SQL</span>
            {hasLongText && <span style={badgeStyle}>✓ 含长文本字段 → 知识检索</span>}
          </div>
          <div style={{ fontSize: '0.8rem', color: '#475569', whiteSpace: 'pre-wrap' }}>
            {dataset?.description ?? '—'}
          </div>
        </div>

        <div style={cardStyle}>
          <div style={{ fontSize: '0.95rem', fontWeight: 600, color: '#0f172a', marginBottom: 10 }}>
            基本信息
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '6px 20px', fontSize: '0.82rem' }}>
            <div>
              <span style={{ color: '#64748b' }}>来源：</span>
              {dataset?.origin === 'datasource' ? '数据源关联' : '文件上传'}
            </div>
            <div>
              <span style={{ color: '#64748b' }}>库.表：</span>
              {dataset?.schemaName}.{dataset?.tableName}
            </div>
            <div>
              <span style={{ color: '#64748b' }}>行数：</span>
              {dataset?.rowCount ?? '-'}
            </div>
            <div>
              <span style={{ color: '#64748b' }}>来源文件：</span>
              {dataset?.sourceFileName ?? '-'}
            </div>
          </div>
        </div>

        <div style={cardStyle}>
          <div style={{ fontSize: '0.95rem', fontWeight: 600, color: '#0f172a', marginBottom: 12 }}>
            表格解析（前 {preview?.rows.length ?? 0} 行）
          </div>
          {preview && preview.rows.length > 0 ? (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ borderCollapse: 'collapse', minWidth: '100%' }}>
                <thead>
                  <tr>
                    {preview.columns.map(c => (
                      <th key={c.name} style={thStyle}>
                        <div>{c.description || c.originalName || c.name}</div>
                        <div style={{ fontWeight: 400, color: '#94a3b8' }}>
                          {c.name} · {c.sqlType}
                        </div>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {preview.rows.map((r, i) => (
                    <tr key={i}>
                      {r.map((v, j) => (
                        <td key={j} style={tdStyle} title={v ?? ''}>
                          {v ?? ''}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div style={{ color: '#94a3b8', fontSize: '0.85rem' }}>暂无数据行。</div>
          )}
        </div>

        <div style={cardStyle}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 12,
              marginBottom: 12,
            }}
          >
            <span style={{ fontSize: '0.95rem', fontWeight: 600, color: '#0f172a' }}>
              字段语义（中文描述，供 agent 理解）
            </span>
            <button style={buttonStyle} onClick={handleSave} disabled={busy}>
              保存
            </button>
          </div>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th style={thStyle}>字段</th>
                <th style={thStyle}>类型</th>
                <th style={thStyle}>中文描述</th>
              </tr>
            </thead>
            <tbody>
              {(dataset?.columns ?? []).map(c => (
                <tr key={c.name}>
                  <td style={tdStyle}>
                    {c.name}
                    <div style={{ color: '#94a3b8', fontSize: '0.7rem' }}>{c.originalName}</div>
                  </td>
                  <td style={tdStyle}>{c.sqlType}</td>
                  <td style={{ ...tdStyle, maxWidth: 420 }}>
                    <input
                      style={inputStyle}
                      value={edits[c.name] ?? ''}
                      onChange={e => setEdits(prev => ({ ...prev, [c.name]: e.target.value }))}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
