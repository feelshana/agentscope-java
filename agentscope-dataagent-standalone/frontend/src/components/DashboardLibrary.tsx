import React, { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { createDashboard, Dashboard, listDashboards } from '../api/dashboards';
import { getToken } from '../api/auth';
import DashboardMetadataDialog from './DashboardMetadataDialog';
import DashboardKnowledgePicker from './DashboardKnowledgePicker';
import DashboardKnowledgeDrawer from './DashboardKnowledgeDrawer';
import { dashboardNameFromKnowledgeBase, dashboardStatuses, dashboardViewKey, readDashboardView, saveDashboardView, selectDashboards, DashboardSort, DashboardView } from './dashboardLibraryState';
import { DatasetGroup, listGroups } from '../api/datasets';
import DataSourceDialog from './DataSourceDialog';
import { KnowledgeBreadcrumb, WorkspaceMenu } from './KnowledgeWorkspace';
import DashboardPermissions from './DashboardPermissions';
import DashboardDeleteDialog from './DashboardDeleteDialog';
import { dashboardErrorText, dashboardStatus } from './dashboardState';
import { dashboardThumbnail } from './dashboardThumbnails';
import Icon from './Icon';

const filters = [['all', '全部'], ['mine', '我创建的'], ['manage', '可管理'], ['use', '可使用']] as const;
function updated(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '更新时间暂不可用' : `更新于 ${date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}`;
}

function DashboardCard({ item, group, onPermissions, onDelete, onEdit, onKnowledge }: { item: Dashboard; group?: DatasetGroup; onPermissions: () => void; onDelete: () => void; onEdit: () => void; onKnowledge: () => void }) {
  const status = dashboardStatus(item);
  const publishedImage = dashboardThumbnail(item.id, item.publishedRevision);
  const image = !item.deletionPending && (publishedImage || (item.canEdit ? dashboardThumbnail(item.id, item.revision) : undefined));
  const path = `/dashboards/${item.id}`;
  const viewing = item.publishedRevision ? `${path}?view=published` : path;
  return <article className="dashboard-library-item">
    <Link className="dashboard-cover" to={viewing} aria-disabled={item.deletionPending || undefined} onClick={event => { if (item.deletionPending) event.preventDefault(); }} aria-label={`${item.publishedRevision ? '查看' : '预览草稿'}：${item.title}`}>
      {image ? <><img src={image} alt={`${item.title}的图表预览`} /><span className="dashboard-cover-caption">{publishedImage ? '最近查看的图表' : '最近查看的草稿图表'}</span></>
        : <div className="dashboard-cover-empty"><span className="dashboard-feature-icon"><Icon name="chart" /></span><strong>{item.publishedRevision ? '业务全景，一目了然' : '从一个想法开始'}</strong><span>{item.publishedRevision ? '打开看板，查看完整图表' : '继续对话，完善第一版看板'}</span></div>}
    </Link>
    <div className="dashboard-card-body"><div className="dashboard-card-heading"><h3 title={item.title}>{item.title}</h3><WorkspaceMenu label={`${item.title}的操作`} items={[...(item.canEdit && !item.deletionPending ? [{ label: '编辑基本信息', icon: 'edit' as const, onClick: onEdit }] : []), { label: '访问权限', icon: 'shield', onClick: onPermissions }, ...(item.canEdit ? [{ label: item.deletionPending ? '重试清理' : '删除看板', icon: 'trash' as const, danger: true, onClick: onDelete }] : [])]} /></div><div className="dashboard-card-badges"><span className={`dashboard-badge ${status.kind}`}>{status.label}</span><span className="dashboard-access-badge">{item.canPublish ? '可管理' : '可使用'}</span></div>
      {item.description?.trim() && <p className="dashboard-card-description" title={item.description}>{item.description}</p>}
      <div className="dashboard-card-meta"><div className="dashboard-card-provenance"><button type="button" className="dashboard-card-source" disabled={!group?.canQuery || group.deletionPending} title={group?.name ? `查看知识库资料：${group.name}` : '知识库暂不可用'} aria-label={`查看知识库资料：${group?.name || '知识库暂不可用'}`} onClick={onKnowledge}><Icon name="book" size="sm" /><span className="dashboard-card-source-name">{group?.name || '知识库暂不可用'}</span><Icon name="chevron" size="sm" /></button><span className="dashboard-card-creator" title={`创建人：${item.creatorName || '暂不可用'}`}><Icon name="user" size="sm" /><span>{item.creatorName || '创建人暂不可用'}</span></span></div><div className="dashboard-card-dates"><span className="dashboard-card-updated" title={item.updatedAt}>{updated(item.updatedAt)}</span><span className="dashboard-card-created">{item.createdAt && Number.isFinite(Date.parse(item.createdAt)) ? `创建于 ${new Date(item.createdAt).toLocaleDateString('zh-CN')}` : '创建时间未记录'}</span></div></div>
    </div>
    <footer className="dashboard-card-bottom">{item.deletionPending ? <button onClick={onDelete}>重试清理</button> : <><Link to={viewing}><Icon name="chart" size="sm" />{item.publishedRevision ? '查看看板' : '预览草稿'}</Link>{item.canEdit && <Link to={path}><Icon name="edit" size="sm" />继续编辑</Link>}</>}</footer>
  </article>;
}

export default function DashboardLibrary() {
  const navigate = useNavigate();
  const [items, setItems] = useState<Dashboard[]>([]);
  const [groups, setGroups] = useState<DatasetGroup[]>([]);
  const [group, setGroup] = useState('');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [fillNotice, setFillNotice] = useState('');
  const [permissions, setPermissions] = useState<Dashboard | null>(null);
  const [removing, setRemoving] = useState<Dashboard | null>(null);
  const [filter, setFilter] = useState<(typeof filters)[number][0]>('all');
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('all');
  const [sort, setSort] = useState<DashboardSort>('updated');
  const [viewKey] = useState(() => dashboardViewKey(getToken()));
  const [view, setView] = useState<DashboardView>(() => readDashboardView(dashboardViewKey(getToken())));
  const [editing, setEditing] = useState<Dashboard | null>(null);
  const [knowledgeId, setKnowledgeId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [formOpen, setFormOpen] = useState(false);
  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  useEffect(() => {
    let disposed = false;
    Promise.all([listDashboards(), listGroups()]).then(([dashboards, knowledge]) => {
      if (disposed) return;
      setItems(dashboards); setGroups(knowledge);
      setGroup(knowledge.find(g => g.canQuery && (g.publishedVersion ?? 0) > 0 && !g.deletionPending)?.id || '');
    }).catch(e => { if (!disposed) setError(dashboardErrorText(e)); }).finally(() => { if (!disposed) setLoading(false); });
    return () => { disposed = true; };
  }, []);
  const sources = groups.filter(g => g.canQuery && (g.publishedVersion ?? 0) > 0 && !g.deletionPending);
  const groupMap = useMemo(() => new Map(groups.map(g => [g.id, g])), [groups]);
  const visible = useMemo(() => selectDashboards(items, groupMap, filter, query, status, sort), [items, groupMap, filter, query, status, sort]);
  const filtered = filter !== 'all' || Boolean(query.trim()) || status !== 'all';
  const clearFilters = () => { setFilter('all'); setQuery(''); setStatus('all'); };
  const changeView = (next: DashboardView) => { setView(next); saveDashboardView(viewKey, next); };
  const refresh = async () => {
    try { const [dashboards, knowledge] = await Promise.all([listDashboards(), listGroups()]); setItems(dashboards); setGroups(knowledge); setError(''); }
    catch (e) { setError(dashboardErrorText(e)); }
  };
  const selectedSource = sources.find(source => source.id === group);
  const duplicateName = Boolean(title.trim()) && items.some(item => item.title.trim() === title.trim());
  const open = () => { setTitle(''); setDescription(''); setFillNotice(''); setFormError(''); if (!selectedSource) setGroup(sources[0]?.id || ''); setFormOpen(true); };
  const useSourceName = () => {
    if (!selectedSource) return;
    setTitle(dashboardNameFromKnowledgeBase(selectedSource.name));
    setFillNotice(selectedSource.name.trim().length > 80 ? '已填入知识库名称，超出80字的部分已截取，可继续修改。' : '已填入知识库名称，可继续修改。');
  };
  const create = async (event: React.FormEvent) => {
    event.preventDefault(); if (!selectedSource || !title.trim() || creating) return;
    setCreating(true); setFormError('');
    try {
      const dashboard = await createDashboard(group, title, description);
      navigate(`/dashboards/${dashboard.id}`);
    } catch (e) { setFormError(dashboardErrorText(e)); setCreating(false); }
  };
  return <div className="dashboard-library kw-page workspace-overview">
    <KnowledgeBreadcrumb items={[{ label: '返回对话', to: '/chat' }, { label: '仪表盘' }]} />
    <div className="kw-scroll"><div className="kw-content">
    <header className="kw-pagehead"><div className="kw-heading"><h1>仪表盘</h1><p>查看业务看板，或新建看板开始对话创作。</p></div><div className="kw-actions"><button className="dashboard-secondary" aria-label="刷新看板列表" disabled={loading} onClick={() => void refresh()}><Icon name="refresh" size="sm" /></button><button className="dashboard-primary" onClick={open} disabled={loading}><Icon name="plus" size="sm" />新建看板</button></div></header>
    {error && <div className="dashboard-notice error" role="alert">{error}</div>}
    <div className="dashboard-library-toolbar">
      <label className="dashboard-search"><Icon name="search" size="sm" /><input type="search" aria-label="搜索看板" placeholder="搜索看板名称、描述或知识库…" maxLength={160} value={query} onChange={e => setQuery(e.target.value)} /></label>
      <div className="dashboard-segment" role="group" aria-label="看板范围">{filters.map(([value, label]) => <button type="button" key={value} aria-pressed={filter === value} onClick={() => setFilter(value)}>{label}</button>)}</div>
      <div className="dashboard-toolbar-end">
        <label className="dashboard-select"><select aria-label="按看板状态筛选" value={status} onChange={e => setStatus(e.target.value)}><option value="all">状态 · 全部</option>{dashboardStatuses.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select><Icon name="chevron" size="sm" /></label>
        <label className="dashboard-select"><select aria-label="看板排序" value={sort} onChange={e => setSort(e.target.value as DashboardSort)}><option value="updated">最近更新</option><option value="created">最近创建</option><option value="name">名称排序</option></select><Icon name="chevron" size="sm" /></label>
        <div className="dashboard-segment dashboard-view-toggle" role="group" aria-label="看板视图"><button type="button" aria-label="卡片视图" aria-pressed={view === 'grid'} onClick={() => changeView('grid')}><Icon name="table" size="sm" /></button><button type="button" aria-label="列表视图" aria-pressed={view === 'rows'} onClick={() => changeView('rows')}><Icon name="list" size="sm" /></button></div>
      </div>
    </div>
    {knowledgeId && <DashboardKnowledgeDrawer key={knowledgeId} groupId={knowledgeId} onClose={() => setKnowledgeId(null)} />}
    {!loading && !error && <p className="dashboard-result-count" role="status">共 <strong>{visible.length}</strong> 个看板{filtered && <button className="dashboard-secondary clear" onClick={clearFilters}>重置筛选</button>}</p>}
    {loading ? <div className="dashboard-loading" role="status">正在读取看板…</div> : visible.length ? <div className={view === 'grid' ? 'dashboard-grid' : 'dashboard-rows'}>{visible.map(item => <DashboardCard key={item.id} item={item} group={groupMap.get(item.groupId)} onPermissions={() => setPermissions(item)} onDelete={() => setRemoving(item)} onEdit={() => setEditing(item)} onKnowledge={() => setKnowledgeId(item.groupId)} />)}</div>
      : <div className="dashboard-library-empty"><span className="dashboard-feature-icon"><Icon name="chart" /></span><h3>{items.length ? '没有找到匹配的看板' : '你的第一张看板，从一个问题开始'}</h3><p>{items.length ? '试试其他关键词，或调整看板范围和状态。' : '选好知识库，告诉助手你关心的指标、趋势和业务表现。'}</p>{filtered && <button className="dashboard-secondary" onClick={clearFilters}>重置筛选</button>}</div>}
    </div></div>
    {editing && <DashboardMetadataDialog dashboard={editing} onClose={() => setEditing(null)} onSaved={next => { setItems(previous => previous.map(item => item.id === next.id ? { ...item, ...next, html: null } : item)); setEditing(null); }} />}
    {permissions && <DashboardPermissions dashboard={permissions} name={groupMap.get(permissions.groupId)?.name || '当前知识库'} onClose={() => setPermissions(null)} onChanged={() => void refresh()} />}
    {removing && <DashboardDeleteDialog dashboard={removing} onClose={() => setRemoving(null)} onChanged={() => void refresh()} onDeleted={() => { setItems(previous => previous.filter(item => item.id !== removing.id)); setRemoving(null); }} />}
    {formOpen && <DataSourceDialog className="dashboard-dialog" title="新建看板" description="选择知识库并命名，也可以补充用途说明。进入创作页后再聊聊你的需求。" busy={creating} onClose={() => setFormOpen(false)}>
      <form onSubmit={create}><div className="dashboard-dialog-body">{formError && <p className="dashboard-notice error" role="alert">{formError}</p>}
        <div className="dashboard-field"><label id="dashboard-group-label" htmlFor="dashboard-group">知识库 <span className="dashboard-required" aria-hidden="true">*</span></label>
          <DashboardKnowledgePicker id="dashboard-group" sources={sources} value={group} disabled={creating} onChange={value => { setGroup(value); setFillNotice(''); }} />
          <small>{sources.length ? '仅使用你有查询权限的已发布知识库' : <>还没有可用的已发布知识库。<Link to="/configure/datasets">前往知识库完成建模并发布</Link></>}</small>
        </div>
        <div className="dashboard-field"><div className="dashboard-field-heading"><label htmlFor="dashboard-title">看板名称 <span className="dashboard-required" aria-hidden="true">*</span></label><button type="button" className="dashboard-use-source-name" disabled={creating || !selectedSource} title="用所选知识库名称替换当前名称，填入后可自行修改" onClick={useSourceName}><Icon name="copy" size="sm" />使用知识库名称</button></div>
          <input id="dashboard-title" aria-label="看板名称" required value={title} disabled={creating} maxLength={80} placeholder="例如：业务经营概览" onChange={event => { setTitle(event.target.value); setFillNotice(''); }} />
          {fillNotice && <small role="status">{fillNotice}</small>}{duplicateName && <small className="dashboard-name-hint">已有可见看板使用此名称，仍可创建；建议补充用途便于区分。</small>}
        </div>
        <label className="dashboard-field" htmlFor="dashboard-create-description">描述 <span className="dashboard-optional">选填</span><textarea id="dashboard-create-description" aria-label="描述（选填）" aria-describedby="dashboard-create-description-count" rows={3} maxLength={500} value={description} disabled={creating} placeholder="例如：供区域负责人每周查看销售表现与重点门店。" onChange={event => setDescription(event.target.value)} /><small id="dashboard-create-description-count">{description.length}/500</small></label>
      </div><footer className="dashboard-dialog-foot"><button className="dashboard-secondary clear" type="button" disabled={creating} onClick={() => setFormOpen(false)}>取消</button><button className="dashboard-primary" disabled={!selectedSource || !title.trim() || creating} type="submit">{creating ? '正在创建…' : '创建并进入'}</button></footer></form>
    </DataSourceDialog>}
  </div>;
}
