import { useCallback, useEffect, useRef, useState } from 'react';
import { decideAssetProposal, listAssetProposals } from '../../api/semanticAssets';
import type { AssetProposal } from '../../api/semanticAssets';

/** The asset-turn request for a query template proposal; drafted from the proposal's own words. */
export const proposalRequest = (p: AssetProposal) =>
  `请建设语义资产。需求或已有 SQL 如下：\n建议建一个查询模板「${p.name}」。\n要解决的问题：${p.need}\n起草要点：${p.outline}`;

/**
 * ADR0127: in the modeling chat a query template is drafted only when the user asked for it in so
 * many words. The assistant records it as an explicit proposal and this component starts the
 * declared asset turn once the assistant is idle, so the user needs no further click. Everything
 * else is proposed in the query coach.
 */
export default function AssetProposalCards({ groupId, disabled, refreshKey, onDraft }: {
  groupId: string;
  /** The assistant is busy; drafting starts once it is idle. */
  disabled: boolean;
  refreshKey?: unknown;
  onDraft: (text: string, label: string) => boolean;
}) {
  const [started, setStarted] = useState<string[]>([]);
  const [error, setError] = useState('');
  const handled = useRef(new Set<string>());
  const draft = useRef(onDraft); draft.current = onDraft;
  const run = useCallback(async () => {
    try {
      const list = await listAssetProposals(groupId);
      for (const p of list.filter(x => x.status === 'PENDING' && x.explicit)) {
        if (handled.current.has(p.id)) continue;
        handled.current.add(p.id);
        if (!draft.current(proposalRequest(p), `起草查询模板：${p.name}`)) { handled.current.delete(p.id); return; }
        await decideAssetProposal(groupId, p.id, 'DRAFTED');
        setStarted(all => [...all, p.name]);
        window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
      }
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  }, [groupId]);
  useEffect(() => { if (!disabled) void run(); }, [disabled, refreshKey, run]);
  if (!started.length && !error) return null;
  return <div className="sa-proposals" aria-label="起草中的查询模板">
    {error && <div role="alert" className="da-small" style={{ color: 'var(--da-danger)' }}>{error}</div>}
    {started.map(name => <div key={name} role="status" className="da-small sa-proposal">
      已按你的要求开始起草查询模板「{name}」，完成后到"公共视图"页核查和验证。
    </div>)}
  </div>;
}
