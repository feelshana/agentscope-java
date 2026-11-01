import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { KnowledgeBreadcrumb } from '../../components/KnowledgeWorkspace';
import Icon from '../../components/Icon';
import DraftBar from '../../components/modeling/DraftBar';
import { changeBusinessTerm, listBusinessTerms } from '../../api/semantic';
import type { BusinessTerm, BusinessTerms, TermChange, TermStatus } from '../../api/semantic';
import { DatasetGroup, listGroups } from '../../api/datasets';
import { getModelingWorkflow } from '../../api/modelingWorkflow';
import type { ModelingWorkflow } from '../../api/modelingWorkflow';
import './SemanticConfigPage.css';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

const STATUS: Record<TermStatus, string> = {
  PUBLISHED: '已发布', ADDED: '新增未发布', MODIFIED: '修改未发布', DELETED: '删除未发布',
};

/** Editing state of one row: the structured fields, or the raw text of a free-form entry. */
interface Draft { original?: string; free: boolean; term: string; synonyms: string; explanation: string; text: string }
const emptyDraft: Draft = { free: false, term: '', synonyms: '', explanation: '', text: '' };

/**
 * spec100 业务术语: the knowledge base's official Naming conventions (knowledge/rules/naming.md).
 * Edits enter the draft and take effect for querying after a publish from the same draft bar as
 * modeling; QUERY users see the published version read-only.
 */
