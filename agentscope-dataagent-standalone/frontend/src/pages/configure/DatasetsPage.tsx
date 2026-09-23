import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import BackToChatHeader from '../../components/BackToChatHeader';
import DataSourceManagerModal from '../../components/DataSourceManagerModal';
import EmptyIllustration from '../../components/EmptyIllustration';
import Icon from '../../components/Icon';
import { toast } from '../../components/Toast';
import { createGroup, DatasetGroup, deleteGroup, listGroups, updateGroup } from '../../api/datasets';

const helpStyle: React.CSSProperties = {
  padding: '8px 24px',
  fontSize: '0.78rem',
  color: 'var(--da-text-3)',
  background: 'var(--da-surface-sunken)',
  borderBottom: '1px solid var(--da-border)',
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

const NAME_RE = /^[一-龥A-Za-z0-9_-]{1,100}$/;

export default function DatasetsPage() {
  const navigate = useNavigate();
  const [groups, setGroups] = useState<DatasetGroup[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [dsOpen, setDsOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [editGroup, setEditGroup] = useState<DatasetGroup | null>(null);
  const [editName, setEditName] = useState('');
  const [editDescription, setEditDescription] = useState('');
  const [menuOpenId, setMenuOpenId] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setGroups(await listGroups());
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    if (!menuOpenId) return;
    const closeMenu = (event: PointerEvent) => {
      const target = event.target as HTMLElement;
      if (!target.closest('.da-card-menu')) {
        setMenuOpenId(null);
      }
    };
    document.addEventListener('pointerdown', closeMenu);
    return () => document.removeEventListener('pointerdown', closeMenu);
  }, [menuOpenId]);

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
      toast('知识库已创建', 'success');
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
      toast('知识库已删除', 'success');
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  function openEditMenu(g: DatasetGroup, ev: React.MouseEvent) {
    ev.stopPropagation();
    setMenuOpenId(menuOpenId === g.id ? null : g.id);
  }

  function handleEdit(g: DatasetGroup, ev: React.MouseEvent) {
    ev.stopPropagation();
    setEditGroup(g);
    setEditName(g.name);
    setEditDescription(g.description || '');
    setEditOpen(true);
    setMenuOpenId(null);
  }

  async function handleUpdate() {
    if (!editGroup) return;
    if (!NAME_RE.test(editName.trim())) {
      setError('名称需为 1-100 个中文/字母/数字/-/_ 字符');
      return;
    }
    if (editDescription.length > 1000) {
      setError('描述不能超过 1000 字');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await updateGroup(editGroup.id, editName.trim(), editDescription.trim());
      setEditOpen(false);
      toast('知识库已更新', 'success');
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
        {error && <span style={{ color: 'var(--da-danger)', marginLeft: 12 }}>{error}</span>}
      </div>
      <div style={{ padding: '16px 24px 0', display: 'flex', gap: 10 }}>
        <button className="da-btn da-btn-primary" onClick={() => setCreateOpen(true)} disabled={busy}>
          + 创建知识库
        </button>
        <button className="da-btn" onClick={() => setDsOpen(true)}>
          数据源管理
        </button>
      </div>
      <div style={gridStyle}>
        {loading &&
          [0, 1, 2, 3].map(i => (
            <div key={i} className="da-card" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <div className="da-skeleton da-skeleton-line" style={{ width: '60%' }} />
              <div className="da-skeleton da-skeleton-line" style={{ width: '90%' }} />
              <div className="da-skeleton da-skeleton-line" style={{ width: '40%' }} />
            </div>
          ))}
        {!loading && groups.length === 0 && (
          <EmptyIllustration variant="table" caption="还没有知识库，点击「创建知识库」开始" />
        )}
        {groups.map((g, gi) => (
          <div
            key={g.id}
            className="da-card da-card-hover da-enter"
            style={{
              cursor: 'pointer',
              display: 'flex',
              flexDirection: 'column',
              gap: 8,
              animationDelay: `${gi * 40}ms`,
              transition: 'transform 0.2s ease, box-shadow 0.2s ease',
            }}
            onClick={() => navigate(`/configure/datasets/${g.id}`)}
            onMouseEnter={e => {
              const el = e.currentTarget;
              el.style.transform = 'translateY(-4px)';
              el.style.boxShadow = '0 12px 24px rgba(0,0,0,0.12), 0 4px 8px rgba(0,0,0,0.08)';
            }}
            onMouseLeave={e => {
              const el = e.currentTarget;
              el.style.transform = 'translateY(0)';
              el.style.boxShadow = '';
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ fontSize: '0.95rem', fontWeight: 700, color: 'var(--da-text)', flex: 1 }}>
                {g.name}
              </span>
              <button
                title="基于该知识库问答"
                className="da-btn da-btn-sm"
                onClick={ev => {
                  ev.stopPropagation();
                  navigate(`/chat?groups=${encodeURIComponent(g.id)}`);
                }}
              >
                <Icon name="chat" size="sm" /> 问答
              </button>
              <div className="da-card-menu" style={{ position: 'relative' }}>
                <button
                  className="da-btn da-btn-sm"
                  style={{ padding: '4px 8px', minWidth: 'auto' }}
                  onClick={ev => openEditMenu(g, ev)}
                  title="更多操作"
                >
                  <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor">
                    <circle cx="3" cy="8" r="1.5" />
                    <circle cx="8" cy="8" r="1.5" />
                    <circle cx="13" cy="8" r="1.5" />
                  </svg>
                </button>
                {menuOpenId === g.id && (
                  <div
                    style={{
                      position: 'absolute',
                      right: 0,
                      top: '100%',
                      marginTop: 4,
                      background: 'var(--da-surface)',
                      border: '1px solid var(--da-border)',
                      borderRadius: 8,
                      boxShadow: '0 8px 24px rgba(0,0,0,0.12)',
                      minWidth: 140,
                      zIndex: 10,
                      overflow: 'hidden',
                    }}
                    onClick={ev => ev.stopPropagation()}
                  >
                    <button
                      className="da-menu-item"
                      style={{
                        display: 'block',
                        width: '100%',
                        padding: '10px 14px',
                        textAlign: 'left',
                        background: 'none',
                        border: 'none',
                        cursor: 'pointer',
                        fontSize: '0.88rem',
                        color: 'var(--da-text)',
                      }}
                      onMouseEnter={e => (e.currentTarget.style.background = 'var(--da-surface-sunken)')}
                      onMouseLeave={e => (e.currentTarget.style.background = 'none')}
                      onClick={ev => handleEdit(g, ev)}
                    >
                      修改名称/描述
                    </button>
                    <button
                      className="da-menu-item"
                      style={{
                        display: 'block',
                        width: '100%',
                        padding: '10px 14px',
                        textAlign: 'left',
                        background: 'none',
                        border: 'none',
                        cursor: 'pointer',
                        fontSize: '0.88rem',
                        color: 'var(--da-danger)',
                        borderTop: '1px solid var(--da-border)',
                      }}
                      onMouseEnter={e => (e.currentTarget.style.background = 'var(--da-surface-sunken)')}
                      onMouseLeave={e => (e.currentTarget.style.background = 'none')}
                      onClick={ev => handleDelete(g.id, ev)}
                      disabled={busy}
                    >
                      删除
                    </button>
                  </div>
                )}
              </div>
            </div>
            <div style={{ fontSize: '0.78rem', color: 'var(--da-text-3)', minHeight: 32 }}>
              {g.description || '—'}
            </div>
            <div style={{ fontSize: '0.72rem', color: 'var(--da-text-muted)' }}>
              {g.datasetCount} 个数据集 · 创建于{' '}
              {g.createdAt ? new Date(g.createdAt).toLocaleDateString() : '-'}
            </div>
          </div>
        ))}
      </div>

      {createOpen && (
        <div className="da-modal-overlay" onClick={() => setCreateOpen(false)}>
          <div className="da-modal-shell" style={{ width: 'min(520px, 92vw)' }} onClick={e => e.stopPropagation()}>
            <div className="da-modal-head">
              <div className="da-modal-title">创建知识库</div>
            </div>
            <div className="da-modal-body">
              <div className="da-form-grid">
                <label className="da-label">知识库名称 *</label>
                <div>
                  <input
                    className="da-input"
                    value={name}
                    onChange={e => setName(e.target.value)}
                    placeholder="请输入知识库名称"
                  />
                  <div className="da-small" style={{ marginTop: 4 }}>
                    只能包含中文、字母、数字、连接线-或下划线_，长度 1-100 字符
                  </div>
                </div>
                <label className="da-label">描述</label>
                <div>
                  <textarea
                    className="da-input"
                    style={{ minHeight: 90, resize: 'vertical' }}
                    value={description}
                    onChange={e => setDescription(e.target.value)}
                    placeholder="请输入知识库描述"
                  />
                  <div className="da-small" style={{ marginTop: 4 }}>
                    限制 1000 个字符
                  </div>
                </div>
              </div>
            </div>
            <div className="da-modal-foot">
              <button className="da-btn" onClick={() => setCreateOpen(false)}>
                取消
              </button>
              <button className="da-btn da-btn-primary" onClick={handleCreate} disabled={busy}>
                创建
              </button>
            </div>
          </div>
        </div>
      )}
      {editOpen && editGroup && (
        <div className="da-modal-overlay" onClick={() => setEditOpen(false)}>
          <div className="da-modal-shell" style={{ width: 'min(520px, 92vw)' }} onClick={e => e.stopPropagation()}>
            <div className="da-modal-head">
              <div className="da-modal-title">修改知识库</div>
            </div>
            <div className="da-modal-body">
              <div className="da-form-grid">
                <label className="da-label">知识库名称 *</label>
                <div>
                  <input
                    className="da-input"
                    value={editName}
                    onChange={e => setEditName(e.target.value)}
                    placeholder="请输入知识库名称"
                  />
                  <div className="da-small" style={{ marginTop: 4 }}>
                    只能包含中文、字母、数字、连接线 - 或下划线_，长度 1-100 字符
                  </div>
                </div>
                <label className="da-label">描述</label>
                <div>
                  <textarea
                    className="da-input"
                    style={{ minHeight: 90, resize: 'vertical' }}
                    value={editDescription}
                    onChange={e => setEditDescription(e.target.value)}
                    placeholder="请输入知识库描述"
                  />
                  <div className="da-small" style={{ marginTop: 4 }}>
                    限制 1000 个字符
                  </div>
                </div>
              </div>
            </div>
            <div className="da-modal-foot">
              <button className="da-btn" onClick={() => setEditOpen(false)}>
                取消
              </button>
              <button className="da-btn da-btn-primary" onClick={handleUpdate} disabled={busy}>
                保存
              </button>
            </div>
          </div>
        </div>
      )}
      {dsOpen && <DataSourceManagerModal open={dsOpen} onClose={() => setDsOpen(false)} />}
    </div>
  );
}
