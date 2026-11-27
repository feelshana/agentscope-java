import type { CaliberTable } from '../../api/calibers';
import { caliberStage, nextStep, publishCheck, publishedText, selectionDirectory, stageCounts, stepProgress } from './caliberPresentation';
import type { CaliberAction, CaliberStage } from './caliberPresentation';

const STAGES: { id: CaliberStage; label: string; hint: string }[] = [
  { id: 'draft', label: '待起草', hint: '有文档还没有清单，或文档有更新' },
  { id: 'review', label: '待核对', hint: '逐条核对口径与表说明' },
  { id: 'verify', label: '待验证', hint: '每个口径一道问题，由你判定' },
  { id: 'ready', label: '已完成', hint: '核对并验证通过' },
];
const ORDER: Record<CaliberStage, number> = { draft: 0, review: 1, verify: 2, ready: 3 };

/** spec153 overview: one card per table with its four steps and a single next action. */
export default function CaliberOverview({ tables, assistantIdle, onGo, onCheck, onPublish }: {
  tables: CaliberTable[];
  assistantIdle: boolean;
  onGo: (model: string, step: Exclude<CaliberAction, 'publish'>) => void;
  onCheck: () => void;
  onPublish: () => void;
}) {
  const counts = stageCounts(tables);
  const directory = selectionDirectory(tables);
  const blocking = publishCheck(tables).blocking.length;
  return <div className="cw-overview">
    <header className="cw-head">
      <div className="mo-grow">
        <h2>口径</h2>
        <p>每张表一份口径清单，问数只按清单取数。每张表依次完成 起草 → 核对 → 验证，全部完成后知识库才能发布。</p>
      </div>
      {tables.length > 0 && <button type="button" className={`da-btn ${blocking ? '' : 'da-btn-primary'}`} onClick={onCheck}>
        发布检查{blocking ? `（${blocking} 项阻断）` : ''}</button>}
    </header>
    {tables.length === 0 ? <div className="da-card sm-empty">还没有声明了适用模型的业务文档。在“资料”页上传业务文档，并在文档开头写一行“适用模型：`模型名`”。</div> : <>
      <div className="cw-stats" aria-label="进度汇总">
        {STAGES.map(s => <div key={s.id} className="cw-stat"><span className="da-small">{s.label}</span>
          <strong>{counts[s.id]} 张表</strong><span className="da-small">{s.hint}</span></div>)}
      </div>
      <div className="cw-overview-body">
        <section className="cw-cards" aria-label="口径清单">
          {[...tables].sort((a, b) => ORDER[caliberStage(a)] - ORDER[caliberStage(b)]).map(t => <TableCard key={t.model} table={t} assistantIdle={assistantIdle}
            onGo={(model, step) => step === 'publish' ? (blocking ? onCheck() : onPublish()) : onGo(model, step)} />)}
          <p className="da-small cw-foot">上传带“适用模型”的业务文档后，建模结束会自动排队起草；文档写原表名即可，平台会对上带 ds_ 前缀的模型名。</p>
        </section>
        <aside className="cw-directory" aria-label="选表目录">
          <h3>选表目录</h3>
          <p className="da-small">问数时先按“业务 × 统计对象”选表，由各表的表说明生成，发布后生效。</p>
          {directory.length === 0 ? <p className="da-small">核对表说明后在这里显示。</p>
            : <div className="cw-table-wrap"><table className="da-table">
              <thead><tr><th>业务</th><th>统计对象</th><th>默认指标</th><th>时间</th></tr></thead>
              <tbody>{directory.map(d => <tr key={d.model} title={d.model}>
                <td>{d.business}</td><td>{d.audience}</td><td className={d.hasDefault ? '' : 'cw-warn-text'}>{d.metric}</td><td>{d.grain}</td></tr>)}</tbody>
            </table></div>}
          {directory.some(d => !d.hasDefault) && <p className="cw-hint cw-hint-warn">没有默认指标的表，问数时用户没说指标，助手会先问用哪个。可在表说明里补充。</p>}
        </aside>
      </div>
    </>}
  </div>;
}

function TableCard({ table: t, assistantIdle, onGo }: {
  table: CaliberTable; assistantIdle: boolean; onGo: (model: string, step: CaliberAction) => void;
}) {
  const stage = caliberStage(t);
  const next = nextStep(t);
  const p = stepProgress(t);
  const s = t.scenario;
  const steps: { id: CaliberStage; name: string; detail: string }[] = [
    { id: 'draft', name: '起草', detail: t.status === 'NO_DOCUMENT' ? '缺业务文档' : !t.listPath ? '还没有清单' : t.status === 'OUTDATED' ? '文档有更新' : t.errors.length ? '清单有错误' : `${t.rows.length} 个口径` },
    { id: 'review', name: '核对', detail: `${p.reviewed} / ${p.reviewTotal} 已核对` },
    { id: 'verify', name: '验证', detail: p.problems ? `${p.passed} 符合 · ${p.problems} 不符合` : `${p.passed} / ${p.verifyTotal} 符合` },
    { id: 'ready', name: '发布', detail: stage === 'ready' ? (t.published === 'CURRENT' ? '已发布' : '可发布') : '未就绪' },
  ];
  const tone = (id: CaliberStage) => ORDER[id] < ORDER[stage] || stage === 'ready' ? 'cw-step-done' : id === stage ? 'cw-step-now' : '';
  return <article className="cw-card">
    <div className="cw-card-head">
      <div className="mo-grow">
        <div className="cw-card-title"><strong>{t.subject ?? t.model}</strong>
          {s && <span className="mo-pill">{s.business?.name ?? '业务未写'} · {s.audience?.name ?? '统计对象未写'}</span>}
          {t.status === 'OUTDATED' && <span className="mo-pill mo-amber">文档有更新</span>}
          {t.status === 'NO_DOCUMENT' && <span className="mo-pill mo-amber">缺业务文档</span>}
          {p.problems > 0 && <span className="mo-pill cw-pill-problem">{p.problems} 个不符合</span>}</div>
        <code className="cw-model">{t.model}</code>
        <div className="da-small">{t.documents.length ? t.documents.map(d => `《${d}》`).join('、') : '无对应文档'}
          {s?.time ? ` · ${s.time.grain === 'month' ? '按月' : '按日'}` : ''} · {publishedText(t)}</div>
      </div>
      <button type="button" className={`da-btn ${next.step === 'review' && stage === 'ready' ? '' : 'da-btn-primary'}`} disabled={next.step === 'draft' && !assistantIdle}
        title={next.step === 'draft' && !assistantIdle ? '助手正在工作，完成后再起草' : undefined} onClick={() => onGo(t.model, next.step)}>{next.label}</button>
    </div>
    <ol className="cw-steps" aria-label="进度">
      {steps.map(x => <li key={x.id} className={`cw-step ${tone(x.id)}`} aria-current={x.id === stage && stage !== 'ready' ? 'step' : undefined}>
        <strong>{x.name}</strong><span>{x.detail}</span></li>)}
    </ol>
  </article>;
}
