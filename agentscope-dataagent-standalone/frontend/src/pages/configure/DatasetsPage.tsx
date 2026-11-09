import { tourActive, tourSignal } from '../../state/guidedTour';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { KnowledgeBreadcrumb, WorkspaceMenu } from '../../components/KnowledgeWorkspace';
import KnowledgeBaseMembersModal from '../../components/KnowledgeBaseMembersModal';
import EmptyIllustration from '../../components/EmptyIllustration';
import Icon from '../../components/Icon';
import { toast } from '../../components/Toast';
import { createGroup, DatasetGroup, deleteGroup, listGroups, updateGroup } from '../../api/datasets';
import './DatasetsPage.css';

const NAME_RE = /^[一-龥A-Za-z0-9_-]{1,100}$/;

const TOUR_GROUP_NAME = '我的第一个知识库';
const PAGE_SIZE = 20;
const PERMISSIONS = [
  ['all', '全部'],
  ['manage', '可管理'],
  ['use', '仅可使用'],
] as const;
type Permission = (typeof PERMISSIONS)[number][0];
type GroupStatus = 'published' | 'model' | 'data';
const STATUS_LABELS: Record<GroupStatus, string> = {
  published: '已发布',
  model: '待建模',
  data: '待添加数据',
};
const shortDate = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric' });

