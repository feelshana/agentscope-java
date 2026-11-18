import type { FactQueryWindowCandidate } from '../api/modelingWorkflow';

export type FactWindowDraft = { mode: 'LIMITED' | 'UNLIMITED'; days: number; timeColumn: string };

/** Preserve saved policies; only unconfigured facts default to unlimited. */
export function factWindowDraft(table: FactQueryWindowCandidate): FactWindowDraft {
  return { mode: table.mode ?? 'UNLIMITED', days: table.windowDays ?? 31, timeColumn: table.timeColumn ?? table.temporalColumns[0] ?? '' };
}

export function factWindowSetup(candidates: FactQueryWindowCandidate[], pendingOnly: boolean) {
  const unknown = candidates.filter(table => table.tableRole !== 'FACT' && table.tableRole !== 'DIMENSION');
  const facts = candidates.filter(table => table.tableRole === 'FACT'
    && (!pendingOnly || !table.mode || (table.mode === 'LIMITED' && !table.timeColumn)));
  return { facts, unknown, ready: facts.length === 0 && unknown.length === 0 };
}