export default function SemanticConfigPage() {
  const navigate = useNavigate();
  const [groups, setGroups] = useState<DatasetGroup[]>([]);
  const [groupId, setGroupId] = useState('');
  const [data, setData] = useState<BusinessTerms | null>(null);
  const [workflow, setWorkflow] = useState<ModelingWorkflow | null>(null);
  const [editing, setEditing] = useState<Draft | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    listGroups(controller.signal)
      .then(gs => { if (!controller.signal.aborted) { setGroups(gs); setGroupId(prev => prev || (gs[0]?.id ?? '')); } })
      .catch(e => { if (!controller.signal.aborted) setError(message(e)); });
    return () => controller.abort();
  }, []);

  const request = useRef<AbortController | null>(null);
  const refresh = useCallback(async () => {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    if (!groupId) { setData(null); return; }
    setLoading(true);
    try {
      const terms = await listBusinessTerms(groupId, controller.signal);
      if (controller.signal.aborted) return;
      setData(terms); setError(null);
      if (terms.editable) getModelingWorkflow(groupId).then(w => { if (!controller.signal.aborted) setWorkflow(w); }).catch(() => undefined);
      else setWorkflow(null);
    } catch (e) { if (!controller.signal.aborted) setError(message(e)); }
    finally { if (!controller.signal.aborted) setLoading(false); }
  }, [groupId]);
  useEffect(() => { void refresh(); return () => request.current?.abort(); }, [refresh]);
  // Publishing from the draft bar, or a modeling turn elsewhere, changes what is online.
  useEffect(() => {
    const update = (event: Event) => { if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) void refresh(); };
    window.addEventListener('modeling:updated', update);
    return () => window.removeEventListener('modeling:updated', update);
  }, [groupId, refresh]);

  const submit = async (change: TermChange, confirmText?: string) => {
    if (confirmText && !window.confirm(confirmText)) return;
    setBusy(true); setError(null);
    try {
      setData(await changeBusinessTerm(groupId, change));
      setEditing(null);
      window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  };
  const save = () => {
    if (!editing) return;
    const fields = editing.free ? { text: editing.text } : { term: editing.term, synonyms: editing.synonyms, explanation: editing.explanation };
    void submit({ original: editing.original, ...fields });
  };
  const startEdit = (t: BusinessTerm) => setEditing(t.structured
    ? { original: t.text, free: false, term: t.term ?? '', synonyms: t.synonyms.join('、'), explanation: t.explanation ?? '', text: '' }
    : { original: t.text, free: true, term: '', synonyms: '', explanation: '', text: t.text });

  const editable = !!data?.editable;
  const rows = data?.rows ?? [];
  const pending = rows.filter(r => r.status !== 'PUBLISHED').length;
  const canSave = !!editing && !busy && (editing.free ? editing.text.trim() !== '' : editing.term.trim() !== '' && editing.explanation.trim() !== '');

  const editor = editing && <tr className="terms-editor">
    {editing.free ? <td colSpan={4}>
      <textarea className="da-input" rows={3} aria-label="条目内容" value={editing.text} onChange={e => setEditing({ ...editing, text: e.target.value })} />
      <div className="da-small">该条目不是"术语 = 解释"的格式，按原文编辑。</div>
    </td> : <>
      <td><input className="da-input" aria-label="术语" placeholder="业务名词，50 字以内" maxLength={50} value={editing.term} onChange={e => setEditing({ ...editing, term: e.target.value })} /></td>
      <td><textarea className="da-input" aria-label="业务定义" rows={2} placeholder="含义；能写出对应的表、字段或条件更好，500 字以内" maxLength={500} value={editing.explanation} onChange={e => setEditing({ ...editing, explanation: e.target.value })} /></td>
      <td><input className="da-input" aria-label="同义词" placeholder="多个用顿号或逗号分隔" value={editing.synonyms} onChange={e => setEditing({ ...editing, synonyms: e.target.value })} /></td>
      <td />
    </>}
    <td><span className="terms-row-actions">
      <button className="terms-text-button" disabled={!canSave} onClick={save}>{busy ? '保存中…' : '保存'}</button>
      <button className="terms-text-button" disabled={busy} onClick={() => setEditing(null)}>取消</button>
    </span></td>
  </tr>;

  return (
    <div className="kw-page kw-secondary-page terms-page">
      <KnowledgeBreadcrumb items={[{ label: '对话', to: '/chat' }, { label: '业务术语' }]} />
      <div className="terms-scroll"><div className="terms-content">
        <header className="terms-pagehead"><div className="kw-heading"><h1>业务术语</h1>
          <p>让同一个业务概念，始终对应同一种含义。</p></div>
          <div className="terms-head-actions">
            <button className="da-btn terms-refresh" title="刷新术语" aria-label="刷新术语" onClick={() => void refresh()} disabled={busy || loading}><Icon name="refresh" /></button>
            {editable && <button className="da-btn da-btn-primary" disabled={busy || !!editing}
              onClick={() => setEditing({ ...emptyDraft })}><Icon name="plus" size="sm" />添加术语</button>}
          </div>
        </header>
        {error && <div role="alert" className="terms-error">{error}</div>}
        <div className="terms-context">
          <div className="terms-knowledge"><Icon name="book" />
          <select value={groupId} aria-label="知识库" disabled={busy}
            onChange={e => { setGroupId(e.target.value); setEditing(null); setData(null); setWorkflow(null); }}>
            {groups.length === 0 && <option value="">（暂无知识库）</option>}
            {groups.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}
          </select>
          </div>
          {data && <span className={`terms-context-status${data.publishedVersion > 0 ? ' is-published' : ''}`}>{data.publishedVersion > 0 ? `线上 v${data.publishedVersion}` : '尚未发布'}</span>}
          {editable && pending > 0 && <span className="terms-context-status is-pending">{pending} 项修改待发布</span>}
          {data && !editable && <span className="terms-readonly">只读</span>}
          <span className="terms-count" role="status">{loading ? '加载中…' : `共 ${rows.length} 条术语`}</span>
        </div>
        {data && !editable && <p className="terms-permission">你对该知识库只有查询权限，只能查看已发布的术语。</p>}
        <div className="terms-table-scroll"><table className="terms-table" aria-label="业务术语" aria-busy={loading}>
          <thead><tr>
            <th style={{ width: '18%' }}>业务术语</th><th style={{ width: '36%' }}>业务定义</th><th style={{ width: '18%' }}>同义词</th>
            <th style={{ width: '15%' }}>状态</th><th style={{ width: '13%' }}>{editable ? '操作' : ''}</th>
          </tr></thead>
          <tbody>
            {editing && !editing.original && editor}
            {rows.map(t => editing?.original === t.text ? <React.Fragment key={t.text}>{editor}</React.Fragment> : (
              <tr key={`${t.status}:${t.text}`} className={`terms-row is-${t.status.toLowerCase()}`}>
                <td className="terms-name">
                  {t.structured ? t.term : <span className="da-small">（自由格式）</span>}</td>
                <td className="terms-definition">
                  {t.structured ? t.explanation : t.text}
                  {t.status === 'MODIFIED' && t.publishedText && <div className="terms-previous">线上：{t.publishedText}</div>}
                </td>
                <td><span className="terms-synonyms">
                  {t.synonyms.length ? t.synonyms.map(s => <span key={s}>{s}</span>) : <span className="terms-no-synonym">—</span>}</span></td>
                <td><span className={`terms-status is-${t.status.toLowerCase()}`}>{STATUS[t.status]}</span></td>
                <td>{editable && <span className="terms-row-actions">
                  {t.status === 'DELETED'
                    ? <button className="terms-text-button" disabled={busy || !!editing} onClick={() => void submit({ text: t.publishedText ?? t.text })}>恢复</button>
                    : <>
                      <button className="terms-text-button" aria-label={`编辑术语：${t.term ?? t.text}`} disabled={busy || !!editing} onClick={() => startEdit(t)}>编辑</button>
                      <button className="terms-delete-button" title="删除术语" aria-label={`删除术语：${t.term ?? t.text}`} disabled={busy || !!editing} onClick={() => void submit({ original: t.text, delete: true }, '删除该术语？删除进入草稿，发布后生效。')}><Icon name="trash" size="sm" /></button>
                    </>}
                </span>}</td>
              </tr>
            ))}
            {!editing && rows.length === 0 && <tr><td colSpan={5} className="terms-empty">
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}><Icon name="file" size="sm" />
                {loading ? '正在加载业务术语…' : !groupId ? '请先选择上方知识库' : editable ? '暂无业务术语，添加术语来说明你的业务说法' : '该知识库还没有已发布的业务术语'}</span>
            </td></tr>}
          </tbody>
        </table></div>
        {editable && <div className="terms-draft">
          <DraftBar key={groupId} groupId={groupId} workflow={workflow} assistantActive={false} pendingConfirmations={0}
            terms={{ pending, editing: busy || !!editing }}
            onLocate={() => navigate(`/configure/modeling/${groupId}`)} onPublished={() => void refresh()} />
        </div>}
      </div></div>
    </div>
  );
}
