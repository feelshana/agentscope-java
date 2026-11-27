import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { getCalibers } from '../../api/calibers';
import type { CaliberTable } from '../../api/calibers';
import CaliberOverview from './CaliberOverview';
import CaliberPublishDrawer from './CaliberPublishDrawer';
import CaliberReview from './CaliberReview';
import CaliberVerify from './CaliberVerify';
import { caliberLabel, caliberRequest } from './caliberPresentation';
import type { CaliberStep, CheckItem } from './caliberPresentation';
import './ModelingOptimization.css';
import './CaliberPanel.css';

const message = (e: unknown) => e instanceof Error ? e.message : String(e);

/**
 * spec153: the caliber page in steps. The overview shows each table's stage; a table is reviewed one
 * caliber at a time and then verified one question at a time; the publish check lists what blocks the
 * release. Review and verdicts are a person's own clicks (spec149), verification never runs a query.
 * Where the person is lives in the URL ({@code table}, {@code step}, {@code item}, {@code check}).
 */
export default function CaliberPanel({ groupId, assistantIdle, onDraft, onAsk, onPublish, onDocuments }: {
  groupId: string;
  assistantIdle: boolean;
  /** Queues a caliber turn that copies the documents; returns whether it was queued. */
  onDraft: (text: string, label: string) => boolean;
  /** Prefills the modeling conversation; the person finishes and sends the request. */
  onAsk: (text: string) => void;
  /** Opens the draft bar's release review, where the knowledge base is published. */
  onPublish: () => void;
  /** Opens the documents page, where business documents are uploaded. */
  onDocuments: () => void;
}) {
  const [params, setParams] = useSearchParams();
  const [tables, setTables] = useState<CaliberTable[] | null>(null);
  const [error, setError] = useState('');

  const refresh = useCallback(async () => setTables(await getCalibers(groupId)), [groupId]);
  // A finished turn may have drafted or changed a list; reload when the assistant becomes idle.
  useEffect(() => { if (assistantIdle) void refresh().catch(e => setError(message(e))); }, [refresh, assistantIdle]);

  const navigate = (next: { table?: string | null; step?: CaliberStep | null; item?: string | null; check?: boolean }) =>
    setParams(previous => {
      const out = new URLSearchParams(previous);
      for (const [key, value] of Object.entries({ table: next.table, step: next.step, item: next.item }))
        if (value === null) out.delete(key); else if (value !== undefined) out.set(key, value);
      if (next.check !== undefined) { if (next.check) out.set('check', '1'); else out.delete('check'); }
      return out;
    });
  const replace = (next: CaliberTable) => {
    setTables(previous => previous?.map(t => t.model === next.model ? next : t) ?? previous);
    window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
  };
  const draft = (t: CaliberTable) => { if (onDraft(caliberRequest(t), caliberLabel(t))) setError(''); };
  const go = (model: string | undefined, step: CheckItem['step']) => {
    if (step === 'document') { navigate({ check: false }); onDocuments(); return; }
    const t = tables?.find(x => x.model === model);
    if (!t || !step) { navigate({ check: false }); return; }
    if (step === 'draft') { navigate({ check: false }); draft(t); return; }
    navigate({ table: t.model, step, item: null, check: false });
  };

  const table = tables?.find(t => t.model === params.get('table')) ?? null;
  const step: CaliberStep | null = params.get('step') === 'verify' ? 'verify' : params.get('step') === 'review' ? 'review' : null;
  const back = () => navigate({ table: null, step: null, item: null });

  return <section className="sm-pane cw-pane" aria-label="口径">
    {error && <div role="alert" className="da-alert da-alert-error">{error}</div>}
    {tables === null ? <div className="da-small">{error ? '读取失败' : '加载中…'}</div>
      : table && step === 'review' ? <CaliberReview groupId={groupId} table={table} item={params.get('item')}
        onItem={item => navigate({ item })} onReplace={replace} onBack={back} onVerify={() => navigate({ step: 'verify', item: null })}
        onAsk={onAsk} onDraft={() => draft(table)} assistantIdle={assistantIdle} onCheck={() => navigate({ check: true })} onPublish={onPublish} />
      : table && step === 'verify' ? <CaliberVerify groupId={groupId} table={table} item={params.get('item')}
        onItem={item => navigate({ item })} onReplace={replace} onBack={back} onReview={item => navigate({ step: 'review', item })}
        onCheck={() => navigate({ check: true })} onPublish={onPublish} />
      : <CaliberOverview tables={tables} assistantIdle={assistantIdle} onGo={go} onCheck={() => navigate({ check: true })} onPublish={onPublish} />}
    {tables !== null && params.get('check') === '1' && <CaliberPublishDrawer tables={tables} assistantIdle={assistantIdle}
      onGo={go} onClose={() => navigate({ check: false })}
      onPublish={() => { navigate({ check: false }); onPublish(); }} />}
  </section>;
}
