import { useEffect, useState, type MutableRefObject } from 'react';
import { createPortal } from 'react-dom';
import { updateSqlQaExample, type SqlQaExample } from '../../api/semanticModeling';
import Icon from '../Icon';
import ReadableCode from '../ReadableCode';
import SqlEditor from '../SqlEditor';
import { WorkspaceMenu } from '../KnowledgeWorkspace';
import './SqlQaExamplesPanel.css';

export default function SqlQaExampleDetail({ groupId, entry, onChanged, onRemove, leaveGuard, onContext, toolbarTarget }: {
  groupId: string; entry: SqlQaExample; onChanged: () => void;
  onRemove?: (entry: SqlQaExample) => void;
  leaveGuard: MutableRefObject<(() => boolean) | null>;
  onContext: (question: string) => void;
  toolbarTarget: HTMLElement | null;
}) {
  const [edit, setEdit] = useState<{ question: string; sql: string }>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const dirty = !!edit && (edit.question !== entry.question || edit.sql !== entry.sql);
  const startEdit = () => setEdit({ question: entry.question, sql: entry.sql });
  useEffect(() => {
    leaveGuard.current = () => !busy && (!dirty || window.confirm('有尚未保存的问答修改，是否放弃并切换？'));
    return () => { leaveGuard.current = null; };
  }, [busy, dirty, leaveGuard]);
  const run = async (operation: () => Promise<unknown>) => {
    setBusy(true);
    setError('');
    try { await operation(); setEdit(undefined); onChanged(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };
  return <section className={edit || !entry.sql ? 'sqa-detail' : 'sqa-detail sqa-readonly'} aria-label={entry.question}>
    {toolbarTarget && entry.editable && !edit && createPortal(<>
      {!entry.sql && <button type="button" className="da-btn" onClick={startEdit}>编辑问答示例</button>}
      <WorkspaceMenu label="问答更多操作" items={[{ label: '删除问答示例', icon: 'trash', danger: true,
        disabled: busy || !onRemove, onClick: () => onRemove?.(entry) }]} />
    </>, toolbarTarget)}
    {error && <p role="alert" className="mo-error">{error}</p>}
    {entry.message && <p className="da-small">{entry.message}</p>}
    {edit ? <>
      <label className="sqa-question-label">问题<input className="da-input" value={edit.question} disabled={busy} onChange={e => setEdit({ ...edit, question: e.target.value })} /></label>
      <SqlEditor autoFocus rows={8} value={edit.sql} disabled={busy} ariaLabel="编辑示例 SQL" onChange={sql => setEdit({ ...edit, sql })} />
      <div className="sqa-actions"><button type="button" className="da-btn" disabled={busy} onClick={() => setEdit(undefined)}>取消</button>
        <button type="button" className="da-btn da-btn-primary" disabled={busy || !edit.question.trim() || !edit.sql.trim()
          || (edit.question === entry.question && edit.sql === entry.sql)}
          onClick={() => void run(() => updateSqlQaExample(groupId, { source: entry.source, file: entry.file, ...edit }))}>{busy ? '保存中…' : '保存问答示例'}</button>
      </div>
    </> : entry.sql && <><ReadableCode text={entry.sql} language="sql" title="已确认的 SQL" fitHeight
        actions={entry.editable && <button type="button" onClick={startEdit}><Icon name="edit" size="sm" />编辑 SQL</button>} />
      <div className="qc-sql-foot"><span className="da-small">示例 SQL</span>
        <button type="button" className="sm-text-link" onClick={() => onContext(entry.question)}>核对规则与版本</button>
      </div>
    </>}
  </section>;
}
