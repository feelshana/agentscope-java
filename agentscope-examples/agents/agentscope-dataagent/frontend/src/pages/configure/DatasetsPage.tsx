import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import BackToChatHeader from '../../components/BackToChatHeader';
import DataSourceManagerModal from '../../components/DataSourceManagerModal';
import { createGroup, DatasetGroup, deleteGroup, listGroups } from '../../api/datasets';

const helpStyle: React.CSSProperties = {
  padding: '8px 24px',
  fontSize: '0.78rem',
  color: '#64748b',
  background: '#f8fafc',
  borderBottom: '1px solid #e2e8f0',
};

const gridStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflow: 'auto',
  padding: 24,
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
  gap: 16,
  alignContent: 'start',
};

const cardStyle: React.CSSProperties = {
  background: '#ffffff',
  border: '1px solid #e2e8f0',
  borderRadius: 12,
  padding: 16,
  cursor: 'pointer',
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
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

const dangerStyle: React.CSSProperties = {
  padding: '4px 10px',
  borderRadius: 6,
  border: '1px solid #dc2626',
  background: '#ffffff',
  color: '#dc2626',
  fontSize: '0.75rem',
  fontWeight: 600,
  cursor: 'pointer',
};

const modalOverlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(15,23,42,0.55)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 60,
};

const modalShellStyle: React.CSSProperties = {
  background: '#ffffff',
  borderRadius: 12,
  width: 'min(520px, 92vw)',
  padding: 20,
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 10px',
  borderRadius: 8,
  border: '1px solid #cbd5e1',
  fontSize: '0.85rem',
  color: '#0f172a',
  boxSizing: 'border-box',
};

const NAME_RE = /^[一-龥A-Za-z0-9_-]{1,100}$/;

export default function DatasetsPage() {
  const navigate = useNavigate();
  const [groups, setGroups] = useState<DatasetGroup[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [dsOpen, setDsOpen] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    try {
      setGroups(await listGroups());
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  async function handleCreate() {
    if (!NAME_RE.test(name.trim())) {
      setError('名称需为 1-100 个中文/字母/数字/-/_ 字符');
      return;
    }
    if (description.length > 1000) {
      setError('描述不能超过 1000 字');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const g = await createGroup(name.trim(), description.trim());
      setCreateOpen(false);
      setName('');
      setDescription('');
      await refresh();
      navigate(`/configure/datasets/${g.id}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(id: string, ev: React.MouseEvent) {
    ev.stopPropagation();
    if (!window.confirm('删除该知识库？其中的数据集表与关系文档将一并删除。')) return;
    setBusy(true);
    try {
      await deleteGroup(id);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <BackToChatHeader title="知识库" subtitle="组织数据集与关系文档，供问数时自动选用" />
      <div style={helpStyle}>
        知识库(KB)是数据集的容器：一个 KB 下可上传多张表与一份关系说明文档。问数时无需手动选择，
        agent 会通过 list_data_sources 看到你的全部 KB 内容并自行选用。
        {error && <span style={{ color: '#b91c1c', marginLeft: 12 }}>{error}</span>}
      </div>
      <div style={{ padding: '16px 24px 0', display: 'flex', gap: 10 }}>
        <button style={buttonStyle} onClick={() => setCreateOpen(true)} disabled={busy}>
          + 创建知识库
        </button>
        <button
          style={{
            padding: '8px 16px',
            borderRadius: 8,
            border: '1px solid #cbd5e1',
            background: '#ffffff',
            color: '#475569',
            fontSize: '0.85rem',
            fontWeight: 600,
            cursor: 'pointer',
          }}
          onClick={() => setDsOpen(true)}
        >
          数据源管理
        </button>
      </div>
      <div style={gridStyle}>
        {groups.length === 0 && (
          <div style={{ color: '#94a3b8', fontSize: '0.85rem' }}>
            还没有知识库，点击"创建知识库"开始。
          </div>
        )}
        {groups.map(g => (
          <div key={g.id} style={cardStyle} onClick={() => navigate(`/configure/datasets/${g.id}`)}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ fontSize: '0.95rem', fontWeight: 700, color: '#0f172a', flex: 1 }}>
                {g.name}
              </span>
              <button
                title="基于该知识库问答"
                style={{
                  background: '#fff',
                  border: '1px solid #e2e8f0',
                  borderRadius: 6,
                  padding: '2px 8px',
                  fontSize: '0.78rem',
                  cursor: 'pointer',
                }}
                onClick={ev => {
                  ev.stopPropagation();
                  navigate(`/chat?groups=${encodeURIComponent(g.id)}`);
                }}
              >
                💬 问答
              </button>
              <button style={dangerStyle} onClick={ev => handleDelete(g.id, ev)} disabled={busy}>
                删除
              </button>
            </div>
            <div style={{ fontSize: '0.78rem', color: '#64748b', minHeight: 32 }}>
              {g.description || '—'}
            </div>
            <div style={{ fontSize: '0.72rem', color: '#94a3b8' }}>
              {g.datasetCount} 个数据集 · 创建于{' '}
              {g.createdAt ? new Date(g.createdAt).toLocaleDateString() : '-'}
            </div>
          </div>
        ))}
      </div>

      {createOpen && (
        <div style={modalOverlayStyle} onClick={() => setCreateOpen(false)}>
          <div style={modalShellStyle} onClick={e => e.stopPropagation()}>
            <div style={{ fontSize: '1rem', fontWeight: 700, color: '#0f172a' }}>创建知识库</div>
            <div>
              <label style={{ fontSize: '0.78rem', fontWeight: 600, color: '#475569' }}>
                知识库名称 *
              </label>
              <input
                style={inputStyle}
                value={name}
                onChange={e => setName(e.target.value)}
                placeholder="请输入知识库名称"
              />
              <div style={{ fontSize: '0.72rem', color: '#94a3b8', marginTop: 4 }}>
                只能包含中文、字母、数字、连接线-或下划线_，长度 1-100 字符
              </div>
            </div>
            <div>
              <label style={{ fontSize: '0.78rem', fontWeight: 600, color: '#475569' }}>描述</label>
              <textarea
                style={{ ...inputStyle, minHeight: 90, resize: 'vertical' }}
                value={description}
                onChange={e => setDescription(e.target.value)}
                placeholder="请输入知识库描述"
              />
              <div style={{ fontSize: '0.72rem', color: '#94a3b8', marginTop: 4 }}>
                限制 1000 个字符
              </div>
            </div>
            <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
              <button style={ghostButtonStyle} onClick={() => setCreateOpen(false)}>
                取消
              </button>
              <button style={buttonStyle} onClick={handleCreate} disabled={busy}>
                创建
              </button>
            </div>
          </div>
        </div>
      )}
      {dsOpen && <DataSourceManagerModal open={dsOpen} onClose={() => setDsOpen(false)} />}
    </div>
  );
}
