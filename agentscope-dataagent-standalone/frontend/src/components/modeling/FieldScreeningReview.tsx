import { useEffect, useRef, useState } from 'react';
import { getScreeningStatus, getScreeningTable } from '../../api/fieldScreening';
import type { ScreeningStatus, ScreeningTable } from '../../api/fieldScreening';
import Icon from '../Icon';
import { canConfirmTable, confirmScreeningSelection, needsFieldReview } from './fieldScreeningActions';

export type ScreeningSelection = { datasetId?: string; pendingOnly?: boolean };

type TableReview = { table: ScreeningTable } | { failed: true };
const describe = (error: unknown) => error instanceof Error ? error.message : String(error);

export default function FieldScreeningReview({ groupId, tables, editorOpen, onOpen, onStatus, onBusy, onDone }: {
  groupId: string;
  tables: ScreeningStatus[] | null;
  editorOpen: boolean;
  onOpen: (selection: ScreeningSelection) => void;
  onStatus: (tables: ScreeningStatus[]) => void;
  onBusy: (busy: boolean) => void;
  onDone: (tables: ScreeningStatus[]) => void;
}) {
  const [reviews, setReviews] = useState<Record<string, TableReview>>({});
  const [retry, setRetry] = useState(0);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const saving = useRef(false);
  const mounted = useRef(false);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  useEffect(() => {
    if (!tables || editorOpen) return;
    let cancelled = false;
    setReviews({});
    const pending = tables.filter(table => !table.confirmed);
    const load = async () => {
      // First reads can each trigger classification. Keep this fan-out bounded.
      for (let start = 0; start < pending.length && !cancelled; start += 3) {
        const batch = pending.slice(start, start + 3);
        await Promise.all(batch.map(async ({ datasetId }) => {
          let review: TableReview;
          try { review = { table: await getScreeningTable(groupId, datasetId) }; }
          catch { review = { failed: true }; }
          if (!cancelled) setReviews(previous => ({ ...previous, [datasetId]: review }));
        }));
      }
    };
    void load();
    return () => { cancelled = true; };
  }, [groupId, tables, editorOpen, retry]);

  const rows = (tables ?? []).map(status => {
    const review = reviews[status.datasetId];
    const table = review && 'table' in review ? review.table : null;
    const unsure = table?.fields.filter(needsFieldReview) ?? [];
    const count = table?.fields.length ?? status.total;
    const included = table?.fields.filter(field => field.included).length ?? status.included;
    return { status, table, unsure, count, included, failed: !!review && 'failed' in review };
  });
  const total = rows.reduce((sum, row) => sum + row.count, 0);
  const selected = rows.reduce((sum, row) => sum + row.included, 0);
  const pending = rows.filter(row => !row.status.confirmed);
  const loading = tables === null || pending.some(row => !row.table && !row.failed);
  const failed = pending.some(row => row.failed);
  const reviewRequired = pending.some(row => row.table && !canConfirmTable(row.table));
  const next = pending.find(row => row.unsure.length) ?? pending.find(row => row.failed || row.included !== row.count) ?? pending[0];
  const showBulkAction = !failed && !reviewRequired;

  async function confirmAll() {
    if (!tables?.length || loading || !showBulkAction || saving.current) return;
    saving.current = true;
    setBusy(true); onBusy(true); setError('');
    try {
      await confirmScreeningSelection(groupId, tables, () => mounted.current);
      if (!mounted.current) return;
      const updated = await getScreeningStatus(groupId);
      if (!mounted.current) return;
      if (!updated.length || updated.some(table => !table.confirmed)) {
        onStatus(updated);
        setError('还有数据表待确认，请核对当前字段范围。');
      } else {
        onDone(updated);
      }
    } catch (error) {
      if (!mounted.current) return;
      setError(describe(error));
      // A failed table may follow successful saves. Reflect the persisted progress before retrying.
      try {
        const updated = await getScreeningStatus(groupId);
        if (mounted.current) onStatus(updated);
      } catch { if (mounted.current) setRetry(value => value + 1); }
    } finally {
      saving.current = false;
      if (mounted.current) { setBusy(false); onBusy(false); }
    }
  }

  return <section className="mw-field-review" aria-label="确认建模字段">
    <header className="mw-field-heading">
      <h2>确认建模字段</h2>
      <p>{tables === null ? '正在读取数据表…' : rows.length
        ? `已识别 ${rows.length} 张表、${total} 个字段。你可以采纳建议，也可以打开数据表调整。`
        : '请回到知识库上传文件或关联数据表。'}</p>
    </header>
    <div className="mw-field-summary">
      <div className="mw-field-labels"><span>数据表</span><span>纳入 / 全部</span></div>
      <ul aria-label="数据表列表">
        {rows.map(({ status, table, unsure, count, included, failed: unreadable }) => <li key={status.datasetId}>
          <button type="button" className="mw-field-row" aria-label={`查看 ${status.name} 的建模字段`} disabled={busy}
            onClick={() => onOpen({ datasetId: status.datasetId })}>
            <span className="mw-field-table-icon" data-source={table?.origin}><Icon name="table" size="sm" /></span>
            <span className="mw-field-table-name"><strong>{status.name}</strong>
              <small>{status.confirmed ? '已确认' : unreadable ? '字段状态读取失败' : !table ? '正在检查字段' : table.origin === 'datasource' ? '外部表' : '上传表'}</small>
            </span>
            <span className="mw-field-fraction">{included} / {count}</span><Icon name="chevron" size="sm" />
          </button>
          {unsure.length > 0 && <button type="button" className="mw-field-attention" disabled={busy}
            onClick={() => onOpen({ datasetId: status.datasetId, pendingOnly: true })}>
            <span className="mw-field-attention-count">{unsure.length} 个待确认</span>
            {unsure.slice(0, 3).map(field => <code key={field.name} title={field.reason ?? undefined}>{field.name}</code>)}
            {unsure.length > 3 && <span>等 {unsure.length} 个字段</span>}
            <Icon name="chevron" size="sm" />
          </button>}
        </li>)}
      </ul>
      {(error || failed) && <p className="mw-field-error" role="alert">
        {error || '有数据表的字段状态未能读取。可以逐表核对，或重新检查。'}
        {failed && <button type="button" className="da-btn da-btn-sm" onClick={() => { setError(''); setRetry(value => value + 1); }}>重新检查</button>}
      </p>}
      <footer className="mw-field-footer">
        <span role="status">{busy ? '正在保存字段范围…' : loading ? '正在检查待确认字段…'
          : reviewRequired ? '请先核对需要判断的字段或纳入范围。' : `将按所选的 ${selected} 个字段建模。`}</span>
        <div className="mw-field-actions">
          <button type="button" className="mp-button" disabled={busy || !rows.length} onClick={() => onOpen({})}>查看建模字段</button>
          {showBulkAction ? <button type="button" className="mp-button mp-primary" disabled={busy || loading || !rows.length} onClick={() => void confirmAll()}>
            <Icon name="check" size="sm" />{busy ? '确认中…' : '确认全部字段'}
          </button> : <button type="button" className="mp-button mp-primary" disabled={busy}
            onClick={() => onOpen({ datasetId: next?.status.datasetId, pendingOnly: !!next?.unsure.length })}>
            核对字段<Icon name="chevron" size="sm" />
          </button>}
        </div>
      </footer>
    </div>
  </section>;
}
