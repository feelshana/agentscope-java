import React, { useCallback, useEffect, useRef, useState } from 'react';
import { KnowledgeBreadcrumb, WorkspaceMenu } from '../../components/KnowledgeWorkspace';
import WorkspaceDialog from '../../components/DataSourceDialog';
import Icon from '../../components/Icon';
import { toast } from '../../components/Toast';
import { createSemanticTerm, deleteSemanticTerm, listSemanticTerms, SemanticTerm, updateSemanticTerm } from '../../api/semantic';
import { DatasetGroup, listGroups } from '../../api/datasets';
import '../../secondary-pages.css';

const splitSynonyms = (value: string | null) => [...new Set((value ?? '').split(/[,，、]/).map(item => item.trim()).filter(Boolean))];

export default function SemanticConfigPage() {
  const [groups, setGroups] = useState<DatasetGroup[]>([]);
  const [groupId, setGroupId] = useState('');
  const [terms, setTerms] = useState<SemanticTerm[]>([]);
  const [groupsLoading, setGroupsLoading] = useState(true);
  const [loading, setLoading] = useState(false);
  const [groupError, setGroupError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [editor, setEditor] = useState<{ groupId: string; term: SemanticTerm | null } | null>(null);
  const [deleting, setDeleting] = useState<SemanticTerm | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [deletingBusy, setDeletingBusy] = useState(false);
  const deletePending = useRef(false);
  const active = useRef(false);
  const request = useRef<AbortController | null>(null);
  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);

  useEffect(() => {
    const controller = new AbortController();
    setGroupsLoading(true); setGroupError(null);
    listGroups(controller.signal, true).then(result => {
      if (controller.signal.aborted) return;
      setGroups(result); setGroupId(previous => result.some(group => group.id === previous) ? previous : result[0]?.id ?? '');
    }).catch(e => { if (!controller.signal.aborted) setGroupError(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (!controller.signal.aborted) setGroupsLoading(false); });
    return () => controller.abort();
  }, [attempt]);

  const refresh = useCallback(async () => {
    request.current?.abort();
    const controller = new AbortController(); request.current = controller;
    setError(null);
    if (!groupId) { setTerms([]); setLoading(false); return; }
    setLoading(true);
    try {
      const result = await listSemanticTerms(groupId, controller.signal);
      if (!controller.signal.aborted) setTerms(result);
    } catch (e) {
      if (!controller.signal.aborted) setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (!controller.signal.aborted) setLoading(false);
    }
  }, [groupId]);
  useEffect(() => { void refresh(); return () => request.current?.abort(); }, [refresh]);

  const selectedGroup = groups.find(group => group.id === groupId);
  const canManage = selectedGroup?.canManage === true;
  const needle = query.trim().toLocaleLowerCase();
  const filtered = terms.filter(term => [term.term, term.explanation, term.synonyms].some(value => value?.toLocaleLowerCase().includes(needle)));
  const pages = Math.max(1, Math.ceil(filtered.length / pageSize));
  const currentPage = Math.min(page, pages);
  const rows = filtered.slice((currentPage - 1) * pageSize, currentPage * pageSize);
  const failure = groupError || error;

  async function remove() {
    if (!deleting || !canManage || deletePending.current) return;
    deletePending.current = true; setDeletingBusy(true); setDeleteError(null);
    try {
      await deleteSemanticTerm(groupId, deleting.id);
      if (!active.current) return;
      setDeleting(null); toast('业务术语已删除', 'success'); await refresh();
    } catch (e) {
      if (active.current) setDeleteError(e instanceof Error ? e.message : String(e));
    } finally { deletePending.current = false; if (active.current) setDeletingBusy(false); }
  }

  return <div className="kw-page kw-secondary-page">
    <KnowledgeBreadcrumb items={[{ label: '对话', to: '/chat' }, { label: '业务术语' }]} />
    <div className="kw-scroll"><div className="kw-content">
      <div className="kw-pagehead"><div className="kw-heading"><h1>业务术语</h1><p>按知识库维护术语，供成员查阅。</p></div>
        <div className="kw-actions"><button className="da-btn da-btn-primary" disabled={!canManage || groupsLoading || !!groupError} onClick={() => setEditor({ groupId, term: null })}><Icon name="plus" size="sm" />添加术语</button></div>
      </div>
      {failure && <div className="secondary-alert" role="alert"><Icon name="warn" size="sm" /><span>{failure}</span><button className="da-btn da-btn-sm" onClick={() => groupError ? setAttempt(value => value + 1) : void refresh()}>重试</button></div>}
      <div className="kw-toolbar terms-toolbar"><label className="terms-kb-label" htmlFor="terms-knowledge-base">知识库</label>
        <select id="terms-knowledge-base" className="kw-select" value={groupId} disabled={groupsLoading || !groups.length} onChange={event => {
          request.current?.abort(); setGroupId(event.target.value); setTerms([]); setLoading(true); setError(null); setQuery(''); setPage(1); setEditor(null);
        }}>
          {!groups.length && <option value="">{groupsLoading ? '加载中…' : groupError ? '知识库加载失败' : '暂无可管理的知识库'}</option>}
          {groups.map(group => <option key={group.id} value={group.id}>{group.name}</option>)}
        </select><span className="kw-spacer" />
        <label className="kw-search"><Icon name="search" size="sm" /><input aria-label="搜索业务术语" placeholder="搜索业务术语" value={query} onChange={event => { setQuery(event.target.value); setPage(1); }} /></label>
      </div>
      <div className="kw-tablebox"><div className="kw-table-scroll" tabIndex={0} role="region" aria-label="业务术语表格，可横向滚动"><table className="kw-table kw-terms-table" aria-label="业务术语" aria-busy={loading || groupsLoading}>
        <thead><tr><th scope="col">业务术语</th><th scope="col">业务定义</th><th scope="col">同义词</th><th scope="col">操作</th></tr></thead>
        <tbody>{loading || groupsLoading ? <tr><td colSpan={4} className="kw-empty" role="status">正在加载业务术语…</td></tr>
          : failure ? <tr><td colSpan={4} className="kw-empty">暂时无法读取业务术语，请重试。</td></tr>
          : !groupId ? <tr><td colSpan={4} className="kw-empty">暂无可管理的知识库，请先创建或取得知识库管理权限。</td></tr>
          : !rows.length ? <tr><td colSpan={4} className="kw-empty">{needle ? '没有匹配的术语' : '暂无业务术语，添加术语来说明你的业务口径。'}</td></tr>
          : rows.map(term => <tr key={term.id}><td><strong>{term.term}</strong></td><td>{term.explanation || '—'}</td><td>{splitSynonyms(term.synonyms).join('、') || '—'}</td>
            <td>{canManage ? <div className="terms-row-actions"><button className="da-btn da-btn-ghost da-btn-sm" onClick={() => setEditor({ groupId, term })}><Icon name="edit" size="sm" />编辑</button>
              <WorkspaceMenu label={`${term.term}的更多操作`} items={[{ label: '删除术语', icon: 'trash', danger: true, onClick: () => { setDeleting(term); setDeleteError(null); } }]} /></div> : <span className="kw-source">只读</span>}</td></tr>)}
        </tbody>
      </table></div><div className="kw-tablebottom">当前知识库的术语 · 不跨库共用定义</div></div>
      {!failure && !groupsLoading && !loading && groupId && <div className="kw-pagination terms-pagination"><span>共 {filtered.length} 条{needle && `（全部 ${terms.length} 条）`}</span><span className="kw-spacer" />
        <select className="kw-select" aria-label="每页术语数" value={pageSize} onChange={event => { setPageSize(Number(event.target.value)); setPage(1); }}>{[10, 20, 50].map(size => <option key={size} value={size}>{size} 条 / 页</option>)}</select>
        <button className="da-btn da-btn-sm" aria-label="首页" disabled={currentPage === 1} onClick={() => setPage(1)}>首页</button>
        <button className="da-btn da-btn-sm" aria-label="上一页" disabled={currentPage === 1} onClick={() => setPage(value => Math.max(1, value - 1))}><span className="terms-chevron-back"><Icon name="chevron" size="sm" /></span></button>
        <span>{currentPage} / {pages}</span><button className="da-btn da-btn-sm" aria-label="下一页" disabled={currentPage === pages} onClick={() => setPage(value => Math.min(pages, value + 1))}><Icon name="chevron" size="sm" /></button>
        <button className="da-btn da-btn-sm" aria-label="末页" disabled={currentPage === pages} onClick={() => setPage(pages)}>末页</button>
      </div>}
      <div className="secondary-notice"><Icon name="info" size="sm" /><p>这里保存的术语不会自动改写已发布模型。需要影响问数口径时，请在建模对话中完善工程业务知识，再检查发布。</p></div>
    </div></div>
    {editor && <TermEditor key={`${editor.groupId}:${editor.term?.id ?? 'new'}`} groupId={editor.groupId} groupName={selectedGroup?.name ?? ''} term={editor.term} onClose={() => setEditor(null)} onSaved={() => { setEditor(null); toast('业务术语已保存', 'success'); void refresh(); }} />}
    {deleting && <WorkspaceDialog title="删除业务术语" description={deleting.term} onClose={() => setDeleting(null)} busy={deletingBusy}><div className="ds-dialog-body"><p>确认删除该词条？</p>{deleteError && <div className="secondary-alert" role="alert">{deleteError}</div>}</div>
      <footer className="ds-dialog-foot"><button className="da-btn" disabled={deletingBusy} onClick={() => setDeleting(null)}>取消</button><button className="da-btn da-btn-danger" disabled={deletingBusy} onClick={remove}>{deletingBusy ? '删除中…' : '确认删除'}</button></footer>
    </WorkspaceDialog>}
  </div>;
}

function TermEditor({ groupId, groupName, term, onClose, onSaved }: { groupId: string; groupName: string; term: SemanticTerm | null; onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState(term?.term ?? '');
  const [explanation, setExplanation] = useState(term?.explanation ?? '');
  const [synonyms, setSynonyms] = useState(term?.synonyms ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pending = useRef(false);
  const active = useRef(false);
  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);
  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (pending.current) return;
    if (!name.trim() || name.trim().length > 30 || explanation.length > 100) { setError('术语名称需为1–30字，业务定义不超过100字。'); return; }
    pending.current = true; setBusy(true); setError(null);
    const payload = { term: name.trim(), explanation, synonyms: splitSynonyms(synonyms).join(',') };
    try {
      if (term) await updateSemanticTerm(groupId, term.id, payload); else await createSemanticTerm(groupId, payload);
      if (active.current) onSaved();
    } catch (e) { if (active.current) setError(e instanceof Error ? e.message : String(e)); }
    finally { pending.current = false; if (active.current) setBusy(false); }
  }
  return <WorkspaceDialog title={term ? '编辑业务术语' : '添加业务术语'} description={`仅属于当前知识库：${groupName}。保存不会自动改写已发布模型。`} onClose={onClose} busy={busy}><form onSubmit={save}>
    <div className="ds-dialog-body"><fieldset className="ds-fields" disabled={busy}>
      <label className="ds-field ds-full">业务术语<input autoFocus value={name} maxLength={30} required onChange={event => setName(event.target.value)} placeholder="请输入业务名词，30字以内" /></label>
      <label className="ds-field ds-full">业务定义<textarea value={explanation} maxLength={100} rows={3} onChange={event => setExplanation(event.target.value)} placeholder="说明业务含义，100字以内" /></label>
      <label className="ds-field ds-full">同义词<input value={synonyms} onChange={event => setSynonyms(event.target.value)} placeholder="多个同义词用逗号分隔" /></label>
    </fieldset>{error && <div className="ds-feedback ds-failed" role="alert">{error}</div>}</div>
    <footer className="ds-dialog-foot"><button type="button" className="da-btn" disabled={busy} onClick={onClose}>取消</button><button type="submit" className="da-btn da-btn-primary" disabled={busy || !name.trim()}>{busy ? '保存中…' : '保存'}</button></footer>
  </form></WorkspaceDialog>;
}
