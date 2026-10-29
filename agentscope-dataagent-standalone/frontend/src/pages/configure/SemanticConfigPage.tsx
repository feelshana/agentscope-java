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

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

const panelStyle: React.CSSProperties = {
  flex: 1, minHeight: 0, overflow: 'auto', padding: '34px 36px 48px', maxWidth: 1480, width: '100%', boxSizing: 'border-box', margin: '0 auto',
};
const linkBtn: React.CSSProperties = { background: 'transparent', border: 'none', color: 'var(--da-primary)', cursor: 'pointer', fontSize: 13, padding: 0 };

const STATUS: Record<TermStatus, { label: string; color: string; background: string }> = {
  PUBLISHED: { label: '已发布', color: 'var(--da-success)', background: 'rgba(22,163,74,.10)' },
  ADDED: { label: '新增未发布', color: 'var(--da-primary)', background: 'rgba(37,99,235,.10)' },
  MODIFIED: { label: '修改未发布', color: 'var(--da-warn)', background: 'rgba(234,179,8,.14)' },
  DELETED: { label: '删除未发布', color: 'var(--da-danger)', background: 'rgba(225,29,72,.08)' },
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
    try {
      const terms = await listBusinessTerms(groupId, controller.signal);
      if (controller.signal.aborted) return;
      setData(terms); setError(null);
      if (terms.editable) getModelingWorkflow(groupId).then(w => { if (!controller.signal.aborted) setWorkflow(w); }).catch(() => undefined);
      else setWorkflow(null);
    } catch (e) { if (!controller.signal.aborted) setError(message(e)); }
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

  const editor = editing && <tr>
    {editing.free ? <td colSpan={4}>
      <textarea className="da-input" rows={3} aria-label="条目内容" value={editing.text} onChange={e => setEditing({ ...editing, text: e.target.value })} />
      <div className="da-small">该条目不是"术语 = 解释"的格式，按原文编辑。</div>
    </td> : <>
      <td><input className="da-input" aria-label="术语" placeholder="业务名词，50 字以内" maxLength={50} value={editing.term} onChange={e => setEditing({ ...editing, term: e.target.value })} /></td>
      <td><textarea className="da-input" aria-label="业务定义" rows={2} placeholder="含义；能写出对应的表、字段或条件更好，500 字以内" maxLength={500} value={editing.explanation} onChange={e => setEditing({ ...editing, explanation: e.target.value })} /></td>
      <td><input className="da-input" aria-label="同义词" placeholder="多个用顿号或逗号分隔" value={editing.synonyms} onChange={e => setEditing({ ...editing, synonyms: e.target.value })} /></td>
      <td />
    </>}
    <td><span style={{ display: 'inline-flex', gap: 14 }}>
      <button style={{ ...linkBtn, opacity: canSave ? 1 : 0.5 }} disabled={!canSave} onClick={save}>{busy ? '保存中…' : '保存'}</button>
      <button style={linkBtn} onClick={() => setEditing(null)}>取消</button>
    </span></td>
  </tr>;

  return (
    <div className="kw-page kw-secondary-page">
      <KnowledgeBreadcrumb items={[{ label: '返回对话', to: '/chat' }, { label: '业务术语' }]} />
      <div style={panelStyle}>
        <div className="kw-pagehead"><div className="kw-heading"><h1>业务术语</h1>
          <p>统一业务名词与常用说法。术语保存在知识库的语义模型中，修改先进入草稿，发布后问数生效。</p></div></div>
        {error && <div role="alert" style={{ color: 'var(--da-danger)', background: 'rgba(225,29,72,.06)', border: '1px solid rgba(225,29,72,.25)', borderRadius: 8, padding: '8px 12px', fontSize: '0.85rem', marginBottom: 12 }}>{error}</div>}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14, flexWrap: 'wrap' }}>
          <span className="da-small" style={{ color: 'var(--da-text-3)', fontWeight: 600 }}>知识库</span>
          <select className="da-input" style={{ width: 240, padding: '4px 8px' }} value={groupId} aria-label="知识库"
            onChange={e => { setGroupId(e.target.value); setEditing(null); setData(null); }}>
            {groups.length === 0 && <option value="">（暂无知识库）</option>}
            {groups.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}
          </select>
          {data && <span className="da-small">线上版本 {data.publishedVersion > 0 ? `v${data.publishedVersion}` : '未发布'}{editable && pending > 0 ? ` · ${pending} 项修改未发布` : ''}</span>}
          {data && !editable && <span className="da-badge">只读</span>}
          <span style={{ flex: 1 }} />
          {editable && <button className="da-btn da-btn-primary" disabled={busy || !!editing}
            onClick={() => setEditing({ ...emptyDraft })}>添加术语</button>}
          <button className="da-btn da-btn-ghost da-btn-sm" title="刷新" onClick={() => void refresh()} disabled={busy}><Icon name="refresh" size="sm" /></button>
        </div>
        {data && !editable && <div className="da-small" style={{ marginBottom: 10 }}>你对该知识库只有查询权限，只能查看已发布的术语。</div>}
        <div className="kw-tablebox"><div className="kw-table-scroll"><table className="kw-table kw-terms-table">
          <thead><tr>
            <th style={{ width: '18%' }}>业务术语</th><th style={{ width: '40%' }}>业务定义</th><th style={{ width: '18%' }}>同义词</th>
            <th style={{ width: '10%' }}>状态</th><th style={{ width: '14%' }}>{editable ? '操作' : ''}</th>
          </tr></thead>
          <tbody>
            {editing && !editing.original && editor}
            {rows.map(t => editing?.original === t.text ? <React.Fragment key={t.text}>{editor}</React.Fragment> : (
              <tr key={`${t.status}:${t.text}`} style={t.status === 'DELETED' ? { opacity: 0.6 } : undefined}>
                <td style={{ fontWeight: 500, color: 'var(--da-text)', textDecoration: t.status === 'DELETED' ? 'line-through' : undefined }}>
                  {t.structured ? t.term : <span className="da-small">（自由格式）</span>}</td>
                <td style={{ whiteSpace: 'pre-wrap' }}>
                  {t.structured ? t.explanation : t.text}
                  {t.status === 'MODIFIED' && t.publishedText && <div className="da-small" style={{ marginTop: 4 }}>线上：{t.publishedText}</div>}
                </td>
                <td><span style={{ display: 'inline-flex', flexWrap: 'wrap', gap: 4 }}>
                  {t.synonyms.length ? t.synonyms.map(s => <span key={s} className="da-badge">{s}</span>) : '-'}</span></td>
                <td><span style={{ fontSize: 12, padding: '1px 6px', borderRadius: 4, whiteSpace: 'nowrap', color: STATUS[t.status].color, background: STATUS[t.status].background }}>{STATUS[t.status].label}</span></td>
                <td>{editable && <span style={{ display: 'inline-flex', gap: 14 }}>
                  {t.status === 'DELETED'
                    ? <button style={linkBtn} disabled={busy || !!editing} onClick={() => void submit({ text: t.publishedText ?? t.text })}>恢复</button>
                    : <>
                      <button style={linkBtn} disabled={busy || !!editing} onClick={() => startEdit(t)}>编辑</button>
                      <button style={linkBtn} disabled={busy || !!editing} onClick={() => void submit({ original: t.text, delete: true }, '删除该术语？删除进入草稿，发布后生效。')}>删除</button>
                    </>}
                </span>}</td>
              </tr>
            ))}
            {!editing && rows.length === 0 && <tr><td colSpan={5} style={{ textAlign: 'center', padding: '64px 20px', color: 'var(--da-text-muted)', fontSize: 13 }}>
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}><Icon name="file" size="sm" />
                {!groupId ? '请先选择上方知识库' : editable ? '暂无业务术语，添加术语来说明你的业务说法' : '该知识库还没有已发布的业务术语'}</span>
            </td></tr>}
          </tbody>
        </table></div></div>
        {editable && <div style={{ marginTop: 16 }}>
          <DraftBar groupId={groupId} workflow={workflow} assistantActive={false} pendingConfirmations={0}
            onLocate={() => navigate(`/configure/modeling/${groupId}`)} onPublished={() => void refresh()} />
          <div className="da-small" style={{ marginTop: 6 }}>草稿与建模共用：发布会一并发布该知识库草稿中的其他建模修改。</div>
        </div>}
      </div>
    </div>
  );
}
