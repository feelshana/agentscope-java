import { useEffect, useState } from 'react';
import { generateAssetSql, getAsset, judgeAsset, saveVerifiedAssetQuestion, verifyAsset } from '../../api/semanticAssets';
import type { AssetDetail, Verdict, VerifyCase, VerificationEvidence } from '../../api/semanticAssets';
import { getModelingQuestions } from '../../api/modelingQuestions';
import type { QuestionReview } from '../../api/modelingQuestions';
import AssetActionDialog from './AssetActionDialog';
import ReadableCode from '../ReadableCode';

const LIMIT = 20;

export default function AssetVerificationPanel({ groupId, detail, published, locked, busy, run, onChange }: {
  groupId: string;
  detail: AssetDetail;
  published: boolean;
  locked: boolean;
  busy: string;
  run: (name: string, action: () => Promise<void>) => Promise<boolean>;
  onChange: (next: AssetDetail) => void;
}) {
  const { asset } = detail;
  const evidence = asset.review?.evidence ?? [];
  const [rows, setRows] = useState<VerifyCase[]>(() => evidence.map(({ question, sql }) => ({ question, sql })));
  const [question, setQuestion] = useState('');
  const [verdicts, setVerdicts] = useState<Record<string, Verdict>>({});
  const [editingCases, setEditingCases] = useState(!evidence.length);
  const [questions, setQuestions] = useState<QuestionReview[]>();
  const [savedCases, setSavedCases] = useState(new Set<string>());
  const [replacement, setReplacement] = useState<{ evidence: VerificationEvidence; current: QuestionReview }>();
  const [saveError, setSaveError] = useState('');
  useEffect(() => {
    if (published) return;
    let active = true;
    void getModelingQuestions(groupId).then(list => { if (active) setQuestions(list); }).catch(() => undefined);
    return () => { active = false; };
  }, [groupId, published]);

  const disabled = published || locked || !!busy;
  const done = !!asset.review?.verifiedAt;
  const canJudge = !editingCases && !done && evidence.length > 0 && evidence.every(c => !c.error && verdicts[c.key]);
  const saveQuestion = async (item: VerificationEvidence, current?: QuestionReview) => {
    setSaveError('');
    const ok = await run('save-question', async () => {
      try {
        const replacement = current ? { id: current.question.id, revision: current.revision } : undefined;
        const saved = await saveVerifiedAssetQuestion(groupId, asset, item.key, replacement);
        setSavedCases(previous => new Set([...previous, item.key]));
        setQuestions(previous => [...(previous ?? []).filter(q => q.question.id !== saved.question.id), saved]);
        window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
      } catch (error) {
        setSaveError(error instanceof Error ? error.message : String(error));
        void getModelingQuestions(groupId).then(setQuestions).catch(() => undefined);
        throw error;
      }
    });
    if (ok) setReplacement(undefined);
  };

  if (!asset.review && !published) return <p>完成用途核查后即可验证。</p>;
  return <section className="va-content" aria-label="问题验证">
    <div className="va-section-heading"><div><h4>问题验证</h4>
      <p>在{published ? '发布前的草稿' : '当前草稿'}上展开 SQL，核对取数逻辑。未执行查询。</p></div>
      {!published && evidence.length > 0 && !editingCases && <button type="button" className="da-btn da-btn-sm" disabled={disabled}
        onClick={() => { setRows(evidence.map(({ question, sql }) => ({ question, sql }))); setEditingCases(true); }}>
        {done ? '重新验证' : '调整验证问题'}</button>}
    </div>
    {published && evidence.length === 0 && <p className="da-small">{done ? '此版本保留了验证结论。' : '此版本尚无可查看的验证记录。'}</p>}
    {!published && editingCases && <>
      <div className="sa-ask">
        <input className="da-input" aria-label="验证问题" disabled={disabled || rows.length >= LIMIT} value={question}
          onChange={e => setQuestion(e.target.value)} placeholder="例如，2026 年 10 月足球通在订会员数是多少？" />
        <button type="button" className="da-btn da-btn-primary da-btn-sm" disabled={disabled || !question.trim() || rows.length >= LIMIT}
          onClick={() => void run('generate', async () => {
            const text = question.trim();
            const { sql } = await generateAssetSql(groupId, asset, text);
            setRows(previous => [...previous, { question: text, sql }]); setQuestion('');
          })}>{busy === 'generate' ? '生成中…' : '生成 SQL'}</button>
        <button type="button" className="da-btn da-btn-sm" disabled={disabled || rows.length >= LIMIT}
          onClick={() => { setRows(previous => [...previous, { question: question.trim(), sql: '' }]); setQuestion(''); }}>手写 SQL</button>
      </div>
      {!!questions?.some(q => q.status === 'CONFIRMED') && <details className="qc-detail-fold">
        <summary>选用已确认问答</summary><div className="va-suggestions">
          {questions.filter(q => q.status === 'CONFIRMED').map(q => <button key={q.question.id} type="button" className="da-btn da-btn-sm"
            disabled={disabled || rows.length >= LIMIT} onClick={() => setQuestion(q.question.question)}>{q.question.question}</button>)}
        </div>
      </details>}
      {rows.map((row, index) => <div key={index} className="va-case">
        <div className="sa-case-head"><strong>{row.question || `SQL ${index + 1}`}</strong>
          <button type="button" className="da-btn da-btn-sm" disabled={disabled} onClick={() => setRows(previous => previous.filter((_, i) => i !== index))}>移除</button></div>
        <textarea className="da-input sm-code" rows={4} aria-label={`验证 SQL ${index + 1}`} disabled={disabled} spellCheck={false}
          value={row.sql} onChange={e => { const sql = e.target.value; setRows(previous => previous.map((item, i) => i === index ? { ...item, sql } : item)); }} />
      </div>)}
      {rows.length > 0 && <div className="va-actions">
        <small className="da-small">{rows.length} / {LIMIT} 条</small>
        {evidence.length > 0 && <button type="button" className="da-btn da-btn-sm" disabled={disabled} onClick={() => setEditingCases(false)}>取消调整</button>}
        <button type="button" className="da-btn da-btn-primary" disabled={disabled || rows.some(c => !c.sql.trim())}
          onClick={() => void run('verify', async () => {
            await verifyAsset(groupId, asset, rows);
            const next = await getAsset(groupId, asset);
            setVerdicts({}); setSavedCases(new Set()); setEditingCases(false); onChange(next);
          })}>{busy === 'verify' ? '展开中…' : '展开 SQL'}</button>
      </div>}
    </>}
    {(!editingCases || published) && evidence.map((item, index) => {
      const verdict = done ? asset.review?.verdicts[item.key] : verdicts[item.key];
      const current = questions?.find(q => q.question.question.trim() === item.question);
      const saved = savedCases.has(item.key) || (current?.status === 'CONFIRMED' && current.validation?.origin === 'asset-verify'
        && current.question.sql?.trim() === item.sql.trim());
      return <article className="va-case" key={item.key}>
        <h5>{item.question || `SQL ${index + 1}`}</h5>
        <details className="qc-detail-fold"><summary>查看原 SQL</summary><ReadableCode text={item.sql} language="sql" title="原 SQL" /></details>
        {item.error ? <p className="sm-issue" role="alert">{item.error}</p> : <>
          {item.expanded && <ReadableCode text={item.expanded} language="sql" title="展开后的 SQL" />}
          {done ? <p className="va-case-verdict">{verdict === 'EXPECTED' ? 'SQL 符合预期' : '核对发现问题'}</p> : !published &&
            <div className="sa-verdict" role="radiogroup" aria-label={`判定 ${item.question || index + 1}`}>
              {(['EXPECTED', 'PROBLEM'] as const).map(choice => <label key={choice}>
                <input type="radio" name={item.key} disabled={disabled} checked={verdict === choice}
                  onChange={() => setVerdicts(previous => ({ ...previous, [item.key]: choice }))} />
                {choice === 'EXPECTED' ? 'SQL 符合预期' : '有问题'}
              </label>)}
            </div>}
          {!published && done && asset.review?.passed && verdict === 'EXPECTED' && item.question && <div className="va-actions">
            {saved ? <span role="status">已保存为问答</span>
              : <button type="button" className="da-btn da-btn-sm" disabled={disabled}
                onClick={() => {
                  setSaveError('');
                  if (current) setReplacement({ evidence: item, current });
                  else void saveQuestion(item);
                }}>{current ? '替换同名问答' : '保存为问答'}</button>}
          </div>}
        </>}
      </article>;
    })}
    {!published && !editingCases && !done && evidence.length > 0 && <div className="va-actions">
      <small className="da-small">{evidence.some(item => item.error) ? '请调整有错误的 SQL，再重新展开。' : '请逐题给出判定。'}</small>
      <button type="button" className="da-btn da-btn-primary" disabled={disabled || !canJudge}
        onClick={() => void run('verdict', async () => onChange(await judgeAsset(groupId, asset, verdicts)))}>
        {busy === 'verdict' ? '提交中…' : '提交判定'}
      </button>
    </div>}
    {replacement && <AssetActionDialog title="替换同名问答" busy={busy === 'save-question'} onClose={() => setReplacement(undefined)}
      actions={<><button type="button" className="da-btn" disabled={!!busy} onClick={() => setReplacement(undefined)}>取消</button>
        <button type="button" className="da-btn da-btn-primary" disabled={disabled || !!saveError}
          onClick={() => void saveQuestion(replacement.evidence, replacement.current)}>确认替换</button></>}>
      <strong>{replacement.current.question.question}</strong>
      <p>将以上面的验证 SQL 和视图口径替换草稿中的同名问答。发布知识库后生效。</p>
      {saveError && <p role="alert">{saveError}</p>}
    </AssetActionDialog>}
  </section>;
}
