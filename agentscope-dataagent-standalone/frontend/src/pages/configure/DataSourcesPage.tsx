import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { dataSourceRoleLabel, deleteDataSource, ExternalDataSource, getDataSourceStatus, listDataSources, setDataSourceEnabled } from '../../api/datasources';
import { KnowledgeBreadcrumb, WorkspaceMenu } from '../../components/KnowledgeWorkspace';
import DataSourceDialog from '../../components/DataSourceDialog';
import DataSourceFormModal from '../../components/DataSourceFormModal';
import DataSourceDetailModal from '../../components/DataSourceDetailModal';
import DataSourceMembersModal from '../../components/DataSourceMembersModal';
import DataSourceAssociateChooser from '../../components/DataSourceAssociateChooser';
import { dataSourceConnection, dataSourceKind } from '../../components/dataSourceConnection';
import Icon from '../../components/Icon';
import ErrorNotice from '../../components/ErrorNotice';
import { toast } from '../../components/Toast';
import '../../components/DataSources.css';
import '../../workspace-overview.css';

type ConnectionState = { state: 'checking' | 'connected' | 'disconnected' | 'error'; error?: string | null };
export default function DataSourcesPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [items, setItems] = useState<ExternalDataSource[]>([]);
  const [statuses, setStatuses] = useState<Record<string, ConnectionState>>({});
  const [query, setQuery] = useState(''); const [filter, setFilter] = useState<'all' | 'mine' | 'shared'>('all');
  const [loading, setLoading] = useState(true); const [error, setError] = useState<string | null>(null);
  const [editor, setEditor] = useState<{ source: ExternalDataSource | null } | null>(null);
  const [detail, setDetail] = useState<ExternalDataSource | null>(null);
  const [members, setMembers] = useState<ExternalDataSource | null>(null);
  const [associate, setAssociate] = useState<ExternalDataSource | null>(null);
  const [action, setAction] = useState<{ type: 'delete' | 'toggle'; source: ExternalDataSource } | null>(null);
  const [busy, setBusy] = useState(false); const [actionError, setActionError] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const generation = useRef(0); const alive = useRef(true); const pending = useRef(new Set<string>()); const mutation = useRef(false);
  useEffect(() => { if (searchParams.get('create') !== '1') return; setEditor({ source: null }); const next = new URLSearchParams(searchParams); next.delete('create'); setSearchParams(next, { replace: true }); }, [searchParams, setSearchParams]);
  const refresh = useCallback(async () => {
    const current = ++generation.current; setLoading(true); setError(null);
    try { const list = await listDataSources(); if (generation.current !== current) return; setItems(list); setStatuses({}); setExpandedId(null); }
    catch (e) { if (generation.current === current) setError(e instanceof Error ? e.message : String(e)); }
    finally { if (generation.current === current) setLoading(false); }
  }, []);
  useEffect(() => { alive.current = true; void refresh(); return () => { alive.current = false; generation.current += 1; }; }, [refresh]);
  const filtered = useMemo(() => {
    const term = query.trim().toLocaleLowerCase();
    return items.filter(s => (filter === 'all' || (filter === 'mine' ? s.role === 'OWNER' : s.shared === true)) && (!term || [s.name, s.creatorName, s.creatorAccount].some(v => v?.toLocaleLowerCase().includes(term))));
  }, [items, filter, query]);
  async function check(id: string) {
    const current = generation.current; const key = `${current}:${id}`; if (pending.current.has(key)) return;
    pending.current.add(key); setStatuses(p => ({ ...p, [id]: { state: 'checking' } }));
    try { const status = await getDataSourceStatus(id); if (generation.current === current) setStatuses(p => ({ ...p, [id]: { state: status.connected ? 'connected' : 'disconnected', error: status.error } })); }
    catch (e) { if (generation.current === current) setStatuses(p => ({ ...p, [id]: { state: 'error', error: e instanceof Error ? e.message : String(e) } })); }
    finally { pending.current.delete(key); }
  }
  function edit(source: ExternalDataSource) { if (!source.canManage) return; if (!dataSourceConnection(source)) { setError('旧连接地址格式不受支持，请联系创建人检查'); return; } setEditor({ source }); }
  async function apply() {
    if (!action || mutation.current) return; mutation.current = true; setBusy(true); setActionError(null);
    try {
      if (action.type === 'delete') await deleteDataSource(action.source.id); else await setDataSourceEnabled(action.source.id, action.source.enabled === false);
      if (!alive.current) return; toast(action.type === 'delete' ? '数据源已删除' : action.source.enabled === false ? '数据源已启用' : '数据源已停用', 'success'); setAction(null); await refresh();
    } catch (e) { if (alive.current) setActionError(e instanceof Error ? e.message : String(e)); }
    finally { mutation.current = false; if (alive.current) setBusy(false); }
  }
  const scopeCount = (scope: string) => items.filter(s => scope === 'all' || (scope === 'mine' ? s.role === 'OWNER' : s.shared)).length;
  return <div className="kw-page workspace-overview ds-page ds-sharing-page">
    <KnowledgeBreadcrumb items={[{ label: '知识库', to: '/configure/datasets' }, { label: '数据源管理' }]} />
    <div className="kw-scroll"><div className="kw-content">
      <div className="kw-pagehead"><div className="kw-heading"><h1>数据源管理</h1><p>维护业务连接，安全地共享给需要的同事。</p></div><div className="kw-actions"><button className="da-btn da-btn-primary" onClick={() => setEditor({ source: null })}><Icon name="plus" size="sm" />添加数据源</button></div></div>
      <div className="ds-list-toolbar"><label className="kw-search"><Icon name="search" size="sm" /><input aria-label="搜索数据源" placeholder="搜索名称、创建人或账号" value={query} onChange={e => setQuery(e.target.value)} /></label>
        <div className="kw-tabs ds-source-filters" role="group" aria-label="数据源范围">{([['all', '全部'], ['mine', '我创建的'], ['shared', '共享给我的']] as const).map(([value, label]) => <button key={value} className={filter === value ? 'active' : ''} aria-pressed={filter === value} onClick={() => setFilter(value)}>{label}<span>{scopeCount(value)}</span></button>)}</div>
        <button type="button" className="da-btn ds-refresh" aria-label="刷新数据源列表" disabled={loading} onClick={() => void refresh()}><Icon name="refresh" size="sm" /></button>
      </div>
      {error && <ErrorNotice error={error} context="datasource"><button className="da-btn da-btn-sm" onClick={() => void refresh()}>重试</button></ErrorNotice>}
      <div className="kw-tablebox overview-surface"><div className="kw-table-scroll" tabIndex={0} role="region" aria-label="数据源列表，可横向滚动"><table className="kw-table ds-table" aria-label="数据源列表" aria-busy={loading}>
        <thead><tr><th>数据源</th><th>创建人</th><th>我的权限</th><th>连接状态</th><th>关联知识库</th><th>操作</th></tr></thead><tbody>
          {loading ? <tr><td colSpan={6} className="ds-empty" role="status">正在加载数据源…</td></tr> : !filtered.length ? <tr><td colSpan={6}><div className="ds-empty"><Icon name="database" /><h2>{items.length ? '没有匹配的数据源' : '暂无可使用的数据源'}</h2><p>{items.length ? '试试其他关键词或筛选范围。' : '创建一个连接，或请同事在“成员与权限”中邀请你。'}</p>{!items.length && <button className="da-btn" onClick={() => setEditor({ source: null })}>添加数据源</button>}</div></td></tr> : filtered.map(source => {
            const status = statuses[source.id], checking = status?.state === 'checking', disabled = source.enabled === false;
            const failed = !disabled && status && ['disconnected', 'error'].includes(status.state), expanded = failed && expandedId === source.id;
            const menus = [
              ...(source.canManage ? [{ label: '配置连接', icon: 'edit' as const, onClick: () => edit(source) }] : []),
              ...(source.canGovern ? [{ label: '成员与权限', icon: 'shield' as const, onClick: () => setMembers(source) },
                { label: disabled ? '启用数据源' : '停用数据源', icon: 'stop' as const, onClick: () => { setAction({ type: 'toggle', source }); setActionError(null); } },
                { label: '删除数据源', icon: 'trash' as const, danger: true, onClick: () => { setAction({ type: 'delete', source }); setActionError(null); } }] : []),
            ];
            return <React.Fragment key={source.id}><tr className={`ds-source-row${disabled ? ' ds-source-disabled' : ''}`}>
              <td><div className="ds-source-identity"><span className="ds-source-icon"><Icon name="database" /></span><span><strong className="ds-name" title={source.name}>{source.name}</strong><small>{dataSourceKind(source.kind)}{source.builtin ? ' · 系统内置' : ''}</small></span></div></td>
              <td><strong className="ds-creator-name">{source.creatorName || source.creatorAccount || '—'}</strong><div className="ds-meta">{source.creatorAccount || '—'}</div></td>
              <td><span className={`ds-role-badge${source.role === 'USE' ? ' ds-role-use' : ''}`}>{source.canGovern && source.role !== 'OWNER' ? '全局管理' : dataSourceRoleLabel(source.role)}</span></td>
              <td><div className="ds-status-cell"><span className={`ds-status ${status?.state === 'connected' && !disabled ? 'ds-connected' : failed ? 'ds-failed' : ''}`} role="status"><Icon name={disabled ? 'stop' : status?.state === 'connected' ? 'check' : checking ? 'refresh' : 'circleAlert'} size="sm" />{disabled ? '已停用' : checking ? '检测中…' : !status ? '未检测' : status.state === 'connected' ? '已连通' : '连接异常'}</span>{failed && <button type="button" className="ds-reason-toggle" aria-expanded={!!expanded} onClick={() => setExpandedId(expanded ? null : source.id)}>{expanded ? '收起原因' : '查看原因'}</button>}</div></td>
              <td><span className="ds-binding-count">{source.knowledgeBaseCount ?? 0}<small> 个</small></span></td>
              <td><div className="ds-row-actions"><button type="button" className="ds-row-link" disabled={disabled} onClick={() => setDetail(source)}>浏览库表</button><button type="button" className="ds-row-link ds-view-link" disabled={disabled} onClick={() => setAssociate(source)}><Icon name="link" size="sm" />关联</button><button type="button" className="ds-row-link" aria-label={`检测${source.name}连接`} disabled={disabled || checking} onClick={() => void check(source.id)}>检测</button>{menus.length > 0 && <WorkspaceMenu label={`${source.name}的更多操作`} items={menus} />}</div></td>
            </tr>{expanded && <tr className="ds-reason-row"><td colSpan={6}><ErrorNotice error={status.error || '连接未成功，请联系创建人检查'} context="datasource" /></td></tr>}</React.Fragment>;
          })}
        </tbody></table></div><div className="kw-tablebottom"><span>{loading ? '正在读取' : `显示 ${filtered.length} 个，共 ${items.length} 个可见数据源`}</span><span>连接按需检测，结果仅代表本次检测</span></div></div>
      <p className="ds-page-note"><Icon name="info" size="sm" />共享使用不需要分发数据库密码。一个知识库关联一个外部数据源；连接由应用服务器发起。</p>
    </div></div>
    {editor && <DataSourceFormModal source={editor.source} onClose={() => setEditor(null)} onSaved={() => { setEditor(null); toast(editor.source ? '数据源已更新' : '数据源已添加', 'success'); void refresh(); }} />}
    {detail && <DataSourceDetailModal dataSource={detail} initialBrowse onClose={() => setDetail(null)} />}
    {members && <DataSourceMembersModal source={members} onClose={() => setMembers(null)} onChanged={() => void refresh()} />}
    {associate && <DataSourceAssociateChooser source={associate} onClose={() => setAssociate(null)} onAssociated={() => { setAssociate(null); void refresh(); }} />}
    {action && <DataSourceDialog title={action.type === 'delete' ? '删除数据源' : action.source.enabled === false ? '启用数据源' : '停用数据源'} description={action.source.name} onClose={() => setAction(null)} busy={busy}>
      <div className="ds-dialog-body"><p className="ds-action-description">{action.type === 'delete' ? '删除连接配置，不会删除外部数据库的表。有关联知识库时，请先解除关联。' : action.source.enabled === false ? '启用后，有权限的同事可继续浏览关联，已有知识库可恢复读取外部数据。' : `该连接已关联 ${action.source.knowledgeBaseCount ?? 0} 个知识库。停用后将阻止新的外部查询、采样和发布；已保存的历史结果保留。`}</p>{actionError && <ErrorNotice error={actionError} context="datasource" />}</div>
      <footer className="ds-dialog-foot"><button type="button" className="da-btn" disabled={busy} onClick={() => setAction(null)}>取消</button><button type="button" className={`da-btn ${action.type === 'delete' ? 'da-btn-danger' : 'da-btn-primary'}`} disabled={busy} onClick={() => void apply()}>{busy ? '处理中…' : '确认'}</button></footer>
    </DataSourceDialog>}
  </div>;
}
