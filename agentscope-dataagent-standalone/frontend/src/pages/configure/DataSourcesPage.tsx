import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { deleteDataSource, ExternalDataSource, getDataSourceStatus, listDataSources } from '../../api/datasources';
import { KnowledgeBreadcrumb, WorkspaceMenu } from '../../components/KnowledgeWorkspace';
import DataSourceDialog from '../../components/DataSourceDialog';
import DataSourceFormModal from '../../components/DataSourceFormModal';
import DataSourceDetailModal from '../../components/DataSourceDetailModal';
import { dataSourceConnection, dataSourceKind } from '../../components/dataSourceConnection';
import Icon from '../../components/Icon';
import { toast } from '../../components/Toast';
import '../../components/DataSources.css';

type ConnectionState = { state: 'checking' | 'connected' | 'disconnected' | 'error'; error?: string | null };

export default function DataSourcesPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  useEffect(() => {
    if (searchParams.get('create') !== '1') return;
    setEditor({ source: null });
    const next = new URLSearchParams(searchParams);
    next.delete('create');
    setSearchParams(next, { replace: true });
  }, [searchParams, setSearchParams]);
  const [items, setItems] = useState<ExternalDataSource[]>([]);
  const [statuses, setStatuses] = useState<Record<string, ConnectionState>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editor, setEditor] = useState<{ source: ExternalDataSource | null } | null>(null);
  const [detail, setDetail] = useState<ExternalDataSource | null>(null);
  const [deleting, setDeleting] = useState<ExternalDataSource | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const generation = useRef(0);
  const active = useRef(false);
  const requests = useRef(new Set<string>());
  const deletePending = useRef(false);

  const check = useCallback(async (id: string, current: number) => {
    const key = `${current}:${id}`;
    if (requests.current.has(key)) return;
    requests.current.add(key);
    setStatuses(previous => ({ ...previous, [id]: { state: 'checking' } }));
    try {
      const result = await getDataSourceStatus(id);
      if (generation.current === current) setStatuses(previous => ({ ...previous, [id]: { state: result.connected ? 'connected' : 'disconnected', error: result.error } }));
    } catch (e) {
      if (generation.current === current) setStatuses(previous => ({ ...previous, [id]: { state: 'error', error: e instanceof Error ? e.message : String(e) } }));
    } finally {
      requests.current.delete(key);
    }
  }, []);

  const refresh = useCallback(async () => {
    const current = ++generation.current;
    setLoading(true); setError(null);
    try {
      const list = await listDataSources();
      if (generation.current !== current) return;
      setStatuses({});
      setItems(list);
      list.forEach(source => { void check(source.id, current); });
    } catch (e) {
      if (generation.current === current) setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (generation.current === current) setLoading(false);
    }
  }, [check]);

  useEffect(() => {
    active.current = true;
    void refresh();
    return () => { active.current = false; generation.current += 1; };
  }, [refresh]);

  function edit(source: ExternalDataSource) {
    if (!dataSourceConnection(source)) {
      setError('旧连接地址格式不受支持，请重新创建 MySQL 或 PostgreSQL 数据源');
      return;
    }
    setEditor({ source });
  }

  async function remove() {
    if (!deleting || deletePending.current) return;
    deletePending.current = true;
    setBusy(true); setDeleteError(null);
    try {
      await deleteDataSource(deleting.id);
      if (!active.current) return;
      setDeleting(null);
      toast('数据源已删除', 'success');
      await refresh();
    } catch (e) {
      if (active.current) setDeleteError(e instanceof Error ? e.message : String(e));
    } finally {
      deletePending.current = false;
      if (active.current) setBusy(false);
    }
  }

  return <div className="kw-page ds-page">
    <KnowledgeBreadcrumb items={[{ label: '知识库', to: '/configure/datasets' }, { label: '数据源管理' }]} />
    <div className="kw-scroll"><div className="kw-content">
      <div className="kw-pagehead"><div className="kw-heading"><h1>数据源管理</h1><p>配置用于关联业务表的外部连接。</p></div>
        <div className="kw-actions"><button className="da-btn da-btn-primary" onClick={() => setEditor({ source: null })}><Icon name="plus" size="sm" />添加数据源</button></div>
      </div>
      {error && <div className="ds-feedback ds-failed" role="alert"><Icon name="warn" size="sm" /><span>{error}</span><button className="da-btn da-btn-sm" onClick={refresh}>重试</button></div>}
      <div className="kw-tablebox"><div className="kw-table-scroll" tabIndex={0} role="region" aria-label="数据源列表，可横向滚动">
        <table className="kw-table ds-table" aria-label="数据源列表" aria-busy={loading}>
          <thead><tr><th scope="col">数据源名称</th><th scope="col">连接类型</th><th scope="col">连接状态</th><th scope="col">操作</th></tr></thead>
          <tbody>
            {loading ? <tr><td colSpan={4} className="ds-empty" role="status">正在加载数据源…</td></tr>
              : error && items.length === 0 ? <tr><td colSpan={4} className="ds-empty">数据源加载失败，请重试。</td></tr>
              : items.length === 0 ? <tr><td colSpan={4}><div className="ds-empty"><Icon name="database" /><h2>暂无数据源</h2><p>添加一个外部连接，用于关联业务表。</p><button className="da-btn" onClick={() => setEditor({ source: null })}><Icon name="plus" size="sm" />添加数据源</button></div></td></tr>
              : items.map(source => {
                const connection = dataSourceConnection(source);
                const status = statuses[source.id];
                const checking = !status || status.state === 'checking';
                return <tr key={source.id}>
                  <td><strong className="ds-name" title={source.name}>{source.name}</strong><div className="ds-meta" title={connection?.database || undefined}>{connection?.database || '未指定数据库'}</div></td>
                  <td><span className="ds-type">{dataSourceKind(source.kind)}</span></td>
                  <td><span className={`ds-status ${status?.state === 'connected' ? 'ds-connected' : checking ? '' : 'ds-failed'}`} role="status" title={status?.error || undefined}>
                    {status?.state === 'connected' ? '已连通' : checking ? '检测中…' : status?.state === 'error' ? '检测失败' : '未连通'}
                  </span>{status?.error && <div className="ds-meta ds-status-error" title={status.error}>{status.error}</div>}</td>
                  <td><div className="ds-row-actions"><button className="da-btn da-btn-sm" onClick={() => setDetail(source)}>详情</button>
                    <button className="da-btn da-btn-ghost da-btn-sm" onClick={() => edit(source)}>配置</button>
                    <button className="da-btn da-btn-ghost da-btn-sm" disabled={checking} onClick={() => void check(source.id, generation.current)}><Icon name="refresh" size="sm" />{checking ? '检测中…' : '检测连接'}</button>
                    <WorkspaceMenu label={`${source.name}的更多操作`} items={[{ label: '删除数据源', icon: 'trash', danger: true, onClick: () => { setDeleting(source); setDeleteError(null); } }]} />
                  </div></td>
                </tr>;
              })}
          </tbody>
        </table>
      </div><div className="kw-tablebottom">{loading ? '正在读取数据源' : error && items.length === 0 ? '暂时无法读取数据源数量' : `共 ${items.length} 个数据源`}</div></div>
      <p className="ds-page-note">一个知识库关联一个外部数据源。Doris 通过 MySQL 协议接入；是否兼容以实际连接、发布和问答验收为准。</p>
    </div></div>
    {editor && <DataSourceFormModal source={editor.source} onClose={() => setEditor(null)} onSaved={() => { setEditor(null); toast(editor.source ? '数据源已更新' : '数据源已添加', 'success'); void refresh(); }} />}
    {detail && <DataSourceDetailModal dataSource={detail} onClose={() => setDetail(null)} />}
    {deleting && <DataSourceDialog title="删除数据源" description={deleting.name} onClose={() => setDeleting(null)} busy={busy}>
      <div className="ds-dialog-body"><p>确认删除该数据源？已关联的库表需先解除关联。</p>{deleteError && <div className="ds-feedback ds-failed" role="alert">{deleteError}</div>}</div>
      <footer className="ds-dialog-foot"><button className="da-btn" onClick={() => setDeleting(null)} disabled={busy}>取消</button><button className="da-btn da-btn-danger" onClick={remove} disabled={busy}>{busy ? '删除中…' : '确认删除'}</button></footer>
    </DataSourceDialog>}
  </div>;
}
