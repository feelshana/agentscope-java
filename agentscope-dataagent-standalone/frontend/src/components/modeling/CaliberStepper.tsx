import type { CaliberTable } from '../../api/calibers';
import { caliberStage, stepProgress } from './caliberPresentation';
import type { CaliberStep } from './caliberPresentation';

/** spec153: the table being worked on and where it stands in draft → review → verify → publish. */
export default function CaliberStepper({ table, step, onBack, onCheck, onPublish }: {
  table: CaliberTable; step: CaliberStep; onBack: () => void; onCheck: () => void; onPublish: () => void;
}) {
  const p = stepProgress(table);
  const stage = caliberStage(table);
  const items: { id: string; label: string; done: boolean; on: boolean }[] = [
    { id: 'draft', label: '起草', done: stage !== 'draft', on: false },
    { id: 'review', label: `核对 ${p.reviewed}/${p.reviewTotal}`, done: p.reviewed === p.reviewTotal && !table.conflicts.length && !p.problems, on: step === 'review' },
    { id: 'verify', label: `验证 ${p.passed}/${p.verifyTotal}`, done: stage === 'ready', on: step === 'verify' },
    { id: 'ready', label: '发布', done: table.published === 'CURRENT' && stage === 'ready', on: false },
  ];
  return <header className="cw-stepper">
    <button type="button" className="sm-text-link" onClick={onBack}>← 口径</button>
    <div className="cw-stepper-title mo-grow">
      <strong>{table.subject ?? table.model}</strong>
      <span className="da-small">{table.documents.length ? table.documents.map(d => `《${d}》`).join('、') : '无对应文档'}</span>
    </div>
    <ol className="cw-stepper-steps" aria-label="阶段">
      {items.map(i => <li key={i.id} className={i.on ? 'cw-ss-on' : i.done ? 'cw-ss-done' : ''} aria-current={i.on ? 'step' : undefined}>
        {i.done && !i.on ? '✓ ' : ''}{i.label}</li>)}
    </ol>
    {stage === 'ready' && table.published !== 'CURRENT'
      ? <button type="button" className="da-btn da-btn-sm da-btn-primary" onClick={onPublish}>去发布</button>
      : <button type="button" className="da-btn da-btn-sm" onClick={onCheck}>发布检查</button>}
  </header>;
}
