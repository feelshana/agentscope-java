import type { ModelingWorkflow } from '../../api/modelingWorkflow';

const STAGE: Record<ModelingWorkflow['stage'], string> = {
  DATA_PREPARATION: '数据准备', MODELING: '对话建模', VALIDATION: '模型工程检查',
  CONFIRMATION: '业务确认', PUBLICATION: '待发布', COMPLETE: '已发布，可继续完善',
};

export default function ModelingWorkflowGuide({ workflow, error, busy, onNext, hideModelAction = false, hideNextAction = false }: {
  workflow: ModelingWorkflow | null;
  error: string | null;
  busy: boolean;
  onNext: (type: string) => void;
  hideModelAction?: boolean;
  hideNextAction?: boolean;
}) {
  if (!workflow) return <div className="da-card" role={error ? 'alert' : 'status'}>
    {error ? `建模进度加载失败：${error}。请刷新后再操作。` : '正在读取建模进度…'}
  </div>;
  const summary = workflow.questionSummary;
  return <div className="da-card" style={{ padding: 16, flexShrink: 0 }}>
    <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
      <strong>当前：{STAGE[workflow.stage]}</strong>
      <span className="da-badge">{workflow.queryAvailable ? `可问数 · 已发布 v${workflow.publishedVersion}` : '基础模型尚不可问数'}</span>
      {workflow.draftChanged && <span className="da-badge">有未发布变更</span>}
    </div>
    <p className="da-small">{workflow.nextAction.message}</p>
    {workflow.queryAvailable && workflow.draftChanged && <p className="da-small">
      问数继续使用 v{workflow.publishedVersion}；当前草稿只用于建模与验证，发布后才生效。
    </p>}
    <div className="da-small" style={{ marginBottom: 10 }}>
      工程校验：{workflow.engineeringStatus === 'PASSED' ? '通过' : workflow.engineeringStatus === 'FAILED' ? '未通过' : '待校验'}；
      可选问题已确认：{summary.confirmed}/{summary.total}；待审阅：{summary.awaitingConfirmation}（不阻碍发布）
    </div>
    {workflow.blockers.length > 0 && <details style={{ marginBottom: 10 }}>
      <summary>待处理事项（{workflow.blockers.length}）</summary>
      <ul>{workflow.blockers.map((item, i) => <li key={`${item.code}-${item.questionId}-${i}`}>{item.message}</li>)}</ul>
    </details>}
    {!hideNextAction && !(hideModelAction && ['MODEL', 'ADD_QUESTIONS'].includes(workflow.nextAction.type)) && <button className="da-btn da-btn-primary" disabled={busy} onClick={() => onNext(workflow.nextAction.type)}>
      {workflow.nextAction.label}
    </button>}
    {!hideModelAction && !hideNextAction && workflow.queryAvailable && workflow.nextAction.type !== 'QUERY' && <button className="da-btn" style={{ marginLeft: 8 }}
      onClick={() => onNext('QUERY')}>用已发布模型问数</button>}
  </div>;
}
