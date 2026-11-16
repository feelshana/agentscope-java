import { useCallback, useEffect, useRef, useState } from 'react';
import { deleteSqlQaExample, listSqlQaExamples, updateSqlQaExample } from '../../api/semanticModeling';
import type { SqlQaExample } from '../../api/semanticModeling';
import { getModelingQuestions } from '../../api/modelingQuestions';
import SqlEditor from '../SqlEditor';
import ReadableCode from '../ReadableCode';
import Icon from '../Icon';
import './SqlQaExamplesPanel.css';

export default function SqlQaExamplesPanel({ groupId }: { groupId: string }) {
  const [items, setItems] = useState<SqlQaExample[]>([]);
  const [selected, setSelected] = useState<SqlQaExample | null>(null);
  const [question, setQuestion] = useState('');
  const [sql, setSql] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(true);
  const generation = useRef(0);
  // spec112: a modeling pair whose question awaits review is not shipped as an example until then.
  const [held, setHeld] = useState<Record<string, string>>({});
  const show = useCallback((item: SqlQaExample | null) => { setSelected(item); setQuestion(item?.question ?? ''); setSql(item?.sql ?? ''); setError(''); }, []);
  const refresh = useCallback(async () => {
    const current = ++generation.current;
    setLoading(true); setError('');
    try {
      const [next, questions] = await Promise.all([listSqlQaExamples(groupId), getModelingQuestions(groupId).catch(() => [])]);
      if (current !== generation.current) return;
      setItems(next); show(next[0] ?? null);
      setHeld(Object.fromEntries(questions.filter(r => r.status === 'REVIEW').map(r => [`${r.question.id}.md`, (r.reviewReasons ?? []).join('；')])));
    } catch (e) { if (current === generation.current) setError(e instanceof Error ? e.message : String(e)); }
    finally { if (current === generation.current) setLoading(false); }
  }, [groupId, show]);
  const heldNote = (item: SqlQaExample) => item.source === 'modeling' ? held[item.file] : undefined;
  const dirty = !!selected?.editable && (question !== selected.question || sql !== selected.sql);
  const select = (item: SqlQaExample) => {
    if (selected?.id === item.id || saving) return;
    if (dirty && !window.confirm('有尚未保存的问答修改，是否放弃并切换？')) return;
    show(item);
  };
  useEffect(() => { setItems([]); show(null); void refresh(); return () => { generation.current++; }; }, [refresh, show]);
  const save = async () => {
    if (!selected || !selected.editable) return;
    const current = generation.current;
    setSaving(true); setError('');
    try { const next = await updateSqlQaExample(groupId, { source: selected.source, file: selected.file, question, sql }); if (current !== generation.current) return; setItems(all => all.map(x => x.id === next.id ? next : x)); show(next); }
    catch (e) { if (current === generation.current) setError(e instanceof Error ? e.message : String(e)); } finally { if (current === generation.current) setSaving(false); }
  };
  const remove = async () => {
    if (!selected || !selected.editable || !window.confirm(`确认删除问答对“${selected.question}”？`)) return;
    const current = generation.current;
    setSaving(true); setError('');
    try { await deleteSqlQaExample(groupId, selected.source, selected.file); if (current !== generation.current) return; const next = items.filter(x => x.id !== selected.id); setItems(next); show(next[0] ?? null); }
    catch (e) { if (current === generation.current) setError(e instanceof Error ? e.message : String(e)); } finally { if (current === generation.current) setSaving(false); }
  };
  return <div className="sqa" aria-label="问答样例">
    <header className="sqa-head"><span className="sqa-total">{loading ? '读取中…' : `${items.length} 条问答`}</span><p>查看建模中确认的问答，以及问数点赞保存的样例。</p></header>
    {error && <div role="alert" className="da-alert da-alert-error">{error}</div>}
    {loading ? <p className="sqa-empty" role="status">正在读取已确认问答…</p> : !items.length ? <div className="sqa-empty">{error ? <button type="button" className="da-btn" onClick={() => void refresh()}>重试</button> : '当前知识库暂无 SQL 问答对。'}</div> : <div className="sqa-layout">
      <nav className="sqa-list" aria-label="问答样例列表">{items.map(item => <button key={item.id} type="button" className={`sqa-item ${selected?.id === item.id ? 'is-selected' : ''}`} aria-current={selected?.id === item.id ? 'true' : undefined} disabled={saving} onClick={() => select(item)}>
        <span className={`sqa-source-icon sqa-source-${item.source}`}><Icon name={item.source === 'liked' ? 'thumbsUp' : 'check'} size="sm" /></span><span className="sqa-item-copy"><strong>{item.question}</strong><span className="sqa-item-meta"><span>{item.source === 'liked' ? '点赞保存' : '建模确认'}</span><span className={heldNote(item) !== undefined ? 'sqa-review' : ''}>{heldNote(item) !== undefined ? '待复核' : item.editable ? '可编辑' : '只读'}</span></span></span>
      </button>)}</nav>
      {selected && <section className="sqa-detail" aria-label="问答样例详情"><header className="sqa-detail-head"><span className="sqa-source-label">{selected.source === 'liked' ? '点赞保存的问答' : '建模确认的问答'}</span><span className="sqa-permission"><Icon name={selected.editable ? 'edit' : 'shield'} size="sm" />{selected.editable ? '可编辑' : '只读查看'}</span></header>
        {heldNote(selected) !== undefined && <div role="status" className="sqa-note sqa-review-note"><Icon name="warn" size="sm" /><span>
        口径变更待复核：{heldNote(selected) || '它用到的模型或规则有变化'}。请在建模对话的"试问"中复核，复核前不会作为问数示例发布。</span></div>}
        {selected.editable ? <><label className="sqa-question-label">问题<input className="da-input" disabled={saving} value={question} onChange={e => setQuestion(e.target.value)} /></label><div className="sqa-editor"><SqlEditor rows={8} disabled={saving} value={sql} onChange={setSql} /></div><footer className="sqa-actions"><span>{dirty ? '有未保存的修改' : '修改会保存到草稿'}</span><button className="da-btn sqa-delete" disabled={saving} onClick={() => void remove()}><Icon name="trash" size="sm" />删除问答对</button><button className="da-btn da-btn-primary" disabled={saving || !dirty || !question.trim() || !sql.trim()} onClick={() => void save()}>{saving ? '保存中…' : '保存问答对'}</button></footer></>
          : <><h3 className="sqa-question">{selected.question}</h3>{selected.message && <div role="status" className="sqa-note"><Icon name="info" size="sm" /><span>{selected.message}</span></div>}<ReadableCode text={selected.sql} language="sql" title="已确认的 SQL" /></>}
      </section>}
    </div>}
  </div>;
}
