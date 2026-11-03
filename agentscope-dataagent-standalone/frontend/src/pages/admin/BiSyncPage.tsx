import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { BiSyncStatus, getBiSyncStatus } from '../../api/biSync';
import { BiSyncStatusPanel } from '../../components/BiSyncStatusPanel';
import { KnowledgeBreadcrumb } from '../../components/KnowledgeWorkspace';
import Icon from '../../components/Icon';
import '../../secondary-pages.css';
import './bi-sync.css';

export default function BiSyncPage() {
  const [data, setData] = useState<BiSyncStatus | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const abort = new AbortController();
    setLoading(true); setError('');
    void getBiSyncStatus(abort.signal).then(value => {
      if (!abort.signal.aborted) setData(value);
    }).catch((reason: unknown) => {
      if (!abort.signal.aborted) setError(reason instanceof Error ? reason.message : '读取失败，请稍后重试。');
    }).finally(() => { if (!abort.signal.aborted) setLoading(false); });
    return () => abort.abort();
  }, [revision]);
  return <div className="kw-page kw-secondary-page bi-sync-page">
    <KnowledgeBreadcrumb items={[{ label: '更多', to: '/more' }, { label: 'BI数据同步' }]} />
    <div className="kw-scroll"><div className="kw-content bi-sync-content">
      <div className="kw-pagehead"><div className="kw-heading"><h1>BI数据同步</h1><p>查看公共知识库的数据更新情况，仅全局管理员可见。</p></div>
        <button className="da-btn" type="button" disabled={loading} onClick={() => setRevision(value => value + 1)}>
          <Icon name="refresh" />{loading ? '正在读取' : '刷新状态'}
        </button>
      </div>
      {error && <div className="secondary-alert" role="alert"><Icon name="warn" /><span>{error}{data ? ' 当前保留的是上次读取结果。' : ''}</span>
        <button className="da-btn da-btn-sm" type="button" disabled={loading} onClick={() => setRevision(value => value + 1)}>重试</button></div>}
      {data ? <BiSyncStatusPanel data={data} /> : loading ? <div className="bi-sync-loading" role="status">正在读取同步状态…</div> : null}
      <section className="bi-sync-management"><Icon name="info" /><div><h2>同步与知识库权限分别管理</h2>
        <p>服务器计划每日凌晨 02:00 执行，实际是否启用以服务器配置为准。此页只查看状态，不启动任务或修改数据库。</p>
        <p>不再使用通用知识库时，先在服务器暂停同步、确认任务退出，再按需移除定时任务。取消全员使用或删除知识库不会自动停用同步，也不会由此页删除同步表。</p>
        <Link to="/admin/public-knowledge-bases">管理公共知识库 <span aria-hidden="true">→</span></Link>
      </div></section>
    </div></div>
  </div>;
}
