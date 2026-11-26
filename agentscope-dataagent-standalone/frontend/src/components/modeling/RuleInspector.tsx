import { useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { MutableRefObject, ReactNode } from 'react';
import { listDocuments } from '../../api/draftReview';
import type { BusinessDocument, DraftItemEdit } from '../../api/draftReview';
import Icon from '../Icon';
import { WorkspaceMenu } from '../KnowledgeWorkspace';
import BusinessDocumentReader from './BusinessDocumentReader';
import { brokenRefs, canonicalText, filterText, normalizeLegacyTerm, readRule, ruleWorkspaceTitle, rulePath, ruleRefs, SECTIONS, splitList, termText } from './ruleText';
import type { RuleItem, RuleKind, RuleSection } from './ruleText';
import type { SemanticSnapshot } from './semanticSnapshot';

function fieldsOf(rule?: RuleItem) {
  return {
    scope: rule?.filter?.scope ?? rule?.canonical?.scope ?? '',
    condition: rule?.filter?.condition ?? '',
    exception: rule?.filter?.exception ?? '',
    term: rule?.term?.term ?? '',
    synonyms: rule?.term?.synonyms ?? [],
    meaning: rule?.term?.meaning ?? '',
    target: rule?.canonical?.target ?? '',
    raw: rule?.text ?? '',
  };
}

function compose(kind: RuleKind, fields: ReturnType<typeof fieldsOf>): string {
  const { scope, condition, exception, term, synonyms, meaning, target } = fields;
  if (kind === 'filter' && scope.trim() && condition.trim()) return filterText({ scope, condition, exception });
  if (kind === 'term' && term.trim() && meaning.trim()) return termText({ term, synonyms, meaning });
  if (kind === 'canonical' && scope.trim() && target.trim()) return canonicalText({ scope, target });
  return '';
}

function matchesShape(rule: RuleItem | undefined) {
  return !!(rule?.filter?.scope && rule.filter.condition || rule?.term?.term && rule.term.meaning || rule?.canonical?.scope && rule.canonical.target);
}

export default function RuleInspector({ groupId, rule, rules, section, snapshot, before, draft, editable, canEdit, disabledTitle, busy,
  feedback, editGuard, onDismiss, onBackToChecks, onSelectRule, onOpenField, onEngineering, onSave, onSaved, onMoved, onAsk, checkAction }: {
  groupId: string; rule?: RuleItem; rules: RuleItem[]; section: RuleSection | null; snapshot: SemanticSnapshot;
  before?: RuleItem; draft: boolean; editable: boolean; canEdit: boolean; disabledTitle?: string; busy: boolean; feedback: ReactNode;
  editGuard: MutableRefObject<(() => boolean) | null>;
  onDismiss: () => void; onBackToChecks: () => void; onSelectRule: (rule: RuleItem) => void;
  onOpenField: (model: string, field: string) => void; onEngineering?: (path?: string) => void;
  onSave: (edit: DraftItemEdit, done: string, after?: () => void) => Promise<void>;
  onSaved: (path: string, text: string) => void; onMoved: () => void;
  onAsk?: (text: string) => void;
  checkAction?: 'classify' | 'references';
}) {
  const kind = section?.kind ?? 'other';
  const rawOnly = kind === 'other';
  const initial = useMemo(() => fieldsOf(rule), [rule]);
  const [fields, setFields] = useState(initial);
  const [editingRaw, setEditingRaw] = useState(false);
  const [destination, setDestination] = useState<RuleSection>();
  const [synonymInput, setSynonymInput] = useState('');
  const [sourceResults, setSourceResults] = useState<BusinessDocument[]>();
  const [sourceError, setSourceError] = useState('');
  const [sourceLoading, setSourceLoading] = useState(false);
  const [sourceDocument, setSourceDocument] = useState<BusinessDocument>();
  const [copyStatus, setCopyStatus] = useState('');
  const [contextOpen, setContextOpen] = useState(false);
  const skipGuard = useRef(false);
  const initialSource = initial.raw.trim();
  const unclassified = !!rule && !section;
  const isClassifying = unclassified && editable && !editingRaw;
  const heading = rule?.heading ?? section?.heading ?? null;
  const path = rule?.path ?? rules.find(item => item.heading === heading)?.path ?? rulePath(section);
  const filename = path.split('/').pop() ?? path;
  const lineLabel = rule?.line ? '第 ' + rule.line + ' 行' : '';
  const headingLabel = heading ? '## ' + heading : '无小节';
  const synonyms = [...new Set([...fields.synonyms, ...splitList(synonymInput)])];
  const text = fields.raw.trim();
  const dirty = text !== initialSource
    || fields.scope !== initial.scope || fields.condition !== initial.condition || fields.exception !== initial.exception
    || fields.term !== initial.term || fields.meaning !== initial.meaning || fields.target !== initial.target
    || synonyms.join('\n') !== initial.synonyms.join('\n');
  const parsed = heading && text ? readRule(path, heading, text) : undefined;
  const invalidShape = !rawOnly && !!text && !matchesShape(parsed);
  const suggestion = editable && rule && invalidShape ? normalizeLegacyTerm(path, heading, text) : undefined;
  const inputDisabled = !canEdit || busy;
  const canSave = !inputDisabled && !!text && text !== initialSource && (!invalidShape || !!rule);
  const refs = ruleRefs(text, snapshot.models);
  const broken = [...new Set(brokenRefs(text, snapshot.models))];
  const sectionPeers = rule ? rules.filter(item => item.path === rule.path && item.heading === rule.heading) : [];
  const at = rule ? sectionPeers.indexOf(rule) : -1;
  const neighbors = [sectionPeers[at - 1], ...sectionPeers.slice(at + 1, at + 3)].filter(Boolean).slice(0, 2);
  const discard = useCallback(() => {
    setFields(initial); setSynonymInput(''); setEditingRaw(false); setDestination(undefined);
  }, [initial]);

  useLayoutEffect(() => {
    if (!editable) return;
    editGuard.current = () => {
      if (skipGuard.current) return true;
      if (busy) return false;
      if (!dirty) return true;
      if (!window.confirm('规则还有未保存的修改。放弃这些修改并继续？')) return false;
      skipGuard.current = true;
      discard();
      queueMicrotask(() => { skipGuard.current = false; });
      return true;
    };
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    if (dirty) window.addEventListener('beforeunload', warn);
    return () => {
      editGuard.current = null;
      if (dirty) window.removeEventListener('beforeunload', warn);
    };
  }, [editable, dirty, busy, editGuard, discard]);

  const change = (patch: Partial<typeof fields>) => {
    setFields(previous => {
      const next = { ...previous, ...patch };
      return { ...next, raw: compose(kind, next) || (invalidShape ? previous.raw : '') };
    });
  };
  const changeRaw = (raw: string) => {
    const result = heading && raw.trim() ? readRule(path, heading, raw.trim()) : undefined;
    setFields({ ...fieldsOf(result), raw });
    setSynonymInput('');
    setCopyStatus('');
  };
  const addSynonyms = () => { change({ synonyms }); setSynonymInput(''); };
  const organize = () => {
    if (suggestion) changeRaw(suggestion.text);
    else if (kind === 'filter') change({ condition: text });
    else if (kind === 'canonical') change({ target: text });
    else change({ meaning: text });
  };
  const finish = (action: () => void) => {
    skipGuard.current = true;
    try { action(); } finally { skipGuard.current = false; }
  };
  const submit = () => {
    if (!canSave || (!rule && !heading)) return;
    const edit: DraftItemEdit = rule ? { category: 'SEMANTIC', path, original: rule.text, text } : { category: 'SEMANTIC', path, heading: heading ?? undefined, text };
    void onSave(edit, '已保存到草稿。', () => finish(() => onSaved(path, text)));
  };
  const move = () => {
    if (!rule || !destination || inputDisabled) return;
    void onSave({ category: 'SEMANTIC', path, original: rule.text, heading: destination.heading }, '已移入草稿。', () => finish(onMoved));
  };
  const findSource = async () => {
    if (!rule || !editable || sourceLoading) return;
    setSourceError(''); setSourceLoading(true);
    try {
      const documents = await listDocuments(groupId);
      setSourceResults(documents.filter(document => document.status !== 'DELETED'
        && (rule.text.includes(document.name) || rule.text.includes('《' + document.name.replace(/\.md$/, '') + '》'))));
    } catch (error) {
      setSourceError(error instanceof Error ? error.message : String(error));
    } finally {
      setSourceLoading(false);
    }
  };
  const ask = () => onAsk?.('请协助完善这条业务规则（' + path + '，' + (heading ?? '未归类') + '）：\n' + rule?.text + '\n');
  const canOrganize = invalidShape && canEdit;
  const sourceCaption = before ? '与线上对照' : '草稿变更';
  const copy = async () => {
    try { await navigator.clipboard.writeText(text); setCopyStatus('已复制'); }
    catch { setCopyStatus('复制失败'); }
  };

  return <>
    <header className="rw-inspector-head">
      {checkAction && <button type="button" className="sw-back" onClick={onBackToChecks}>← 模型检查</button>}
      <div className="rw-inspector-id">
        <div className="rw-inspector-identity">
          <h3>{rule ? ruleWorkspaceTitle(rule) : '新增' + (section?.label ?? '') + '规则'}</h3>
          <p title={path}><strong>{filename}</strong>{lineLabel && ' · ' + lineLabel} · {section?.label ?? '未归类'}{draft && ' · 草稿'}{!editable && ' · 线上只读'}</p>
        </div>
        {rule && <WorkspaceMenu label="更多规则操作" items={[
          { label: contextOpen ? '收起上下文' : '查看上下文', icon: 'list', onClick: () => setContextOpen(value => !value) },
          ...(editable && onAsk ? [{ label: '在对话中改', icon: 'chat' as const, disabled: busy, onClick: ask }] : []),
          ...(editable && unclassified ? [{ label: editingRaw ? '返回归类' : '编辑原文', icon: 'edit' as const, disabled: inputDisabled,
            onClick: () => { if (!editingRaw || editGuard.current?.() !== false) setEditingRaw(!editingRaw); } }] : []),
          ...(editable ? [{ label: '删除规则', icon: 'trash' as const, danger: true, disabled: inputDisabled, onClick: () => {
            if (window.confirm('删除这条规则？')) void onSave({ category: 'SEMANTIC', path, original: rule.text, delete: true }, '已删除', () => finish(onDismiss));
          } }] : []),
        ]} />}
        <button type="button" className="rw-icon-btn" aria-label="关闭" onClick={onDismiss}><Icon name="close" size="sm" /></button>
      </div>
    </header>
    <div className="sw-db rw-inspector-body">
      {disabledTitle && editable && <p className="rw-detail-note">{disabledTitle}</p>}
      {!rawOnly && <section className="rw-rule-content">
        <header><h4>规则内容</h4>{draft && <span>待发布</span>}</header>
        <div className="rw-editor-fields">
          {(kind === 'filter' || kind === 'canonical') && <label><span>范围</span>
            <input aria-label="适用于" autoFocus={!rule} disabled={inputDisabled} value={fields.scope}
              onChange={event => change({ scope: event.target.value })} placeholder={kind === 'filter' ? '例如 全国' : '例如 会员消费'} /></label>}
          {kind === 'filter' && <>
            <label><span>条件</span><textarea rows={2} disabled={inputDisabled} value={fields.condition}
              onChange={event => change({ condition: event.target.value })} placeholder="例如 只取全国汇总行" /></label>
            <label><span>例外</span><input disabled={inputDisabled} value={fields.exception}
              onChange={event => change({ exception: event.target.value })} placeholder="可选，例如 点名查看分省时放开" /></label>
          </>}
          {kind === 'term' && <>
            <label><span>术语</span><input aria-label="业务说法" autoFocus={!rule} disabled={inputDisabled} value={fields.term}
              onChange={event => change({ term: event.target.value })} placeholder="例如 悦看" /></label>
            <div className="rw-synonym-field"><span>同义词</span><div className="rw-rule-synonyms">
              {fields.synonyms.map(word => <button key={word} disabled={inputDisabled} type="button" aria-label={'移除同义词 ' + word}
                onClick={() => change({ synonyms: fields.synonyms.filter(item => item !== word) })}>{word}<Icon name="close" size="sm" /></button>)}
              <input aria-label="添加同义词" value={synonymInput} disabled={inputDisabled} placeholder="输入后按回车"
                onChange={event => setSynonymInput(event.target.value)} onBlur={addSynonyms} onKeyDown={event => {
                  if (event.key !== 'Enter' || event.nativeEvent.isComposing) return;
                  event.preventDefault();
                  addSynonyms();
                }} />
            </div></div>
            <label><span>对应</span><textarea rows={2} disabled={inputDisabled} value={fields.meaning}
              onChange={event => change({ meaning: event.target.value })} placeholder="例如 cmread_video = '悦看'" /></label>
          </>}
          {kind === 'canonical' && <label><span>取数表</span><input aria-label="从哪张表或视图取数" disabled={inputDisabled} value={fields.target}
            onChange={event => change({ target: event.target.value })} placeholder="表或公共视图名" /></label>}
        </div>
      </section>}
            <section className="rw-rule-content">
        <header><h4>原文</h4>
          {isClassifying && canEdit ? <button type="button" className="sw-link" onClick={() => setEditingRaw(true)}>编辑</button>
            : canOrganize ? <button type="button" className="sw-link" disabled={busy} onClick={organize}>整理成句式</button>
              : text && <button type="button" className="sw-link" onClick={() => void copy()}>复制</button>}
        </header>
        {!rawOnly && editable && <p className="rw-detail-note rw-sentence-note">字段与这段原文同步，按原文保存。</p>}
        {editable && !isClassifying ? <textarea className="rw-raw-input" aria-label="规则原文" autoFocus={rawOnly && (!rule || editingRaw)}
          rows={8} readOnly={inputDisabled} value={fields.raw} placeholder="填写规则原文"
          onChange={event => changeRaw(event.target.value)} />
          : <p className="rw-rule-prose">{text}</p>}
        {copyStatus && <p className="rw-detail-note" role="status">{copyStatus}</p>}
      </section>
      {invalidShape && <p className={'rw-detail-note ' + (rule ? '' : 'sw-error')}>
        {rule ? '原文未匹配标准句式，可按原文保存。' : '内容与该小节的标准句式不匹配，请核对范围、术语或条件。'}
      </p>}
      {isClassifying && <p className="rw-classification-note"><Icon name="circleAlert" size="sm" />不在官方小节下，问数助手可能读不到这条规则。</p>}
      {checkAction === 'references' && broken.length > 0 && <p className="rw-classification-note">字段已改名时，可替换为当前名称。</p>}
      <div className="rw-section-line"><span>所属小节</span>
        {onEngineering ? <button type="button" className="sw-link" title={path} onClick={() => { if (editGuard.current?.() !== false) onEngineering(path); }}>{section?.label ?? headingLabel}</button>
          : <span>{section?.label ?? headingLabel}</span>}
      </div>
      {isClassifying && <section className="rw-classify">
        <h4>移到</h4>
        <div className="rw-segments" aria-label="移动到官方小节" role="group">
          {(checkAction === 'classify' ? SECTIONS : SECTIONS.slice(0, 3)).map(item => <button type="button" key={item.heading}
            aria-pressed={destination === item} disabled={inputDisabled} onClick={() => setDestination(item)}>{item.label}</button>)}
        </div>
        <p className="rw-detail-note">保留原文和所在文件。发布后问数按新的小节查找。</p>
        {destination && <div className="rw-destination"><h4>移动后的位置</h4><code>{filename}<br />## {destination.heading}</code></div>}
      </section>}
      {contextOpen && draft && !isClassifying && <details className="rw-detail-fold">
        <summary><Icon name="list" size="sm" /><strong>{sourceCaption}</strong><code>{before ? '本次改动' : '待发布'}</code><Icon name="chevron" size="sm" /></summary>
        <div>{before ? <div className="rw-diff">
          <p>− {before.heading !== heading ? '## ' + (before.heading ?? '无小节') + '\n  ' : ''}{before.text}</p>
          <p>＋ {before.heading !== heading ? headingLabel + '\n  ' : ''}{rule?.text}</p>
        </div> : <p className="rw-detail-note">可在「审阅草稿」对照完整文件。</p>}</div>
      </details>}
      {contextOpen && at >= 0 && neighbors.length > 0 && <section className="rw-neighbors">
        <h4>{unclassified ? '同一小节的未归类规则' : '同小节'} <small>{sectionPeers.length} 条</small></h4>
        {neighbors.map(peer => <button type="button" className="rw-neighbor" key={peer.line ?? peer.text} disabled={busy} onClick={() => onSelectRule(peer)}>
          <span>{ruleWorkspaceTitle(peer)}</span>{peer.line && <small>第 {peer.line} 行</small>}
        </button>)}
      </section>}
      {contextOpen && <section className="rw-context">
        <h4>上下文</h4>
        {!refs.length && !broken.length && (!editable || isClassifying) && <p className="rw-detail-note">暂无明确引用的字段。</p>}
        {(refs.length > 0 || broken.length > 0) && <div>
          <span>涉及字段{refs.length > 0 ? ' ' + refs.length : ''}</span>
          <div>{refs.map(ref => {
            const [model, field] = ref.split('.');
            return <button key={ref} type="button" className="sw-ref" onClick={() => onOpenField(model, field)}>{ref}</button>;
          })}{broken.length > 0 && <p className="rw-detail-note">当前模型中不存在 {broken.join('、')}</p>}</div>
        </div>}
        {editable && rule && !isClassifying && <div><span>来源文档</span><div>
          {sourceResults === undefined ? <button type="button" className="sw-link" onClick={() => void findSource()} disabled={sourceLoading}>
            {sourceLoading ? '正在查找…' : '查找明确引用'}</button>
            : sourceResults.length ? sourceResults.map(document => <button key={document.name} type="button" className="sw-link"
              onClick={() => setSourceDocument(document)}>打开原文 · {document.name}</button>)
              : <p className="rw-detail-note">未找到明确引用的业务文档。</p>}
          {sourceError && <p role="alert" className="sw-error">{sourceError}</p>}
        </div></div>}
      </section>}
      {sourceDocument && <BusinessDocumentReader groupId={groupId} document={sourceDocument} onClose={() => setSourceDocument(undefined)} />}
      {feedback}
    </div>
    {editable && <footer className="sw-df rw-rule-footer">
      <button type="button" className="da-btn da-btn-sm" disabled={busy || !!rule && !dirty && !editingRaw && !destination} onClick={rule ? discard : onDismiss}>{rule ? '还原' : '取消'}</button>
      <button type="button" className="da-btn da-btn-primary da-btn-sm" disabled={isClassifying ? !destination || inputDisabled : !canSave}
        title={disabledTitle} onClick={isClassifying ? move : submit}>
        {busy ? '保存中…' : isClassifying ? destination ? '移到' + destination.label : '移到所选小节' : '保存到草稿'}
      </button>
    </footer>}
  </>;
}
