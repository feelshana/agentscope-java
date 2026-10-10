import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { KnowledgeBreadcrumb, WorkspaceMenu } from '../../components/KnowledgeWorkspace';
import DataSourceManagerModal from '../../components/DataSourceManagerModal';
import EmptyIllustration from '../../components/EmptyIllustration';
import Icon from '../../components/Icon';
import { toast } from '../../components/Toast';
import { createGroup, DatasetGroup, deleteGroup, listGroups, updateGroup } from '../../api/datasets';

const NAME_RE = /^[一-龥A-Za-z0-9_-]{1,100}$/;

export default function DatasetsPage() {
  const navigate = useNavigate();
  const [groups, setGroups] = useState<DatasetGroup[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [editGroup, setEditGroup] = useState<DatasetGroup | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [dsOpen, setDsOpen] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState('date');
  const [viewMode, setViewMode] = useState<'grid' | 'rows'>('grid');

  const request = useRef<AbortController | null>(null);
  const refresh = useCallback(async () => {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setLoading(true);
    try {
      const result = await listGroups(controller.signal);
      if (controller.signal.aborted) return;
      setGroups(result);
      setError(null);
    } catch (e) {
      if (controller.signal.aborted) return;
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (request.current === controller) setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
    return () => request.current?.abort();
  }, [refresh]);

  function openCreate() {
    setEditGroup(null);
    setName('');
    setDescription('');
    setFormError(null);
    setFormOpen(true);
  }

  function openEdit(group: DatasetGroup, event?: React.MouseEvent) {
    event?.stopPropagation();
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

  async function handleDelete(id: string, ev?: React.MouseEvent) {
    ev?.stopPropagation();
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

  const filtered = groups.filter(g => `${g.name} ${g.description ?? ''}`.toLowerCase().includes(query.trim().toLowerCase()))
    .sort((a, b) => sort === 'name' ? a.name.localeCompare(b.name, 'zh-CN') : (Date.parse(b.createdAt ?? '') || 0) - (Date.parse(a.createdAt ?? '') || 0));
  return (
    <div className="kw-page">
      <KnowledgeBreadcrumb items={[{ label: '返回对话', to: '/chat' }, { label: '知识库' }]} />
      <div className="kw-scroll"><div className="kw-content">
        <div className="kw-pagehead">
          <div className="kw-heading"><h1>知识库</h1><p>组织你的业务数据，让每一次分析都有清晰的依据。</p></div>
          <div className="kw-actions">
            <button className="da-btn" onClick={() => setDsOpen(true)}><Icon name="database" size="sm" />数据源管理</button>
            <button className="da-btn da-btn-primary" onClick={openCreate} disabled={busy}><Icon name="plus" size="sm" />创建知识库</button>
          </div>
        </div>
        {error && <div className="kw-alert" role="alert">{error}<button className="da-btn da-btn-sm" onClick={refresh}>重试</button></div>}
        <div className="kw-toolbar">
          <label className="kw-search"><Icon name="search" size="sm" /><input aria-label="搜索知识库" placeholder="搜索知识库名称或说明" value={query} onChange={e => setQuery(e.target.value)} /></label>
          <span className="kw-count">{loading ? '加载中…' : `${filtered.length} 个知识库`}</span><span className="kw-spacer" />
          <select className="kw-select" aria-label="知识库排序" value={sort} onChange={e => setSort(e.target.value)}><option value="date">最近创建</option><option value="name">名称排序</option></select>
          <div className="kw-viewtoggle"><button aria-label="卡片视图" aria-pressed={viewMode === 'grid'} className={viewMode === 'grid' ? 'active' : ''} onClick={() => setViewMode('grid')}><Icon name="table" size="sm" /></button><button aria-label="列表视图" aria-pressed={viewMode === 'rows'} className={viewMode === 'rows' ? 'active' : ''} onClick={() => setViewMode('rows')}><Icon name="list" size="sm" /></button></div>
        </div>
        <div className={viewMode === 'grid' ? 'kw-grid' : 'kw-rows'}>
          {loading && [0, 1, 2].map(i => <div key={i} className="kw-card" style={{ padding: 24 }}><div className="da-skeleton da-skeleton-line" /><div className="da-skeleton da-skeleton-line" style={{ marginTop: 20 }} /></div>)}
          {!loading && !error && filtered.map(g => <article key={g.id} className="kw-card" tabIndex={0} aria-label={`查看${g.name}`}
            onClick={() => navigate(`/configure/datasets/${g.id}`)}
            onKeyDown={e => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); navigate(`/configure/datasets/${g.id}`); } }}>
            <div className="kw-cardtop"><div className="kw-icon"><Icon name="book" /></div><h2 title={g.name}>{g.name}</h2>
              <WorkspaceMenu label={`${g.name}的更多操作`} items={[
                { label: '查看详情', icon: 'book', onClick: () => navigate(`/configure/datasets/${g.id}`) },
                { label: '编辑名称和说明', icon: 'edit', disabled: busy, onClick: () => openEdit(g) },
                { label: '删除知识库', icon: 'trash', danger: true, disabled: busy, onClick: () => void handleDelete(g.id) },
              ]} />
            </div>
            <p className="kw-description" title={g.description ?? ''} style={!g.description ? { color: 'var(--kw-muted)' } : undefined}>{g.description || '暂无说明'}</p>
            <div className="kw-cardcreator" title={`创建人：${g.ownerUsername || '未知用户'}`}>创建人：{g.ownerUsername || '未知用户'}</div>
            <div className="kw-cardmeta"><Icon name="table" size="sm" /><span>{g.datasetCount} 个数据集</span><span>·</span><span>{g.createdAt ? new Date(g.createdAt).toLocaleDateString('zh-CN') : '创建时间未知'}</span></div>
            <div className="kw-cardfoot"><button onClick={e => { e.stopPropagation(); navigate(`/configure/datasets/${g.id}`); }}>查看详情 →</button><button onClick={e => { e.stopPropagation(); navigate(`/chat?groups=${encodeURIComponent(g.id)}`); }}><Icon name="chat" size="sm" />基于此库提问</button></div>
          </article>)}
        </div>
        {!loading && !error && filtered.length === 0 && <div className="kw-empty">{groups.length ? '没有匹配的知识库，试试其他关键词。' : <EmptyIllustration variant="table" caption="还没有知识库，点击「创建知识库」开始" />}</div>}
      </div></div>

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
