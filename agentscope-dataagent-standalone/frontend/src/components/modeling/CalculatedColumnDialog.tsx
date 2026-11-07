import { useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import type { EditorView } from '@codemirror/view';
import type { DraftItemEdit } from '../../api/draftReview';
import Icon from '../Icon';
import CalculatedExpressionEditor, { insertExpressionReference } from './CalculatedExpressionEditor';
import type { ExpressionReference } from './CalculatedExpressionEditor';
import { splitList } from './ruleText';
import type { SemanticColumn, SemanticModel, SemanticRelation } from './semanticSnapshot';
import { ROLE_HINT, ROLE_LABEL, roleOf } from './workbenchLayout';
import type { Role } from './workbenchLayout';
import './CalculatedColumnDialog.css';

const RESULT_TYPES = ['DECIMAL', 'BIGINT', 'VARCHAR', 'DATE', 'TIMESTAMP', 'BOOLEAN'];
const FUNCTIONS = [
  { name: 'COALESCE', signature: 'COALESCE(value, 0)', description: '为空时返回默认值', tail: ', 0)' },
  { name: 'NULLIF', signature: 'NULLIF(value, 0)', description: '两个值相等时返回空值', tail: ', 0)' },
  { name: 'ROUND', signature: 'ROUND(value, 2)', description: '按指定位数四舍五入', tail: ', 2)' },
  { name: 'ABS', signature: 'ABS(value)', description: '取绝对值', tail: ')' },
  { name: 'LOWER', signature: 'LOWER(text)', description: '转换为小写', tail: ')' },
  { name: 'UPPER', signature: 'UPPER(text)', description: '转换为大写', tail: ')' },
  { name: 'TRIM', signature: 'TRIM(text)', description: '去除两端空格', tail: ')' },
  { name: 'LENGTH', signature: 'LENGTH(text)', description: '返回字符串长度', tail: ')' },
];
const FUNCTION_REFERENCES: ExpressionReference[] = FUNCTIONS.map(f => ({
  key: f.name, label: f.signature, detail: f.description, prefix: `${f.name}(`, tail: f.tail,
}));

export default function CalculatedColumnDialog({
  model, column, relations, busy, blocked, feedback, onClose, onSave,
}: {
  model: SemanticModel;
  column?: SemanticColumn;
  relations: SemanticRelation[];
  busy: boolean;
  blocked: string | null;
  feedback: ReactNode;
  onClose: () => void;
  onSave: (edit: DraftItemEdit) => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const nameInput = useRef<HTMLInputElement>(null);
  const sqlEditor = useRef<EditorView | null>(null);
  const titleId = useId();
  const [name, setName] = useState(column?.name ?? '');
  const [displayName, setDisplayName] = useState(column?.displayName ?? '');
  const [role, setRole] = useState<Role | ''>(column?.semanticType && Object.prototype.hasOwnProperty.call(ROLE_LABEL, column.semanticType)
    ? column.semanticType as Role : '');
  const initialType = column?.type || 'DECIMAL';
  const [resultType, setResultType] = useState(initialType);
  const [initialExpression] = useState(column?.expression ?? '');
  const [expression, setExpression] = useState(initialExpression);
  const [description, setDescription] = useState(column?.description ?? '');
  const [synonyms, setSynonyms] = useState((column?.synonyms ?? []).join('、'));
  const [library, setLibrary] = useState<'fields' | 'functions'>('fields');
  const [query, setQuery] = useState('');
  const creating = !column;
  const disabled = busy || !!blocked;
  const inferredRole = roleOf(model, {
    name: name.trim(), type: resultType, primaryKey: !!column?.primaryKey, calculated: true, description: null, expression: null,
  }, relations).role;
  const fields = library === 'fields';
  const searchLabel = fields ? '搜索字段名或显示名' : '搜索函数名或用途';
  const keyword = query.trim().toLowerCase();
  const fieldReferences = useMemo<ExpressionReference[]>(() => model.columns.filter(c => c.name !== column?.name).map(c => ({
    key: c.name, label: c.displayName || c.name, detail: c.displayName && c.displayName !== c.name ? c.name : null,
    kind: c.calculated ? '衍生' : c.type, prefix: `"${c.name.replace(/"/g, '""')}"`,
  })), [model.columns, column?.name]);
  const completions = useMemo(() => [...fieldReferences, ...FUNCTION_REFERENCES], [fieldReferences]);
  const references = (fields ? fieldReferences : FUNCTION_REFERENCES).filter(reference =>
    [reference.label, reference.detail].some(value => value?.toLowerCase().includes(keyword)));

  useLayoutEffect(() => {
    const node = dialog.current;
    if (!node) return;
    const workbench = node.closest('.sw');
    const drawer = node.closest('.sw-drawer');
    let observer: ResizeObserver | undefined;
    if (workbench && drawer) {
      const align = () => {
        const viewportWidth = document.documentElement.clientWidth;
        const left = Math.max(0, workbench.getBoundingClientRect().left);
        const right = Math.min(viewportWidth, drawer.getBoundingClientRect().left);
        const fitsBesideDrawer = right - left >= 720;
        node.style.setProperty('--calc-area-left', fitsBesideDrawer ? `${left}px` : '0px');
        node.style.setProperty('--calc-area-right', fitsBesideDrawer ? `${viewportWidth - right}px` : '0px');
      };
      align();
      observer = new ResizeObserver(align);
      observer.observe(document.documentElement);
      observer.observe(workbench);
      observer.observe(drawer);
    }
    node.showModal();
    if (creating) nameInput.current?.focus();
    else if (sqlEditor.current) {
      const view = sqlEditor.current;
      view.dispatch({ selection: { anchor: view.state.doc.length } });
      view.focus();
    }
    return () => {
      observer?.disconnect();
      node.close();
    };
  }, [creating]);

  function close() {
    if (busy) return;
    dialog.current?.close();
    onClose();
  }

  return <dialog ref={dialog} className="calc-column-dialog" aria-labelledby={titleId}
    onKeyDown={event => { if (event.key === 'Escape') event.stopPropagation(); }}
    onCancel={event => { event.preventDefault(); close(); }}>
    <form className="calc-column-form" onSubmit={event => {
      event.preventDefault();
      if (disabled || !name.trim() || !expression.trim()) return;
      onSave({
        category: 'COLUMN', model: model.name, column: creating ? name.trim() : column.name,
        create: creating || undefined, displayName, description, synonyms: splitList(synonyms),
        semanticType: role || (column?.semanticType ? '' : undefined), columnType: resultType, expression,
      });
    }}>
      <header className="calc-column-header">
        <span className="calc-column-symbol" aria-hidden="true">ƒ</span>
        <div className="calc-column-heading">
          <div className="sw-muted sw-xs">语义模型 / {model.name} / 字段</div>
          <h2 id={titleId}>{creating ? '新增衍生列' : '编辑衍生列'}</h2>
        </div>
        <button type="button" className="da-btn da-btn-sm" aria-label="关闭衍生列编辑器" disabled={busy} onClick={close}>
          <Icon name="close" size="sm" />
        </button>
      </header>

      <div className="calc-column-body">
        <section className="calc-column-main" aria-label="衍生列定义">
          <div className="calc-column-meta">
            <label className="sw-fld"><span>字段名</span>
              <input ref={nameInput} className="da-input sw-mono" value={name} required readOnly={!creating}
                disabled={disabled} spellCheck={false} placeholder="例如 net_revenue" onChange={event => setName(event.target.value)} />
            </label>
            <label className="sw-fld"><span>显示名</span>
              <input className="da-input" value={displayName} disabled={disabled} placeholder="例如 净销售额（可不填）"
                onChange={event => setDisplayName(event.target.value)} />
            </label>
            <label className="sw-fld"><span>语义角色</span>
              <select className="da-input" value={role} disabled={disabled} onChange={event => setRole(event.target.value as Role | '')}>
                <option value="">自动 · {ROLE_LABEL[inferredRole]}</option>
                {Object.entries(ROLE_LABEL).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
              </select>
              <span className="sw-hint">{ROLE_HINT[role || inferredRole]}</span>
            </label>
            <label className="sw-fld"><span>结果类型</span>
              <select className="da-input sw-mono" value={resultType} disabled={disabled} onChange={event => setResultType(event.target.value)}>
                {!RESULT_TYPES.includes(initialType) && <option value={initialType}>{initialType}</option>}
                {RESULT_TYPES.map(type => <option key={type} value={type}>{type}</option>)}
              </select>
            </label>
          </div>
          <div className="sw-fld">
            <span>SQL 表达式</span>
            <CalculatedExpressionEditor initialValue={initialExpression} onChange={setExpression} disabled={disabled}
              references={completions} editorRef={sqlEditor} />
            <span className="sw-hint">输入字段名或显示名即可补全，也可从右侧插入。Ctrl + Z 可撤销。</span>
          </div>
          <label className="sw-fld"><span>业务说明</span>
            <textarea className="da-input" rows={2} value={description} disabled={disabled} placeholder="业务含义、单位、取值说明（可不填）"
              onChange={event => setDescription(event.target.value)} />
          </label>
          <label className="sw-fld"><span>同义词</span>
            <input className="da-input" value={synonyms} disabled={disabled} placeholder="用顿号分隔（可不填）"
              onChange={event => setSynonyms(event.target.value)} />
          </label>
        </section>

        <section className="calc-column-library" aria-label="表达式引用">
          <h3>表达式引用</h3>
          <div className="sw-seg" role="group" aria-label="引用类型">
            <button type="button" className={fields ? 'on' : ''} aria-pressed={fields}
              onClick={() => { setLibrary('fields'); if (!fields) setQuery(''); }}>引用字段</button>
            <button type="button" className={fields ? '' : 'on'} aria-pressed={!fields}
              onClick={() => { setLibrary('functions'); if (fields) setQuery(''); }}>引用函数</button>
          </div>
          <input type="search" className="da-input" aria-label={searchLabel} placeholder={searchLabel}
            value={query} onChange={event => setQuery(event.target.value)}
            onKeyDown={event => { if (event.key === 'Enter' && !event.nativeEvent.isComposing) event.preventDefault(); }} />
          <div className="calc-column-library-caption sw-muted sw-xs">
            <span>{fields ? '当前模型' : '常用 SQL 函数'} · {references.length} 项</span>
            <span>{fields ? '插入字段到 SQL' : '插入函数到 SQL'}</span>
          </div>
          <ul className="calc-column-references" aria-label={fields ? '可引用字段' : '可引用函数'}>
            {references.map(reference => <li key={reference.key}>
              <button type="button" className="calc-column-reference" disabled={disabled}
                aria-label={`将 ${reference.label}${fields && reference.detail ? `（${reference.detail}）` : ''} 插入 SQL`}
                onClick={() => { if (sqlEditor.current) insertExpressionReference(sqlEditor.current, reference); }}>
                <span className="calc-column-reference-text">
                  <strong className={fields ? undefined : 'sw-mono'}>{reference.label}</strong>
                  {reference.detail && <small className={fields ? 'sw-mono' : undefined}>{reference.detail}</small>}
                </span>
                {reference.kind && <span className="calc-column-reference-type">{reference.kind}</span>}
                <span className="calc-column-insert" aria-hidden="true">＋</span>
              </button>
            </li>)}
          </ul>
          {references.length === 0 && <p className="sw-hint" role="status">{keyword ? '没有匹配结果' : '当前模型没有可引用的字段'}</p>}
        </section>
      </div>

      <footer className="calc-column-footer">
        {feedback}
        <div className="calc-column-actions">
          <span className="sw-hint">{blocked || (busy ? '正在校验衍生列…' : '通过 Wren 校验后写入当前草稿')}</span>
          <button type="button" className="da-btn" disabled={busy} onClick={close}>取消</button>
          <button type="submit" className="da-btn da-btn-primary" disabled={disabled || !name.trim() || !expression.trim()}>
            {busy ? '校验中…' : '保存到草稿'}
          </button>
        </div>
      </footer>
    </form>
  </dialog>;
}
