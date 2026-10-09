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
  const [formOpen, setFormOpen] = useState(false);
  const [editGroup, setEditGroup] = useState<DatasetGroup | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [menuOpenId, setMenuOpenId] = useState<string | null>(null);
  const [dsOpen, setDsOpen] = useState(false);
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
    const close = (event: PointerEvent) => {
      if (!(event.target as HTMLElement).closest('.da-card-menu')) setMenuOpenId(null);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuOpenId(null);
    };
    document.addEventListener('pointerdown', close);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', close);
      document.removeEventListener('keydown', escape);
    };
  }, [menuOpenId]);

  function openCreate() {
    setEditGroup(null);
    setName('');
    setDescription('');
    setFormError(null);
    setFormOpen(true);
  }

  function openEdit(group: DatasetGroup, event: React.MouseEvent) {
    event.stopPropagation();
    setMenuOpenId(null);
    setEditGroup(group);
    setName(group.name);
    setDescription(group.description ?? '');
    setFormError(null);
    setFormOpen(true);
  }

  async function handleSave() {
    if (!NAME_RE.test(name.trim())) {
      setFormError('名称需为 1-100 个中文/字母/数字/-/_ 字符');
      return;
    }
    if (description.length > 1000) {
      setFormError('描述不能超过 1000 字');
      return;
    }
    setBusy(true);
    setFormError(null);
    try {
      const g = editGroup
        ? await updateGroup(editGroup.id, name.trim(), description.trim())
        : await createGroup(name.trim(), description.trim());
      setFormOpen(false);
      setName('');
      setDescription('');
      toast(editGroup ? '知识库已更新' : '知识库已创建', 'success');
      await refresh();
      if (!editGroup) navigate(`/configure/datasets/${g.id}`);
    } catch (e) {
      setFormError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(id: string, ev: React.MouseEvent) {
    ev.stopPropagation();
    setMenuOpenId(null);
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

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        minHeight: 0,
        background: 'var(--da-canvas-bg)',
      }}
    >
      <BackToChatHeader title="知识库" subtitle="组织数据集与关系文档，供问数时自动选用" />
      <div style={helpStyle}>
        知识库(KB)是数据集的容器：一个 KB 下可上传多张表与一份关系说明文档。问数时无需手动选择，
        agent 会通过 list_data_sources 看到你的全部 KB 内容并自行选用。
        {error && <span style={{ color: 'var(--da-danger)', marginLeft: 12 }}>{error}</span>}
      </div>
      <div style={{ padding: '16px 24px 0', display: 'flex', gap: 10 }}>
        <button className="da-btn da-btn-primary" onClick={openCreate} disabled={busy}>
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
              position: 'relative',
              zIndex: menuOpenId === g.id ? 2 : undefined,
              animationDelay: `${gi * 40}ms`,
            }}
            onClick={() => navigate(`/configure/datasets/${g.id}`)}
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
              <div className="da-card-menu" onClick={ev => ev.stopPropagation()}>
                <button
                  type="button"
                  className="da-btn da-btn-sm"
                  aria-label={`${g.name}的更多操作`}
                  aria-expanded={menuOpenId === g.id}
                  onClick={ev => { ev.stopPropagation(); setMenuOpenId(open => open === g.id ? null : g.id); }}
                >
                  <Icon name="moreHorizontal" size="sm" />
                </button>
                {menuOpenId === g.id && (
                  <div className="da-card-menu-popover">
                    <button type="button" onClick={ev => openEdit(g, ev)} disabled={busy}>
                      <Icon name="edit" size="sm" /> 修改名称和说明
                    </button>
                    <button type="button" className="danger" onClick={ev => handleDelete(g.id, ev)} disabled={busy}>
                      <Icon name="trash" size="sm" /> 删除知识库
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

      {formOpen && (
        <div className="da-modal-overlay" onClick={() => { if (!busy) setFormOpen(false); }}>
          <div className="da-modal-shell" style={{ width: 'min(520px, 92vw)' }} onClick={e => e.stopPropagation()}>
            <div className="da-modal-head">
              <div className="da-modal-title">{editGroup ? '编辑知识库' : '创建知识库'}</div>
            </div>
            <div className="da-modal-body">
              {formError && <div role="alert" style={{ color: 'var(--da-danger)', marginBottom: 12 }}>{formError}</div>}
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
              <button className="da-btn" onClick={() => setFormOpen(false)} disabled={busy}>
                取消
              </button>
              <button className="da-btn da-btn-primary" onClick={handleSave} disabled={busy}>
                {busy ? '保存中…' : editGroup ? '保存' : '创建'}
              </button>
            </div>
          </div>
        </div>
      )}
      {dsOpen && <DataSourceManagerModal open={dsOpen} onClose={() => setDsOpen(false)} />}
    </div>
  );
}
