import { useEffect, useState } from 'react';
import { getDataSourceStatus, listDataSources, type DataSourceStatus, type ExternalDataSource } from '../../api/datasources';
import type { Dataset } from '../../api/datasets';
import Icon from '../../components/Icon';

export default function KnowledgeSourceConnection({ datasets, canManage, compact = false, onAssociate }: {
  datasets: Dataset[];
  canManage: boolean;
  compact?: boolean;
  onAssociate: () => void;
}) {
  const external = datasets.filter(table => table.origin === 'datasource');
  const sourceId = external.find(table => table.externalDataSourceId)?.externalDataSourceId;
  const [source, setSource] = useState<ExternalDataSource>();
  const [result, setResult] = useState<{ id: string; value?: DataSourceStatus; error?: string }>();
  const [checking, setChecking] = useState(false);
  useEffect(() => {
    if (!canManage || !sourceId) return;
    let active = true;
    void listDataSources().then(sources => { if (active) setSource(sources.find(entry => entry.id === sourceId)); }).catch(() => {});
    return () => { active = false; };
  }, [sourceId, canManage]);
  const status = result?.id === sourceId ? result : undefined;
  const sourceName = sourceId && source?.id === sourceId ? source.name : null;
  const label = !external.length ? '未关联' : status?.value?.connected ? '已连通'
    : status?.value ? '连接失败' : status?.error ? '检测失败' : '已关联';
  async function check() {
    if (!sourceId || checking) return;
    setChecking(true); setResult(undefined);
    try { setResult({ id: sourceId, value: await getDataSourceStatus(sourceId) }); }
    catch (e) { setResult({ id: sourceId, error: e instanceof Error ? e.message : '无法检测连接' }); }
    finally { setChecking(false); }
  }
  return <section className={compact ? 'kbw-card kbw-card-pad' : 'kbw-card kbw-connection'} aria-label="数据源连接">
    {!compact && <div className="kbw-empty-icon"><Icon name="database" size="sm" /></div>}
    <div><div className="kbw-connection-title"><h2>{compact && <Icon name="database" size="sm" />}{compact ? '数据源' : '数据源连接'}</h2><span className="kbw-status" data-ok={status?.value?.connected || undefined}>{label}</span></div>
      <p title={sourceName ?? undefined}>{external.length ? [sourceName, `已关联 ${external.length} 张表`].filter(Boolean).join(' · ') : '可关联已有数据库中的业务表。'}</p>
      {(status?.error || status?.value?.error) && <p className={compact ? 'kbw-connection-error kbw-connection-toast' : 'kbw-connection-error'} role="status">{status.error || status.value?.error}</p>}
    </div>
    {canManage && <div className="kbw-page-actions">
      {sourceId && <button type="button" className="da-btn" disabled={checking} onClick={() => void check()}><Icon name="refresh" size="sm" />{checking ? '检测中…' : '检测连接'}</button>}
      {external.length > 0 && !compact && <button type="button" className="da-btn" onClick={onAssociate}>关联更多表</button>}
    </div>}
  </section>;
}
