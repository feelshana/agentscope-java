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

/**
 * spec092: standalone entry to semantic modeling. Lists the knowledge bases the user manages with
 * their modeling state; the ones waiting on the user come first.
 */
export default function ModelingHubPage() {
  const navigate = useNavigate();
  const [rows, setRows] = useState<Row[] | null>(null);
  const [error, setError] = useState<string | null>(null);

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
            published: workflow.status === 'fulfilled' && workflow.value.queryAvailable ? workflow.value.publishedVersion : null,
            changes: draft.status === 'fulfilled' ? draft.value.changes.length : 0,
            running: draft.status === 'fulfilled' && draft.value.assistantRunning,
            confirmations: questions.status === 'fulfilled' ? questions.value.length : 0,
            failed: workflow.status === 'rejected',
          };
        }));
        if (controller.signal.aborted) return;
        loaded.sort((a, b) => b.confirmations - a.confirmations || b.changes - a.changes || a.group.name.localeCompare(b.group.name));
        setRows(loaded);
      } catch (e) {
        if (!controller.signal.aborted) setError(e instanceof Error ? e.message : '加载失败');
      }
    })();
    return () => controller.abort();
  }, []);

  return <div className="kw-page">
    <KnowledgeBreadcrumb items={[{ label: '返回对话', to: '/chat' }, { label: '语义建模' }]} />
    <div className="kw-scroll"><div className="kw-content">
      <div className="kw-pagehead">
        <div className="kw-heading"><h1>语义建模</h1><p>为每个知识库梳理字段含义与业务口径，审阅后发布，问数按发布的版本回答。</p></div>
      </div>
      {error && <div className="kw-alert" role="alert">{error}</div>}
      {rows === null && !error && <div className="da-small">加载中…</div>}
      {rows?.length === 0 && <div className="da-small">你还没有可以管理的知识库。先在"知识库"中创建并上传数据。</div>}
      <div className="kw-rows">
        {rows?.map(row => <article key={row.group.id} className="kw-card" tabIndex={0} aria-label={`打开${row.group.name}的语义建模`}
          onClick={() => navigate(`/configure/modeling/${row.group.id}?asset=publish`)}
          onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); navigate(`/configure/modeling/${row.group.id}?asset=publish`); } }}>
          <div className="kw-cardtop"><div className="kw-icon"><Icon name="model" /></div><h2 title={row.group.name}>{row.group.name}</h2></div>
          <div className="kw-cardmeta" style={{ flexWrap: 'wrap', gap: 8 }}>
            <span className="da-badge">{row.published ? `线上 v${row.published}` : '尚未发布'}</span>
            {row.confirmations > 0 && <span className="da-badge" style={{ color: 'var(--da-warn)' }}>待你确认 {row.confirmations}</span>}
            <span className="da-badge">{row.changes ? `草稿修改 ${row.changes}` : '草稿与线上一致'}</span>
            {row.running && <span className="da-badge" style={{ color: 'var(--da-primary)' }}>助手运行中</span>}
            {row.failed && <span className="da-small">状态读取失败</span>}
            <span className="da-small">{row.group.datasetCount} 个数据集</span>
          </div>
        </article>)}
      </div>
    </div></div>
  </div>;
}
