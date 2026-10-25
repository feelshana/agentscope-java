import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { KnowledgeBreadcrumb } from '../../components/KnowledgeWorkspace';
import Icon from '../../components/Icon';
import { listGroups } from '../../api/datasets';
import type { DatasetGroup } from '../../api/datasets';
import { getModelingWorkflow } from '../../api/modelingWorkflow';
import { getDraftReview, listConfirmations } from '../../api/draftReview';

type Row = {
  group: DatasetGroup;
  published: number | null;
  changes: number;
  confirmations: number;
  running: boolean;
  failed: boolean;
};

/** The one thing each knowledge base needs next, in the modeler's words. */
function status(row: Row): { label: string; tone: 'warn' | 'live' | 'ok' | 'muted' } {
  if (row.running) return { label: '助手运行中', tone: 'live' };
  if (row.confirmations) return { label: `待你确定 ${row.confirmations} 项`, tone: 'warn' };
  if (row.changes) return { label: row.published ? `有 ${row.changes} 处修改待发布` : '待首次发布', tone: 'warn' };
  if (row.published) return { label: `已发布 v${row.published}`, tone: 'ok' };
  return { label: '尚未建模', tone: 'muted' };
}

/**
 * spec092: standalone entry to semantic modeling. Lists the knowledge bases the user manages,
 * newest first, each with the next thing it needs.
 */
export default function ModelingHubPage() {
  const navigate = useNavigate();
  const [rows, setRows] = useState<Row[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      try {
        const groups = (await listGroups(controller.signal, true)).filter(g => !g.deletionPending);
        const loaded = await Promise.all(groups.map(async group => {
          const [workflow, draft, questions] = await Promise.allSettled([
            getModelingWorkflow(group.id), getDraftReview(group.id), listConfirmations(group.id),
          ]);
          return {
            group,
            published: workflow.status === 'fulfilled' && workflow.value.publishedVersion > 0 ? workflow.value.publishedVersion : null,
            changes: draft.status === 'fulfilled' ? draft.value.changes.length : 0,
            running: draft.status === 'fulfilled' && draft.value.assistantRunning,
            confirmations: questions.status === 'fulfilled' ? questions.value.length : 0,
            failed: workflow.status === 'rejected',
          };
        }));
        if (controller.signal.aborted) return;
        loaded.sort((a, b) => (b.group.createdAt ?? '').localeCompare(a.group.createdAt ?? '') || a.group.name.localeCompare(b.group.name));
        setRows(loaded);
      } catch (e) {
        if (!controller.signal.aborted) setError(e instanceof Error ? e.message : '加载失败');
      }
    })();
    return () => controller.abort();
  }, []);

  const keyword = query.trim().toLowerCase();
  const shown = rows?.filter(r => !keyword || `${r.group.name} ${r.group.description ?? ''}`.toLowerCase().includes(keyword));
  const open = (row: Row) => navigate(`/configure/modeling/${row.group.id}`);

  return <div className="kw-page">
    <KnowledgeBreadcrumb items={[{ label: '返回对话', to: '/chat' }, { label: '语义建模' }]} />
    <div className="kw-scroll"><div className="kw-content">
      <div className="kw-pagehead">
        <div className="kw-heading"><h1>语义建模</h1><p>为每个知识库梳理字段含义与业务口径，审阅后发布，问数按发布的版本回答。</p></div>
      </div>
      {error && <div className="kw-alert" role="alert">{error}</div>}
      <div className="kw-toolbar">
        <label className="kw-search"><Icon name="search" size="sm" /><input value={query} onChange={e => setQuery(e.target.value)} placeholder="搜索知识库" aria-label="搜索知识库" /></label>
        <span className="kw-spacer" />
        {rows && <span className="kw-count">共 {rows.length} 个 · 按创建时间从新到旧</span>}
      </div>
      {rows === null && !error && <div className="kw-empty">加载中…</div>}
      {rows?.length === 0 && <div className="kw-empty">你还没有可以管理的知识库。先在"知识库"中创建并上传数据。</div>}
      {shown && shown.length > 0 && <div className="kw-tablebox"><div className="kw-table-scroll"><table className="kw-table mh-table">
        <thead><tr><th>知识库</th><th>当前状态</th><th className="numeric">数据集</th><th>创建时间</th><th className="end">操作</th></tr></thead>
        <tbody>{shown.map(row => {
          const s = status(row);
          return <tr key={row.group.id} tabIndex={0} aria-label={`打开${row.group.name}的语义建模`} onClick={() => open(row)}
            onKeyDown={e => { if (e.key === 'Enter') open(row); }}>
            <td><div className="mh-name"><span className="mh-icon"><Icon name="model" size="sm" /></span>
              <span style={{ minWidth: 0 }}><strong title={row.group.name}>{row.group.name}</strong>
                <small>{row.group.description || '暂无说明'}</small></span></div></td>
            <td>{row.failed ? <span className="mh-status mh-muted">状态读取失败</span> : <span className={`mh-status mh-${s.tone}`}>{s.label}</span>}
              {row.published && (row.changes > 0 || row.confirmations > 0) && <span className="mh-sub">线上 v{row.published}</span>}</td>
            <td className="numeric">{row.group.datasetCount}</td>
            <td>{row.group.createdAt ? new Date(row.group.createdAt).toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '—'}</td>
            <td className="end"><button className="da-btn da-btn-sm" onClick={e => { e.stopPropagation(); open(row); }}>进入建模</button></td>
          </tr>;
        })}</tbody></table></div></div>}
      {shown && rows && rows.length > 0 && shown.length === 0 && <div className="kw-empty">没有匹配的知识库</div>}
    </div></div>
  </div>;
}
