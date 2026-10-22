import type { FactQueryWindowCandidate } from '../api/modelingWorkflow';

export function factWindowSetup(candidates: FactQueryWindowCandidate[], pendingOnly: boolean) {
  const unknown = candidates.filter(table => table.tableRole !== 'FACT' && table.tableRole !== 'DIMENSION');
  const facts = candidates.filter(table => table.tableRole === 'FACT'
    && (!pendingOnly || !table.mode || (table.mode === 'LIMITED' && !table.timeColumn)));
  return { facts, unknown, ready: facts.length === 0 && unknown.length === 0 };
}
