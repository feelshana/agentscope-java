import Icon from '../Icon';

const STEPS = {
  coach: [
    ['添加业务问题', '可以只填一道，也可以逐行添加，最多 10 道'],
    ['助手逐题分析', '基于模型草稿生成 SQL，发现缺口时会请你补充'],
    ['补充口径或批准方案', '处理待确认的指标定义、规则或关系'],
    ['核对并保存示例', '检查业务口径和 SQL，确认后存入草稿，发布后供线上问数参考'],
  ],
  views: [
    ['收集已确认的问答', '从问答示例库查找建模确认和问数点赞的问题及 SQL'],
    ['找出反复出现的逻辑', '比如很多题都先算“每人首次访问”，或都把访问记录和人员部门拼在一起'],
    ['你来决定建不建', '每条建议都写明来自哪几道题、出现了几次，批准后才起草'],
    ['用原来的问题核对', '这些问题改用公共视图后重新写 SQL，新旧对照确认后再发布'],
  ],
} as const;

/** Instructional steps, not inferred execution progress. */
export default function ModelingMethodFlow({ kind, compact = false, onAdd }: { kind: keyof typeof STEPS; compact?: boolean; onAdd?: () => void }) {
  return <section className={`mo-method-flow mo-method-flow-${kind}${compact ? ' mo-method-flow-compact' : ''}`} aria-label={kind === 'coach' ? '问数优化四步说明' : '公共视图规划四步说明'}>
    <ol>{STEPS[kind].map(([title, text], index) => <li key={title}>
      <div className="mo-step-top"><span className="mo-step-number" aria-hidden="true">0{index + 1}</span><div className="mo-step-title"><h3>{title}</h3>{index === 0 && kind === 'coach' && onAdd && <button type="button" className="mo-step-add" onClick={onAdd}>去添加<Icon name="chevron" size="sm" /></button>}</div><span className="mo-step-line" aria-hidden="true" /></div>
      <p>{text}</p>
    </li>)}</ol>
  </section>;
}
