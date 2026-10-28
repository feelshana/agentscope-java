import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { KnowledgeBreadcrumb, WorkspaceMenu } from '../../components/KnowledgeWorkspace';
import KnowledgeBaseMembersModal from '../../components/KnowledgeBaseMembersModal';
import EmptyIllustration from '../../components/EmptyIllustration';
import Icon from '../../components/Icon';
import { toast } from '../../components/Toast';
import { createGroup, DatasetGroup, deleteGroup, listGroupPage, updateGroup } from '../../api/datasets';

const NAME_RE = /^[一-龥A-Za-z0-9_-]{1,100}$/;

export default function DatasetsPage() {
  const navigate = useNavigate();
  const [groups, setGroups] = useState<DatasetGroup[]>([]);
  const [error, setError] = useState<string | null>(null);
  const creating = useRef(false);
  const [formOpen, setFormOpen] = useState(false);
  const [editGroup, setEditGroup] = useState<DatasetGroup | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState('date');
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);
  const [search, setSearch] = useState('');
  const [memberGroup, setMemberGroup] = useState<DatasetGroup | null>(null);
  const [viewMode, setViewMode] = useState<'grid' | 'rows'>('grid');

  const request = useRef<AbortController | null>(null);
  const refresh = useCallback(async () => {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setLoading(true);
    try {
      const result = await listGroupPage({ page, size: 20, search, sort }, controller.signal);
      if (controller.signal.aborted) return;
      setGroups(result.items);
      setTotal(result.total);
      if (!result.items.length && page > 0) setPage(Math.max(0, Math.ceil(result.total / 20) - 1));
      setError(null);
    } catch (e) {
      if (controller.signal.aborted) return;
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (request.current === controller) setLoading(false);
    }
  }, [page, search, sort]);

  useEffect(() => {
    const timer = window.setTimeout(() => setSearch(query), 250);
    return () => window.clearTimeout(timer);
  }, [query]);

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
    if (creating.current) return;
    if (!NAME_RE.test(name.trim())) {
      setFormError('名称需为 1-100 个中文/字母/数字/-/_ 字符');
      return;
    }
    if (description.length > 1000) {
      setFormError('描述不能超过 1000 字');
      return;
    }
    creating.current = true;
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
      creating.current = false;
      setBusy(false);
    }
  }

  async function handleDelete(id: string, ev?: React.MouseEvent) {
    ev?.stopPropagation();
    if (!window.confirm('删除该知识库？将停止相关执行，清理导入数据表、建模文件和库内配置。外部数据库原表、历史回答及附件保留。')) return;
    setBusy(true);
    try {
      await deleteGroup(id);
      toast('知识库已删除', 'success');
      await refresh();
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      await refresh();
      setError(message);
    } finally {
      setBusy(false);
    }
  }

  const filtered = groups;
  const pages = Math.max(1, Math.ceil(total / 20));
  const openCard = (group: DatasetGroup) => {
    if (group.deletionPending) return;
    navigate(group.canManage ? `/configure/datasets/${group.id}` : `/knowledge-bases/${group.id}`);
  };
  return (
    <div className="kw-page">
      <KnowledgeBreadcrumb items={[{ label: '返回对话', to: '/chat' }, { label: '知识库' }]} />
      <div className="kw-scroll"><div className="kw-content">
        <div className="kw-pagehead">
          <div className="kw-heading"><h1>知识库</h1><p>组织你的业务数据，让每一次分析都有清晰的依据。</p></div>
          <div className="kw-actions">
            <button className="da-btn" onClick={() => navigate('/configure/datasources')}><Icon name="database" size="sm" />数据源管理</button>
            <button className="da-btn da-btn-primary" onClick={openCreate} disabled={busy}><Icon name="plus" size="sm" />创建知识库</button>
          </div>
        </div>
        {error && <div className="kw-alert" role="alert">{error}<button className="da-btn da-btn-sm" onClick={refresh}>重试</button></div>}
        <div className="kw-toolbar">
          <label className="kw-search"><Icon name="search" size="sm" /><input aria-label="搜索知识库" placeholder="搜索知识库名称或说明" value={query} onChange={e => { setQuery(e.target.value); setPage(0); }} /></label>
          <span className="kw-count">{loading ? '加载中…' : `${total} 个知识库`}</span><span className="kw-spacer" />
          <select className="kw-select" aria-label="知识库排序" value={sort} onChange={e => { setSort(e.target.value); setPage(0); }}><option value="date">最近创建</option><option value="name">名称排序</option></select>
          <div className="kw-viewtoggle"><button aria-label="卡片视图" aria-pressed={viewMode === 'grid'} className={viewMode === 'grid' ? 'active' : ''} onClick={() => setViewMode('grid')}><Icon name="table" size="sm" /></button><button aria-label="列表视图" aria-pressed={viewMode === 'rows'} className={viewMode === 'rows' ? 'active' : ''} onClick={() => setViewMode('rows')}><Icon name="list" size="sm" /></button></div>
        </div>
        <div className={viewMode === 'grid' ? 'kw-grid' : 'kw-rows'}>
          {loading && [0, 1, 2].map(i => <div key={i} className="kw-card" style={{ padding: 24 }}><div className="da-skeleton da-skeleton-line" /><div className="da-skeleton da-skeleton-line" style={{ marginTop: 20 }} /></div>)}
          {!loading && filtered.map(g => <article key={g.id} className="kw-card" tabIndex={g.deletionPending ? -1 : 0} aria-label={`${g.canManage ? '查看' : '基于'}${g.name}${g.canManage ? '' : '提问'}`}
            onClick={() => openCard(g)}
            onKeyDown={e => { if (!g.deletionPending && e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); openCard(g); } }}>
            <div className="kw-cardtop"><div className="kw-icon"><Icon name="book" /></div><h2 title={g.name}>{g.name}</h2>
              <WorkspaceMenu label={`${g.name}的更多操作`} items={g.canManage ? [
                { label: '查看详情', icon: 'book', disabled: g.deletionPending, onClick: () => navigate(`/configure/datasets/${g.id}`) },
                { label: '编辑名称和说明', icon: 'edit', disabled: busy || g.deletionPending, onClick: () => openEdit(g) },
                { label: '成员与权限', icon: 'user', disabled: g.deletionPending, onClick: () => setMemberGroup(g) },
                { label: g.deletionPending ? '重试删除' : '删除知识库', icon: 'trash', danger: true, disabled: busy, onClick: () => void handleDelete(g.id) },
              ] : [{ label: '只读查看详情', icon: 'book', disabled: g.deletionPending, onClick: () => navigate(`/knowledge-bases/${g.id}`) }]} />
            </div>
            <p className="kw-description" title={g.description ?? ''} style={!g.description ? { color: 'var(--kw-muted)' } : undefined}>{g.description || '暂无说明'}</p>
            <div className="kw-cardcreator" title={`创建人：${g.ownerUsername || '已移除用户'}`}><span>创建人：{g.ownerUsername || '已移除用户'}</span><span className="kw-permission">{g.canManage ? '管理' : '使用'}</span></div>
            {g.deletionPending && <p className="da-small" role="status">删除待完成，后台正在重试清理。</p>}
            <div className="kw-cardmeta"><Icon name="table" size="sm" /><span>{g.datasetCount} 个数据集</span><span>·</span><span>{g.createdAt ? new Date(g.createdAt).toLocaleDateString('zh-CN') : '创建时间未知'}</span></div>
            <div className="kw-cardfoot">{g.deletionPending ? g.canManage && <button disabled={busy} onClick={e => handleDelete(g.id, e)}>重试删除</button> : <>{g.canManage ? <button onClick={e => { e.stopPropagation(); navigate(`/configure/datasets/${g.id}`); }}>查看详情 →</button> : <button onClick={e => { e.stopPropagation(); navigate(`/knowledge-bases/${g.id}`); }}>只读查看详情 →</button>}<button disabled={!g.canQuery} onClick={e => { e.stopPropagation(); navigate(`/chat?groups=${encodeURIComponent(g.id)}`); }}><Icon name="chat" size="sm" />基于此库提问</button></>}</div>
          </article>)}
        </div>
        {!loading && !error && filtered.length === 0 && <div className="kw-empty">{query ? '没有匹配的知识库，试试其他关键词。' : <EmptyIllustration variant="table" caption="还没有知识库，点击「创建知识库」开始" />}</div>}
        {!loading && total > 0 && <div className="kw-pagination"><span>共 {total} 个知识库 · 每页 20 个</span><span className="kw-spacer" /><button className="da-btn da-btn-sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}>上一页</button><span>{page + 1} / {pages}</span><button className="da-btn da-btn-sm" disabled={page + 1 >= pages} onClick={() => setPage(p => p + 1)}>下一页</button></div>}
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
                <label className="da-label" htmlFor="create-kb-name">知识库名称 *</label>
                <div>
                  <input
                    id="create-kb-name"
                    className="da-input"
                    value={name}
                    disabled={busy}
                    aria-invalid={!!formError}
                    aria-describedby={formError ? 'create-kb-error' : undefined}
                    onChange={e => { setName(e.target.value); setFormError(null); }}
                    placeholder="请输入知识库名称"
                  />
                  <div className="da-small" style={{ marginTop: 4 }}>
                    只能包含中文、字母、数字、连接线-或下划线_，长度 1-100 字符
                  </div>
                </div>
                <label className="da-label" htmlFor="create-kb-description">描述</label>
                <div>
                  <textarea
                    id="create-kb-description"
                    className="da-input"
                    disabled={busy}
                    style={{ minHeight: 90, resize: 'vertical' }}
                    value={description}
                    onChange={e => { setDescription(e.target.value); setFormError(null); }}
                    placeholder="请输入知识库描述"
                  />
                  <div className="da-small" style={{ marginTop: 4 }}>
                    限制 1000 个字符
                  </div>
                </div>
              </div>
              {formError && (
                <div id="create-kb-error" role="alert" style={{ color: 'var(--da-danger)', marginTop: 16 }}>
                  {formError}
                </div>
              )}
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
      {memberGroup && <KnowledgeBaseMembersModal groupId={memberGroup.id} name={memberGroup.name} onClose={() => setMemberGroup(null)} onChanged={() => void refresh()} />}
    </div>
  );
}