function groupStatus(group: DatasetGroup): GroupStatus {
  if (group.publishedVersion) return 'published';
  return group.datasetCount > 0 ? 'model' : 'data';
}

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
  const [permission, setPermission] = useState<Permission>('all');
  const [status, setStatus] = useState('all');
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
      const result = await listGroups(controller.signal, false, { search, sort });
      if (controller.signal.aborted) return;
      setGroups(result);
      setError(null);
    } catch (e) {
      if (controller.signal.aborted) return;
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (request.current === controller) setLoading(false);
    }
  }, [search, sort]);

  useEffect(() => {
    const timer = window.setTimeout(() => setSearch(query), 250);
    return () => window.clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    refresh();
    return () => request.current?.abort();
  }, [refresh]);

  // spec104: the new-user checklist links here to create a knowledge base or a data source.
  const [searchParams, setSearchParams] = useSearchParams();
  useEffect(() => {
    const create = searchParams.get('create') === '1';
    const datasource = searchParams.get('datasource') === '1';
    if (!create && !datasource) return;
    if (create) { openCreate(); if (tourActive()) setName(TOUR_GROUP_NAME); }
    if (datasource) { navigate('/configure/datasources?create=1', { replace: true }); return; }
    setSearchParams({}, { replace: true });
  }, [searchParams, setSearchParams, navigate]);

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
      if (!editGroup) { tourSignal('kb-created', { groupId: g.id }); navigate(`/configure/datasets/${g.id}`); }
    } catch (e) {
      const text = e instanceof Error ? e.message : String(e);
      // The tour's suggested name may be taken; a new one lets the user just click again.
      if (tourActive() && text.includes('already exists')) {
        setName(`${TOUR_GROUP_NAME}-${Math.floor(Math.random() * 900) + 100}`);
        setFormError('这个名称已经用过，已换一个新名称，再点一次“创建”。');
      } else setFormError(text);
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

  const matching = status === 'all' ? groups : groups.filter(group => groupStatus(group) === status);
  const manageable = matching.filter(group => group.canManage).length;
  const permissionCounts = { all: matching.length, manage: manageable, use: matching.length - manageable };
  const filtered = permission === 'all' ? matching : matching.filter(group => group.canManage === (permission === 'manage'));
  const total = filtered.length;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const currentPage = Math.min(page, pages - 1);
  const visible = filtered.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE);
  const hasFilters = Boolean(query.trim()) || permission !== 'all' || status !== 'all';
  const showCreateCard = viewMode === 'grid' && !loading && !error && !hasFilters && currentPage === pages - 1;
  const clearFilters = () => {
    setQuery('');
    setSearch('');
    setPermission('all');
    setStatus('all');
    setPage(0);
  };
  const openCard = (group: DatasetGroup) => {
    if (group.deletionPending) return;
    navigate(`/configure/datasets/${group.id}`);
  };
  return (
    <div className="kw-page workspace-overview kb-library">
      <KnowledgeBreadcrumb items={[{ label: '返回对话', to: '/chat' }, { label: '知识库' }]} />
      <div className="kw-scroll"><div className="kw-content">
        <div className="kw-pagehead">
          <div className="kw-heading"><h1>知识库</h1><p>管理业务数据，选择知识库开始问数。</p></div>
          <div className="kw-actions">
            <button className="da-btn" onClick={() => navigate('/configure/datasources')}><Icon name="database" size="sm" />数据源管理</button>
            <button className="da-btn da-btn-primary" onClick={openCreate} disabled={busy}><Icon name="plus" size="sm" />创建知识库</button>
          </div>
        </div>
        {error && <div className="kw-alert" role="alert">{error}<button className="da-btn da-btn-sm" onClick={refresh}>重试</button></div>}
        <div className="kw-toolbar">
          <label className="kw-search">
            <Icon name="search" size="sm" />
            <input type="search" aria-label="搜索知识库" placeholder="搜索知识库名称或说明…" maxLength={100}
              value={query} onChange={e => { setQuery(e.target.value); setPage(0); }} />
          </label>
          <div className="kb-permission-filter" role="group" aria-label="按知识库权限筛选">
            {PERMISSIONS.map(([value, label]) => (
              <button key={value} type="button" aria-pressed={permission === value}
                onClick={() => { setPermission(value); setPage(0); }}>
                {label}{!loading && !error && <b>{permissionCounts[value]}</b>}
              </button>
            ))}
          </div>
          <div className="kb-toolbar-end">
            <label className="kb-select">
              <select className="kw-select" aria-label="按知识库状态筛选" value={status}
                onChange={e => { setStatus(e.target.value); setPage(0); }}>
                <option value="all">状态 · 全部</option>
                {Object.entries(STATUS_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
              </select>
              <Icon name="chevron" size="sm" />
            </label>
            <label className="kb-select">
              <select className="kw-select" aria-label="知识库排序" value={sort}
                onChange={e => { setSort(e.target.value); setPage(0); }}>
                <option value="date">最近创建</option><option value="name">名称排序</option>
              </select>
              <Icon name="chevron" size="sm" />
            </label>
            <div className="kw-viewtoggle" role="group" aria-label="知识库视图">
              <button type="button" aria-label="卡片视图" aria-pressed={viewMode === 'grid'} onClick={() => setViewMode('grid')}><Icon name="table" size="sm" /></button>
              <button type="button" aria-label="列表视图" aria-pressed={viewMode === 'rows'} onClick={() => setViewMode('rows')}><Icon name="list" size="sm" /></button>
            </div>
          </div>
        </div>
        <p className="kb-result-count" role="status">{loading ? '加载中…' : error ? null : <>共 <strong>{total}</strong> 个知识库</>}</p>
        <div className={viewMode === 'grid' ? 'kw-grid kb-grid' : 'kw-rows kb-list'}
          hidden={!loading && !total && !showCreateCard}>
          {loading && [0, 1, 2, 3].map(i => <div key={i} className="kw-card kb-card kb-skeleton" aria-hidden="true"><div className="da-skeleton da-skeleton-line" /><div className="da-skeleton da-skeleton-line" style={{ marginTop: 20 }} /></div>)}
          {!loading && visible.map(g => {
            const state = groupStatus(g);
            const preparing = !g.publishedVersion && g.canManage;
            const detailPath = `/configure/datasets/${g.id}`;
            const action = preparing
              ? state === 'data'
                ? { label: '添加数据', icon: 'database', path: detailPath } as const
                : { label: '进入建模', icon: 'model', path: `/configure/modeling/${g.id}` } as const
              : { label: '开始提问', icon: 'chat', path: `/chat?groups=${encodeURIComponent(g.id)}` } as const;
            const owner = g.ownerUsername || '已移除用户';
            const created = g.createdAt ? new Date(g.createdAt) : null;
            const createdTitle = created ? `创建于 ${created.toLocaleDateString('zh-CN')}` : '创建时间未知';
            return <article key={g.id} className={`kw-card kb-card${g.deletionPending ? ' kb-deleting' : ''}`} tabIndex={g.deletionPending ? -1 : 0} aria-label={`查看${g.name}`}
            onClick={() => openCard(g)}
            onKeyDown={e => { if (!g.deletionPending && e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); openCard(g); } }}>
            <div className="kb-card-header">
              <div className="overview-icon kb-symbol"><Icon name="book" /></div>
              <div className="kb-card-heading">
                <h2 className="kb-card-title" title={g.name}>{g.name}</h2>
                <div className="kb-card-state">
                  <span className={`overview-status${state === 'published' ? ' is-published' : ''}`}>
                    {STATUS_LABELS[state]}{state === 'published' ? ` v${g.publishedVersion}` : ''}
                  </span>
                  <span className="kb-permission">{g.canManage ? '可管理' : '仅可使用'}</span>
                </div>
              </div>
              <div className="kb-card-controls">
                <WorkspaceMenu label={`${g.name}的更多操作`} items={g.canManage ? [
                { label: '查看详情', icon: 'book', disabled: g.deletionPending, onClick: () => navigate(detailPath) },
                { label: '编辑名称和说明', icon: 'edit', disabled: busy || g.deletionPending, onClick: () => openEdit(g) },
                { label: '成员与权限', icon: 'user', disabled: g.deletionPending, onClick: () => setMemberGroup(g) },
                { label: g.deletionPending ? '重试删除' : '删除知识库', icon: 'trash', danger: true, disabled: busy, onClick: () => void handleDelete(g.id) },
                ] : [{ label: '查看详情', icon: 'book', disabled: g.deletionPending, onClick: () => navigate(detailPath) }]} />
              </div>
            </div>
            <div className="kb-card-description">
              {g.description?.trim() ? <p title={g.description}>{g.description}</p> : <p className="kb-no-description">暂无说明</p>}
            </div>
            <div className="kb-card-info">
              <span>{g.datasetCount} 个数据集</span>
              <span className="kb-meta-part"><i aria-hidden="true">·</i><span className="kb-card-owner" title={owner}>{owner}</span></span>
              <span className="kb-meta-part" title={createdTitle}>
                <i aria-hidden="true">·</i>
                {created && g.createdAt ? <time dateTime={g.createdAt}>{shortDate.format(created)}</time> : createdTitle}
              </span>
            </div>
            {g.deletionPending && <p className="kb-delete-status" role="status">删除待完成，后台正在重试清理。</p>}
            <div className="kb-card-footer">
              {g.deletionPending ? g.canManage && (
                <button type="button" className="kb-view-data" disabled={busy} onClick={e => handleDelete(g.id, e)}>重试删除</button>
              ) : <>
                <button type="button" className="kb-view-data" onClick={e => { e.stopPropagation(); openCard(g); }}>
                  查看数据<Icon name="chevron" size="sm" />
                </button>
                <button type="button" className="kb-ask"
                  aria-label={preparing ? `${action.label}：${g.name}` : `基于${g.name}提问`}
                  disabled={!preparing && !g.canQuery}
                  title={!preparing && !g.canQuery ? '当前知识库暂不可提问' : undefined}
                  onClick={e => { e.stopPropagation(); navigate(action.path); }}>
                  <Icon name={action.icon} size="sm" />{action.label}
                </button>
              </>}
            </div>
          </article>;
          })}
          {showCreateCard && <button type="button" className="kw-card kb-card kb-create-card"
            aria-labelledby="kb-create-card-label" aria-describedby="kb-create-card-hint" onClick={openCreate} disabled={busy}>
            <span className="kb-create-card-icon"><Icon name="plus" /></span>
            <strong id="kb-create-card-label">创建知识库</strong>
            <small id="kb-create-card-hint">上传 Excel / CSV，或连接数据库</small>
          </button>}
        </div>
        {!loading && !error && !total && !showCreateCard && (
          <div className="kw-empty kb-empty">
            {hasFilters ? <>
              <strong>没有匹配的知识库</strong>
              <p>换个关键词，或清除筛选条件。</p>
              <button type="button" className="da-btn" onClick={clearFilters}>清除筛选</button>
            </> : <EmptyIllustration variant="table" caption="还没有知识库，点击「创建知识库」开始" />}
          </div>
        )}
        {!loading && pages > 1 && <div className="kw-pagination"><span>每页 {PAGE_SIZE} 个</span><span className="kw-spacer" />
          <button type="button" className="da-btn da-btn-sm" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>上一页</button>
          <span>{currentPage + 1} / {pages}</span>
          <button type="button" className="da-btn da-btn-sm" disabled={currentPage + 1 >= pages} onClick={() => setPage(currentPage + 1)}>下一页</button>
        </div>}
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
              <button className="da-btn da-btn-primary" data-tour="kb-create" onClick={handleSave} disabled={busy}>
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
