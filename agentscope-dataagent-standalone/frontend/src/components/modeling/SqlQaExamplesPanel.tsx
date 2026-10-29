import { useEffect, useState } from 'react';
import { deleteSqlQaExample, listSqlQaExamples, updateSqlQaExample } from '../../api/semanticModeling';
import type { SqlQaExample } from '../../api/semanticModeling';
import SqlEditor from '../SqlEditor';

export default function SqlQaExamplesPanel({ groupId }: { groupId: string }) {
  const [items, setItems] = useState<SqlQaExample[]>([]);
  const [selected, setSelected] = useState<SqlQaExample | null>(null);
  const [question, setQuestion] = useState('');
  const [sql, setSql] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const refresh = async () => { try { const next = await listSqlQaExamples(groupId); setItems(next); if (!selected && next[0]) select(next[0]); } catch (e) { setError(e instanceof Error ? e.message : String(e)); } };
  const select = (item: SqlQaExample) => { setSelected(item); setQuestion(item.question); setSql(item.sql); setError(''); };
  useEffect(() => { void refresh(); }, [groupId]);
  const save = async () => {
    if (!selected) return;
    setSaving(true); setError('');
    try { const next = await updateSqlQaExample(groupId, { source: selected.source, file: selected.file, question, sql }); setItems(all => all.map(x => x.id === next.id ? next : x)); select(next); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); } finally { setSaving(false); }
  };
  const remove = async () => {
    if (!selected || !window.confirm(`确认删除问答对“${selected.question}”？`)) return;
    setSaving(true); setError('');
    try { await deleteSqlQaExample(groupId, selected.source, selected.file); const next = items.filter(x => x.id !== selected.id); setItems(next); if (next[0]) select(next[0]); else setSelected(null); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); } finally { setSaving(false); }
  };
  return <div className="da-card" style={{ marginTop: 12 }}>
    <h3>SQL 问答对</h3><p className="da-small">统一管理建模生成并发布的样例，以及问数点赞后保存的样例。</p>
    {error && <div role="alert" className="da-alert da-alert-error">{error}</div>}
    {items.length === 0 ? <div className="da-small">当前知识库暂无 SQL 问答对。</div> : <div style={{ display: 'flex', gap: 12 }}>
      <div style={{ width: 260, maxHeight: 360, overflowY: 'auto' }}>{items.map(item => <button key={item.id} className={`da-btn ${selected?.id === item.id ? 'da-btn-primary' : ''}`} style={{ display: 'block', width: '100%', textAlign: 'left', marginBottom: 4 }} onClick={() => select(item)}><strong>{item.question}</strong><br /><span className="da-small">{item.source === 'liked' ? '点赞保存' : '建模样例'} · {item.message}</span></button>)}</div>
      {selected && <div style={{ flex: 1, minWidth: 0 }}><label>问题<input className="da-input" value={question} onChange={e => setQuestion(e.target.value)} /></label><div style={{ margin: '8px 0' }}><SqlEditor rows={12} value={sql} onChange={setSql} /></div><button className="da-btn da-btn-primary" disabled={saving} onClick={() => void save()}>{saving ? '保存中…' : '保存问答对'}</button>{' '}<button className="da-btn" disabled={saving} onClick={() => void remove()}>删除问答对</button></div>}
    </div>}
  </div>;
}
