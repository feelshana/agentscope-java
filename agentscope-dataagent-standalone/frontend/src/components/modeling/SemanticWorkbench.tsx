import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { MutableRefObject, PointerEvent as ReactPointerEvent, ReactNode } from 'react';
import { editDraftFile, editDraftItem, getDocumentContent, getDraftItems, listDocuments } from '../../api/draftReview';
import type { DraftItem, DraftItemEdit } from '../../api/draftReview';
import CalculatedColumnDialog from './CalculatedColumnDialog';
import YamlContent from './YamlContent';
import { joinLabel, modelTag, relationsOf, unrelatedModels } from './semanticSnapshot';
import type { SemanticColumn, SemanticModel, SemanticRelation, SemanticSnapshot } from './semanticSnapshot';
import { NODE_H, NODE_W, ROLE_HINT, ROLE_LABEL, focusOf, joinEnds, layoutOverview, roleOf } from './workbenchLayout';
import type { FocusLink, Positions, Role } from './workbenchLayout';
import {
  SECTIONS, brokenRefs, canonicalText, filterText, parseRuleFile, ruleRefs, ruleSummary, sectionOf, splitList, termText,
} from './ruleText';
import type { RuleItem, RuleSection } from './ruleText';
import './SemanticWorkbench.css';

export type WorkbenchView = 'graph' | 'rules';
type FieldEditGuard = MutableRefObject<(() => boolean) | null>;
type Selection =
  | { kind: 'model'; model: string }
  | { kind: 'field'; model: string; column: string }
  | { kind: 'relation'; name: string }
  | { kind: 'newRelation'; left: string }
  | { kind: 'rule'; path: string; text: string }
  | { kind: 'newRule'; heading: string }
  | { kind: 'view'; name: string }
  | { kind: 'problems' }
  | null;

interface Problem { key: string; tag: string; text: string; go: Selection; view?: WorkbenchView }
/** Which draft changes to mark: one conversation turn (since its start) or the whole draft (null). */
export interface ChangeMark { since: string | null }
/** spec117 §3c: a stable key for one change, so "已看" survives reloads until the change differs. */
export const changeKey = (item: DraftItem) => `${item.id}|${item.kind}|${item.text ?? ''}|${item.fields.map(f => `${f.name}=${f.after ?? ''}`).join(',')}`;
/**
 * red: the assistant changed it and you have not looked; added: new and not looked at (a small
 * "新" only, red stays for modifications); seen: looked at; diff: differs from online.
 */
export type MarkTone = 'red' | 'added' | 'seen' | 'diff' | '';
interface Marks { items: DraftItem[]; models: Set<string>; columns: Set<string>; relations: Set<string>; rules: Set<string>; added: Set<string> }
const NO_MARKS: Marks = { items: [], models: new Set(), columns: new Set(), relations: new Set(), rules: new Set(), added: new Set() };

function marksOf(items: DraftItem[]): Marks {
  const marks: Marks = { items, models: new Set(), columns: new Set(), relations: new Set(), rules: new Set(), added: new Set() };
  for (const item of items) {
    const added = item.kind === 'ADDED';
    if (item.category === 'MODEL') {
      // A new model is marked once as new; its columns are not each a change.
      if (added) { marks.added.add(`m:${item.title}`); continue; }
      marks.models.add(item.title);
      item.fields.filter(f => f.name !== '__table__').forEach(f => marks.columns.add(`${item.title}.${f.name}`));
    } else if (item.category === 'RELATIONSHIP' && item.text) (added ? marks.added.add(`r:${item.text}`) : marks.relations.add(item.text));
    else if (item.text) (added ? marks.added.add(`q:${item.path}\n${item.text}`) : marks.rules.add(`${item.path}\n${item.text}`));
  }
  return marks;
}

const ROLES: Role[] = ['IDENTIFIER', 'DIMENSION', 'TIME', 'MEASURE'];
const JOINS = ['MANY_TO_ONE', 'ONE_TO_MANY', 'ONE_TO_ONE', 'MANY_TO_MANY'];
const JOIN_HINT: Record<string, string> = {
  MANY_TO_ONE: '左边多行对应右边一行，例如多笔订单对应一个客户；右边的关联字段必须唯一，否则会重复计算。',
  ONE_TO_MANY: '左边一行对应右边多行。',
  ONE_TO_ONE: '两边一一对应。',
  MANY_TO_MANY: '多对多关联容易重复计算，尽量避免。',
};
const RULE_FILE = 'knowledge/rules/general.md';
const TERM_FILE = 'knowledge/rules/naming.md';
const MIN_ZOOM = 0.4;
const MAX_ZOOM = 1.6;
/** Fitting never shrinks text below this; larger graphs scroll instead. */
const FIT_FLOOR = 0.7;
const clampZoom = (z: number) => Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Math.round(z * 100) / 100));
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const columnLabel = (c: SemanticColumn) => c.displayName || c.name;
const isRuleFile = (path: string) => /^knowledge\/rules\/[^/]+\.md$/.test(path);

function readPositions(groupId: string): Positions {
  try { return JSON.parse(localStorage.getItem(`sw-node-pos:${groupId}`) ?? '{}') as Positions; } catch { return {}; }
}
function savePositions(groupId: string, positions: Positions) {
  try { localStorage.setItem(`sw-node-pos:${groupId}`, JSON.stringify(positions)); } catch { /* per-browser convenience only */ }
}

/**
 * spec117 语义模型工作台: models, relationships and rules on one page. The draft is edited in
 * place by MANAGE users; every save writes the Wren project files through /draft/items or
 * /draft/edit and lands in the same draft as conversation changes.
 */
export default function SemanticWorkbench({
  groupId, snapshot, baseline, files, baselineFiles, views, issues, editable, blocked, view, onViewChange,
  focusRelation, focusModel, onChanged, onAsk, toolbar, changes, onChanges,
  marked, isSeen, onSeen, onOpenPending, reveal, showDetail = true, firstBuild, compareWithBaseline = false, fieldEditGuard,
}: {
  groupId: string;
  snapshot: SemanticSnapshot;
  /** The published version, for 与线上对比; null when viewing it or nothing is published. */
  baseline: SemanticSnapshot | null;
  compareWithBaseline?: boolean;
  fieldEditGuard?: FieldEditGuard;
  /** Project files of the version shown, by path. */
  files: Map<string, string>;
  baselineFiles: Map<string, string> | null;
  views: { name: string; sql: string; description: string | null }[];
  issues: string[];
  editable: boolean;
  /** Why edits are paused right now (the assistant is running), or null. */
  blocked: string | null;
  view: WorkbenchView;
  onViewChange: (view: WorkbenchView) => void;
  focusRelation?: string | null;
  focusModel?: string | null;
  onChanged: () => void;
  onAsk?: (text: string) => void;
  toolbar?: ReactNode;
  /** Draft changes to mark on the graph and rules (spec117: "在语义模型中查看" from a turn). */
  changes?: ChangeMark | null;
  onChanges?: (changes: ChangeMark | null) => void;
  /** spec117 §3c: the marked changes, loaded by the page (shared with the 待确认 panel). */
  marked?: DraftItem[];
  isSeen?: (item: DraftItem) => boolean;
  onSeen?: (item: DraftItem) => void;
  onOpenPending?: () => void;
  /** A change to show, sent from the 待确认 panel; `id` changes on every request. */
  reveal?: { id: number; item: DraftItem } | null;
  /** The detail drawer shares the right panel with the assistant and 待确认. */
  showDetail?: boolean;
  /** Before the first publish: a summary of what was built and what needs a look, not marks. */
  firstBuild?: { onPublish: () => void } | null;
}) {
  const [selection, setSelectedObject] = useState<Selection>(focusRelation ? { kind: 'relation', name: focusRelation } : focusModel ? { kind: 'model', model: focusModel } : null);
  const localFieldEditGuard = useRef<(() => boolean) | null>(null);
  const editGuard = fieldEditGuard ?? localFieldEditGuard;
  const selectionKey = JSON.stringify(selection);
  const setSelection = useCallback((next: Selection) => {
    if (JSON.stringify(next) === selectionKey) return true;
    if (editGuard.current?.() === false) return false;
    setSelectedObject(next);
    return true;
  }, [editGuard, selectionKey]);
  const [zoom, setZoom] = useState(1);
  // 聚焦: double-clicking a model shows it with its direct neighbours (spec117).
  const [focus, setFocus] = useState<string | null>(null);
  const [positions, setPositions] = useState<Positions>(() => readPositions(groupId));
  const [query, setQuery] = useState('');
  const [full, setFull] = useState(false);
  const [summaryClosed, setSummaryClosed] = useState(false);
  // The object bar folds to icons; remembered per browser.
  const [railOpen, setRailOpen] = useState(() => { try { return localStorage.getItem('sw-rail') !== 'closed'; } catch { return true; } });
  const toggleRail = () => setRailOpen(open => { try { localStorage.setItem('sw-rail', open ? 'closed' : 'open'); } catch { /* convenience only */ } return !open; });
  useEffect(() => {
    if (!full) return undefined;
    const key = (event: KeyboardEvent) => { if (event.key === 'Escape') setFull(false); };
    window.addEventListener('keydown', key);
    return () => window.removeEventListener('keydown', key);
  }, [full]);
  const items = changes && editable ? marked ?? [] : [];
  const unseen = useMemo(() => marksOf(items.filter(i => !isSeen?.(i))), [items, isSeen]);
  const looked = useMemo(() => marksOf(items.filter(i => isSeen?.(i))), [items, isSeen]);
  const marks = unseen;
  const tone = (red: boolean, seen: boolean, diff: boolean, added = false): MarkTone => red ? 'red' : added ? 'added' : diff ? 'diff' : seen ? 'seen' : '';
  // Callers key this component by groupId, so card positions never cross knowledge bases.
  useEffect(() => { if (focusRelation) setSelection({ kind: 'relation', name: focusRelation }); }, [focusRelation]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { if (focusModel) setSelection({ kind: 'model', model: focusModel }); }, [focusModel]); // eslint-disable-line react-hooks/exhaustive-deps

  const layout = useMemo(() => layoutOverview(snapshot, positions), [snapshot, positions]);
  const focused = useMemo(() => focus ? focusOf(snapshot, focus) : null, [snapshot, focus]);
  const unrelated = useMemo(() => new Set(unrelatedModels(snapshot).map(m => m.name)), [snapshot]);
  const rules = useMemo(() => [...files.entries()].filter(([p]) => isRuleFile(p)).flatMap(([p, c]) => parseRuleFile(p, c)), [files]);
  const onlineRules = useMemo(() => new Set(baselineFiles ? [...baselineFiles.entries()].filter(([p]) => isRuleFile(p)).flatMap(([p, c]) => parseRuleFile(p, c)).map(r => `${r.path}\n${r.text}`) : []), [baselineFiles]);
  const comparing = compareWithBaseline && !!baseline;
  const differs = (model: string, column: SemanticColumn) => {
    if (!comparing || !baseline) return false;
    const before = baseline.models.find(m => m.name === model)?.columns.find(c => c.name === column.name);
    return !before || (before.displayName ?? '') !== (column.displayName ?? '')
      || (before.description ?? '') !== (column.description ?? '') || (before.expression ?? '') !== (column.expression ?? '');
  };
  const changedColumn = (model: string, column: SemanticColumn): MarkTone =>
    tone(unseen.columns.has(`${model}.${column.name}`), looked.columns.has(`${model}.${column.name}`), differs(model, column));
  const changedModel = (model: SemanticModel) => !!model.path && !!baselineFiles && baselineFiles.get(model.path) !== files.get(model.path);
  const newRule = (r: RuleItem): MarkTone => {
    const key = `${r.path}\n${r.text}`;
    return tone(unseen.rules.has(key), looked.rules.has(key) || looked.added.has(`q:${key}`), comparing && !onlineRules.has(key), unseen.added.has(`q:${key}`));
  };
  const markedRelation = (r: SemanticRelation): MarkTone => tone(unseen.relations.has(r.name), looked.relations.has(r.name) || looked.added.has(`r:${r.name}`), false, unseen.added.has(`r:${r.name}`));
  const markedModel = (m: SemanticModel): MarkTone => tone(unseen.models.has(m.name), looked.models.has(m.name) || looked.added.has(`m:${m.name}`), false, unseen.added.has(`m:${m.name}`));

  const problems = useMemo<Problem[]>(() => {
    const out: Problem[] = [];
    for (const m of snapshot.models) {
      const missing = m.columns.filter(c => !c.description?.trim());
      if (missing.length) out.push({ key: `d:${m.name}`, tag: '缺说明', text: `${m.name} 有 ${missing.length} 个字段没有说明：${missing.slice(0, 4).map(c => c.name).join('、')}${missing.length > 4 ? ' 等' : ''}`, go: { kind: 'field', model: m.name, column: missing[0].name }, view: 'graph' });
      if (unrelated.has(m.name)) out.push({ key: `u:${m.name}`, tag: '未关联', text: `${m.name} 没有关联到其他模型，问数时不能和其他表一起分析`, go: { kind: 'model', model: m.name }, view: 'graph' });
    }
    for (const r of rules) {
      if (!sectionOf(r.heading)) out.push({ key: `s:${r.path}:${r.text}`, tag: '未归类', text: `「${ruleSummary(r).slice(0, 40)}」不在官方小节，问数助手不一定读到`, go: { kind: 'rule', path: r.path, text: r.text }, view: 'rules' });
      for (const ref of brokenRefs(r.text, snapshot.models)) out.push({ key: `b:${r.path}:${r.text}:${ref}`, tag: '字段不存在', text: `规则引用的 ${ref} 在草稿中不存在`, go: { kind: 'rule', path: r.path, text: r.text }, view: 'rules' });
    }
    issues.forEach((text, i) => out.push({ key: `i:${i}`, tag: '校验', text, go: null }));
    return out;
  }, [snapshot, rules, issues, unrelated]);

  // ----- canvas: pan the scroll box, drag cards by their header, zoom with buttons or Ctrl+wheel
  const canvas = useRef<HTMLDivElement>(null);
  const pan = useRef<{ x: number; y: number; left: number; top: number } | null>(null);
  const grab = useRef<{ model: string; x: number; y: number; ox: number; oy: number; moved: boolean } | null>(null);
  const suppressClick = useRef(false);
  useEffect(() => {
    const box = canvas.current;
    if (!box) return undefined;
    const wheel = (event: WheelEvent) => {
      if (!event.ctrlKey && !event.metaKey) return;
      event.preventDefault();
      setZoom(z => clampZoom(z - event.deltaY * 0.001));
    };
    box.addEventListener('wheel', wheel, { passive: false });
    return () => box.removeEventListener('wheel', wheel);
  }, [view]);
  const fit = useCallback(() => {
    const box = canvas.current;
    if (!box || !layout.width) return;
    setZoom(clampZoom(Math.max(FIT_FLOOR, Math.min(1, (box.clientWidth - 24) / layout.width, (box.clientHeight - 24) / layout.height))));
    box.scrollTo(0, 0);
  }, [layout.width, layout.height]);
  const modelCount = snapshot.models.length;
  useEffect(() => { if (view === 'graph' && !focus) fit(); }, [modelCount, view, full, focus]); // eslint-disable-line react-hooks/exhaustive-deps

  const pointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement;
    const head = target.closest('[data-card-head]') as HTMLElement | null;
    const box = canvas.current;
    if (!box) return;
    if (head) {
      const name = head.dataset.cardHead ?? '';
      const card = layout.nodes.find(c => c.model.name === name);
      if (!card) return;
      // No pointer capture here: the click must still land on the header to select the card.
      grab.current = { model: name, x: event.clientX, y: event.clientY, ox: card.x, oy: card.y, moved: false };
      return;
    }
    if (target.closest('button')) return;
    pan.current = { x: event.clientX, y: event.clientY, left: box.scrollLeft, top: box.scrollTop };
    box.setPointerCapture(event.pointerId);
  };
  const pointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const box = canvas.current;
    if (!box) return;
    const g = grab.current;
    if (g) {
      const dx = (event.clientX - g.x) / zoom;
      const dy = (event.clientY - g.y) / zoom;
      if (!g.moved && Math.abs(dx) + Math.abs(dy) < 4) return;
      g.moved = true;
      setPositions(p => ({ ...p, [g.model]: { x: Math.max(0, Math.round(g.ox + dx)), y: Math.max(0, Math.round(g.oy + dy)) } }));
      return;
    }
    if (!pan.current) return;
    box.scrollLeft = pan.current.left - (event.clientX - pan.current.x);
    box.scrollTop = pan.current.top - (event.clientY - pan.current.y);
  };
  const pointerUp = () => {
    const g = grab.current;
    if (g?.moved) { suppressClick.current = true; setTimeout(() => { suppressClick.current = false; }, 0); }
    grab.current = null;
    pan.current = null;
  };
  useEffect(() => { savePositions(groupId, positions); }, [groupId, positions]);
  const resetLayout = () => { setPositions({}); savePositions(groupId, {}); setTimeout(fit, 0); };
  const locate = (name: string) => {
    const card = layout.nodes.find(c => c.model.name === name);
    const box = canvas.current;
    if (!card || !box) return;
    box.scrollTo({ left: Math.max(0, card.x * zoom - 40), top: Math.max(0, card.y * zoom - 40), behavior: 'smooth' });
  };
  const search = () => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) return;
    const model = snapshot.models.find(m => m.name.toLowerCase().includes(keyword) || (m.description ?? '').toLowerCase().includes(keyword));
    if (model) {
      if (setSelection({ kind: 'model', model: model.name })) { onViewChange('graph'); setTimeout(() => locate(model.name), 0); }
      return;
    }
    for (const m of snapshot.models) {
      const column = m.columns.find(c => c.name.toLowerCase().includes(keyword) || (c.displayName ?? '').toLowerCase().includes(keyword) || (c.description ?? '').toLowerCase().includes(keyword));
      if (column) {
        if (setSelection({ kind: 'field', model: m.name, column: column.name })) { onViewChange('graph'); setTimeout(() => locate(m.name), 0); }
        return;
      }
    }
    if (rules.some(r => r.text.toLowerCase().includes(keyword))) onViewChange('rules');
  };

  const selectedModel = selection && 'model' in selection ? snapshot.models.find(m => m.name === selection.model) ?? null : null;
  const selectedRelation = selection?.kind === 'relation' ? snapshot.relations.find(r => r.name === selection.name) ?? null : null;
  const hot = (r: SemanticRelation) => selectedRelation ? r.name === selectedRelation.name : selectedModel ? r.left === selectedModel.name || r.right === selectedModel.name : false;
  const pick = (next: Selection) => { if (suppressClick.current) { suppressClick.current = false; return; } setSelection(next); };
  const go = ({ go: target, view: nextView }: Problem) => {
    if (!target || !setSelection(target)) return;
    if (nextView) onViewChange(nextView);
    if ('model' in target) setTimeout(() => locate(target.model), 0);
  };

  const save = async (edit: DraftItemEdit) => { await editDraftItem(groupId, edit); onChanged(); };
  const canEdit = editable && !blocked;

  if (!snapshot.models.length) return <div className="sw"><div className="sw-empty-page">暂无模型，请先上传数据或关联数据表。</div></div>;

  const markLabel = (item: DraftItem) => item.category === 'MODEL' ? `${item.title}${item.kind === 'ADDED' ? '（新增）' : item.fields.length ? ` · ${item.fields.filter(f => f.name !== '__table__').length || ''}${item.fields.some(f => f.name !== '__table__') ? ' 个字段' : '表说明'}` : ''}`
    : item.category === 'RELATIONSHIP' ? `关系 ${item.title}${item.kind === 'REMOVED' ? '（已删除）' : ''}` : `规则 ${(item.text ?? '').split('\n')[0].slice(0, 24)}`;
  const openMark = (item: DraftItem) => {
    if (item.category === 'MODEL') {
      const field = item.fields.find(f => f.name !== '__table__');
      if (!setSelection(field ? { kind: 'field', model: item.title, column: field.name } : { kind: 'model', model: item.title })) return false;
      onViewChange('graph');
      setTimeout(() => locate(item.title), 0);
    } else if (item.category === 'RELATIONSHIP' && item.text) {
      if (!setSelection({ kind: 'relation', name: item.text })) return false;
      onViewChange('graph');
    } else if (item.text) {
      if (!setSelection({ kind: 'rule', path: item.path, text: item.text })) return false;
      onViewChange('rules');
    }
    return true;
  };

  return <div className={`sw ${full ? 'sw-full' : ''}`}>
    <LocateEffect locate={reveal} onLocate={item => { if (openMark(item) && focus && item.category === 'MODEL') setFocus(item.title); }} />
    <aside className={`sw-rail ${railOpen ? '' : 'sw-rail-closed'}`} aria-label="对象">
      <div className="sw-rail-top">
        {railOpen && <span className="sw-rail-title">对象</span>}
        <button type="button" className="sw-rail-fold" onClick={toggleRail} aria-expanded={railOpen} title={railOpen ? '收起对象栏' : '展开对象栏'}>{railOpen ? '‹' : '›'}</button>
      </div>
      <input className="da-input sw-search" type="search" aria-label="搜索模型、字段、规则" placeholder="搜索模型、字段、规则，回车"
        value={query} onChange={e => setQuery(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') search(); }} />
      <RailGroup label={`模型 ${snapshot.models.length}`} />
      {snapshot.models.map(m => {
        const tag = modelTag(m, unrelated.has(m.name));
        return <button key={m.name} type="button" className={`sw-node ${selectedModel?.name === m.name && view === 'graph' ? 'sw-node-on' : ''}`}
          onClick={() => { if (setSelection({ kind: 'model', model: m.name })) { onViewChange('graph'); if (focus) setFocus(m.name); else setTimeout(() => locate(m.name), 0); } }}
          onDoubleClick={() => { if (selection?.kind === 'model' && selection.model === m.name) setFocus(m.name); }}>
          <span className={`sw-ico ${m.tableRole === 'FACT' ? 'sw-ico-fact' : m.derived ? 'sw-ico-sql' : 'sw-ico-dim'}`}>{m.tableRole === 'FACT' ? '事' : m.derived ? '算' : '维'}</span>
          <span className="sw-node-name" title={m.name}>{m.name}</span>
          {changedModel(m) && <span className="sw-dot" title="草稿有改动" />}
          {tag.tone === 'warn' ? <small className="sw-warn-text">未关联</small> : <small>{m.columns.length}</small>}
        </button>;
      })}
      <RailGroup label={`关系 ${snapshot.relations.length}`} action={canEdit && snapshot.models.length > 1 ? () => { if (setSelection({ kind: 'newRelation', left: snapshot.models[0].name })) onViewChange('graph'); } : undefined} />
      {snapshot.relations.map(r => <button key={r.name} type="button" className={`sw-node ${selectedRelation?.name === r.name ? 'sw-node-on' : ''}`}
        onClick={() => { if (setSelection({ kind: 'relation', name: r.name })) onViewChange('graph'); }}>
        <span className="sw-ico sw-ico-rel">关</span><span className="sw-node-name" title={`${r.left} → ${r.right}`}>{r.left} → {r.right}</span><small>{joinEnds(r.joinType).join(':')}</small>
      </button>)}
      <RailGroup label={`视图 ${views.length}`} action={canEdit && onAsk ? () => onAsk('我想建一个视图：') : undefined} actionLabel="让助手建一个视图" />
      {!views.length && <div className="sw-rail-empty">把常用的多表组合固定成一张表。点 + 在助手里描述，例如"订单带上客户地区和会员等级"。</div>}
      {views.map(v => <button key={v.name} type="button" className={`sw-node ${selection?.kind === 'view' && selection.name === v.name ? 'sw-node-on' : ''}`} onClick={() => setSelection({ kind: 'view', name: v.name })}>
        <span className="sw-ico sw-ico-view">视</span><span className="sw-node-name">{v.name}</span>
      </button>)}
      <RailGroup label={`业务规则 ${rules.length}`} />
      {[...SECTIONS.filter(s => s.kind !== 'other' || rules.some(r => r.heading === s.heading)), null].map(s => {
        const count = rules.filter(r => s ? r.heading === s.heading : !sectionOf(r.heading)).length;
        if (!s && !count) return null;
        return <button key={s?.heading ?? 'other'} type="button" className="sw-node" onClick={() => { onViewChange('rules'); setTimeout(() => document.getElementById(`sw-sec-${s?.heading ?? 'other'}`)?.scrollIntoView({ block: 'start', behavior: 'smooth' }), 0); }}>
          <span className={`sw-ico ${s ? 'sw-ico-rule' : 'sw-ico-warn'}`}>{s ? '规' : '!'}</span><span className="sw-node-name">{s?.label ?? '未归类'}</span><small>{count}</small>
        </button>;
      })}
      <RailGroup label="检查" />
      {editable && onChanges && <button type="button" className={`sw-node ${changes && !changes.since ? 'sw-node-on' : ''}`}
        onClick={() => onChanges(changes && !changes.since ? null : { since: null })}>
        <span className="sw-ico sw-ico-metric">改</span><span className="sw-node-name">标出草稿改动</span><small>{changes && !changes.since ? '开' : ''}</small>
      </button>}
      <button type="button" className={`sw-node ${selection?.kind === 'problems' ? 'sw-node-on' : ''}`} onClick={() => setSelection({ kind: 'problems' })}>
        <span className="sw-ico sw-ico-warn">!</span><span className="sw-node-name">需要处理</span><small className={problems.length ? 'sw-warn-text' : ''}>{problems.length}</small>
      </button>
    </aside>

    <main className="sw-stage">
      <div className="sw-toolbar">
        <div role="group" aria-label="视图" className="sw-seg">
          <button type="button" aria-pressed={view === 'graph'} className={view === 'graph' ? 'on' : ''} onClick={() => onViewChange('graph')}>关系图</button>
          <button type="button" aria-pressed={view === 'rules'} className={view === 'rules' ? 'on' : ''} onClick={() => onViewChange('rules')}>业务规则</button>
        </div>
        {toolbar}
        <span className="sw-grow" />
        {view === 'graph' && !focus && <div className="sw-float">
          <button type="button" className="sw-icon-btn" aria-label="缩小" onClick={() => setZoom(z => clampZoom(z - 0.1))}>−</button>
          <span className="sw-zoom">{Math.round(zoom * 100)}%</span>
          <button type="button" className="sw-icon-btn" aria-label="放大" onClick={() => setZoom(z => clampZoom(z + 0.1))}>+</button>
          <button type="button" className="sw-icon-btn" onClick={fit}>适应</button>
          <button type="button" className="sw-icon-btn" onClick={resetLayout}>自动布局</button>
        </div>}
        <button type="button" className="da-btn da-btn-sm" aria-pressed={full} onClick={() => setFull(!full)} title={full ? '按 Esc 退出' : '占满整个窗口'}>{full ? '退出全屏' : '全屏'}</button>
      </div>
      {firstBuild && editable && !changes && !summaryClosed && <div className="sw-summary">
        <div className="sw-summary-main">
          <strong>建模结果</strong>
          <span>生成 {snapshot.models.length} 个模型、{snapshot.relations.length} 条关系、{rules.length} 条业务规则。</span>
          {problems.length ? <span>需要你看：<button type="button" className="sw-link" onClick={() => setSelection({ kind: 'problems' })}>{problems.length} 处</button>（{[...new Set(problems.map(p => p.tag))].join('、')}）</span>
            : <span className="sw-ok-text">没有需要处理的问题。</span>}
        </div>
        <span className="sw-muted sw-xs">下一步：看一眼关系图和业务规则，口径没问题就发布；试问是可选的。</span>
        <span className="sw-grow" />
        <button type="button" className="da-btn da-btn-primary da-btn-sm" onClick={firstBuild.onPublish}>检查并发布</button>
        <button type="button" className="sw-link" aria-label="关闭" onClick={() => setSummaryClosed(true)}>关闭</button>
      </div>}
      {changes && editable && <div className="sw-marks">
        <strong>{changes.since ? '助手改动' : '草稿全部改动'}</strong>
        {items.length === 0 ? <span className="sw-muted">没有未发布的改动</span> : <>
          <span>{items.length} 处{unseen.items.length ? <>，<b className="sw-red-text">{unseen.items.length} 处未看</b></> : '，都已看过'}</span>
          {unseen.items.slice(0, 6).map(item => <button key={item.id} type="button" className="sw-mark" disabled={item.kind === 'REMOVED' && item.category !== 'MODEL'}
            onClick={() => { if (openMark(item)) onSeen?.(item); }}>{markLabel(item)}</button>)}
        </>}
        <span className="sw-grow" />
        <span className="sw-muted sw-xs"><span className="sw-swatch sw-swatch-red" />未看 <span className="sw-swatch sw-swatch-seen" />已看</span>
        {onOpenPending && unseen.items.length > 0 && <button type="button" className="sw-link" onClick={onOpenPending}>逐项查看</button>}
        {onChanges && <button type="button" className="sw-link" onClick={() => onChanges(null)}>清除标记</button>}
      </div>}
      {comparing && <div className="sw-banner">对比线上版本：黄色为草稿中新增或修改的内容，发布后才对问数生效</div>}
      {blocked && editable && <div className="sw-banner sw-banner-warn">{blocked}</div>}

      {view === 'graph' && focused ? <FocusView focus={focused} snapshot={snapshot} rules={rules} selection={selection} canEdit={canEdit}
        changedColumn={changedColumn} markedRelation={markedRelation} markedModel={markedModel}
        onBack={() => { setFocus(null); }} onFocus={name => { if (setSelection({ kind: 'model', model: name })) setFocus(name); }}
        onSelect={setSelection} />
      : view === 'graph' ? <div className="sw-canvas-wrap"><div ref={canvas} className="sw-canvas" onPointerDown={pointerDown} onPointerMove={pointerMove} onPointerUp={pointerUp} onPointerCancel={pointerUp} onPointerLeave={pointerUp}>
        <div className="sw-sizer" style={{ width: layout.width * zoom, height: layout.height * zoom }}>
          <div className="sw-world" style={{ width: layout.width, height: layout.height, transform: `scale(${zoom})` }}>
            <svg width={layout.width} height={layout.height} className="sw-edges" aria-hidden="true">
              {layout.edges.map(e => <path key={e.key} d={e.path} className={`sw-line ${hot(e.relation) ? 'sw-line-on' : markedRelation(e.relation) ? `sw-line-${markedRelation(e.relation)}` : ''}`} />)}
            </svg>
            {layout.edges.map(e => {
              const [a, b] = joinEnds(e.relation.joinType);
              return <span key={`${e.key}:ends`}>
                <span className="sw-end" style={{ left: e.from.x, top: e.from.y }}>{a}</span>
                <span className="sw-end" style={{ left: e.to.x, top: e.to.y }}>{b}</span>
                <button type="button" className={`sw-edge ${hot(e.relation) ? 'sw-edge-on' : markedRelation(e.relation) ? `sw-edge-${markedRelation(e.relation)}` : ''}`} style={{ left: e.mid.x, top: e.mid.y }}
                  aria-label={`关系 ${e.relation.left} 到 ${e.relation.right}`} onClick={() => setSelection({ kind: 'relation', name: e.relation.name })}>
                  {joinLabel(e.relation.joinType)}</button>
              </span>;
            })}
            {layout.nodes.map(node => {
              const m = node.model;
              const on = selectedModel?.name === m.name;
              const tone = m.tableRole === 'FACT' ? 'fact' : m.derived ? 'sql' : 'dim';
              const calc = m.columns.filter(c => c.calculated).length;
              return <div key={m.name} data-card-head={m.name} role="button" tabIndex={0} title="单击查看详情，双击聚焦"
                className={`sw-onode sw-onode-${tone} ${on ? 'sw-onode-on' : markedModel(m) ? `sw-onode-${markedModel(m)}` : ''} ${unrelated.has(m.name) ? 'sw-onode-warn' : ''}`}
                style={{ left: node.x, top: node.y, width: NODE_W, height: NODE_H }}
                onClick={() => pick({ kind: 'model', model: m.name })} onDoubleClick={() => { if (selection?.kind === 'model' && selection.model === m.name) setFocus(m.name); }}
                onKeyDown={e => { if (e.key === 'Enter' && setSelection({ kind: 'model', model: m.name })) setFocus(m.name); }}>
                <span className={`sw-ico sw-ico-${tone}`}>{tone === 'fact' ? '事' : tone === 'sql' ? '算' : '维'}</span>
                <span className="sw-onode-text"><strong title={m.name}>{m.name}</strong>
                  <small>{modelTag(m, unrelated.has(m.name)).label} · {m.columns.length - calc} 列{calc ? ` · ${calc} 衍生` : ''}</small></span>
                {markedModel(m) === 'red' && <span className="sw-badge-red">改</span>}
                {markedModel(m) === 'added' && <span className="sw-badge-new">新</span>}
              </div>;
            })}
          </div>
        </div>
        </div>
        <div className="sw-legend">
          <span><b>单击</b>看详情</span><span><b>双击</b>聚焦关系</span><span><b>拖动</b>移动或平移</span><span><b>Ctrl + 滚轮</b>缩放</span>
          <span className="sw-legend-sep" /><span>N — 1 表示多对一</span>
        </div>
      </div> : <RulesView rules={rules} snapshot={snapshot} selection={selection} isNew={newRule} editable={canEdit}
        onSelect={setSelection} onMove={async (rule, heading) => { await save({ category: 'SEMANTIC', path: rule.path, original: rule.text, heading }); }} />}
    </main>

    {showDetail && selection && <aside className="sw-drawer" aria-label="详情">
      <Drawer key={selectionKey} {...{ groupId, snapshot, selection, files, views, rules, problems, editable, canEdit, blocked, onAsk, changedColumn, newRule, editGuard }}
        onSelect={setSelection} onSave={save} onGo={go} onChanged={onChanged} />
    </aside>}
  </div>;
}

/** Runs a locate request once per id, after render (the target may need the graph view). */
function LocateEffect({ locate, onLocate }: { locate?: { id: number; item: DraftItem } | null; onLocate: (item: DraftItem) => void }) {
  const done = useRef<number | null>(null);
  useEffect(() => {
    if (!locate || done.current === locate.id) return;
    done.current = locate.id;
    onLocate(locate.item);
  });
  return null;
}

/** "改" in red for an unseen assistant change, grey once looked at, yellow against online. */
function ToneTag({ tone }: { tone: MarkTone }) {
  if (!tone) return null;
  const label = tone === 'red' ? '助手改' : tone === 'added' ? '新增' : tone === 'seen' ? '已看' : '草稿改';
  return <span className={`sw-tone sw-tone-${tone}`}>{label}</span>;
}

function RailGroup({ label, action, actionLabel = '新增' }: { label: string; action?: () => void; actionLabel?: string }) {
  return <div className="sw-rail-group"><span>{label}</span>{action && <button type="button" aria-label={actionLabel} title={actionLabel} onClick={action}>+</button>}</div>;
}

const FOCUS_SIDE_W = 240;
const FOCUS_SIDE_H = 64;
const FOCUS_SIDE_GAP = 18;
const FOCUS_CENTER_W = 360;
const FOCUS_HEAD = 78;
const FOCUS_ROW = 34;
const FOCUS_FOOT = 44;
const FOCUS_TOP = 56;

/**
 * spec117 聚焦: one model in the middle with its key columns; models that point many-to-one into
 * it on the left, the ones it points to on the right. Clicking a side model re-centres on it.
 */
function FocusView({ focus, snapshot, rules, selection, canEdit, changedColumn, markedRelation, markedModel, onBack, onFocus, onSelect }: {
  focus: { center: SemanticModel; incoming: FocusLink[]; outgoing: FocusLink[] };
  snapshot: SemanticSnapshot;
  rules: RuleItem[];
  selection: Selection;
  canEdit: boolean;
  changedColumn: (model: string, column: SemanticColumn) => MarkTone;
  markedRelation: (r: SemanticRelation) => MarkTone;
  markedModel: (m: SemanticModel) => MarkTone;
  onBack: () => void;
  onFocus: (name: string) => void;
  onSelect: (s: Selection) => void;
}) {
  const box = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(1000);
  useEffect(() => {
    const node = box.current;
    if (!node) return undefined;
    const observer = new ResizeObserver(() => setWidth(Math.max(900, node.clientWidth)));
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  const { center, incoming, outgoing } = focus;
  // Key rows: the identity key, then every column a relationship joins on.
  const keys: { column: SemanticColumn; target?: string }[] = [];
  const add = (name: string, target?: string) => {
    const column = center.columns.find(c => c.name === name);
    if (column && !keys.some(k => k.column.name === name)) keys.push({ column, target });
  };
  center.columns.filter(c => c.primaryKey).forEach(c => add(c.name));
  outgoing.forEach(l => l.centerColumns.forEach(c => add(c, l.model.name)));
  incoming.forEach(l => l.centerColumns.forEach(c => add(c)));
  const centerX = width / 2 - FOCUS_CENTER_W / 2;
  const centerH = FOCUS_HEAD + keys.length * FOCUS_ROW + FOCUS_FOOT;
  const sideHeight = (n: number) => n * FOCUS_SIDE_H + Math.max(0, n - 1) * FOCUS_SIDE_GAP;
  const middle = FOCUS_TOP + centerH / 2;
  const top = Math.max(FOCUS_TOP, middle - Math.max(sideHeight(incoming.length), sideHeight(outgoing.length)) / 2);
  const centerTop = Math.max(FOCUS_TOP, top + Math.max(sideHeight(incoming.length), sideHeight(outgoing.length)) / 2 - centerH / 2);
  const rowY = (name: string | undefined) => {
    const i = keys.findIndex(k => k.column.name === name);
    return centerTop + (i < 0 ? FOCUS_HEAD / 2 : FOCUS_HEAD + i * FOCUS_ROW + FOCUS_ROW / 2);
  };
  const sideY = (n: number, i: number) => top + (Math.max(sideHeight(incoming.length), sideHeight(outgoing.length)) - sideHeight(n)) / 2 + i * (FOCUS_SIDE_H + FOCUS_SIDE_GAP);
  const leftX = 24;
  const rightX = width - 24 - FOCUS_SIDE_W;
  const height = Math.max(centerTop + centerH, top + Math.max(sideHeight(incoming.length), sideHeight(outgoing.length))) + 40;
  const plain = center.columns.filter(c => !c.calculated);
  const times = plain.filter(c => roleOf(center, c, snapshot.relations).role === 'TIME').length;
  const calc = center.columns.length - plain.length;
  const ruleCount = rules.filter(r => ruleRefs(r.text, snapshot.models).some(ref => ref.startsWith(`${center.name}.`)) || r.text.includes(center.name)).length;
  const objects = new Set([...incoming, ...outgoing].map(l => l.model.name)).size;
  const label = (model: SemanticModel, names: string[]) => names.map(n => { const c = model.columns.find(x => x.name === n); return c ? columnLabel(c) : n; }).join('、') || '关联';
  const tone = (m: SemanticModel) => m.tableRole === 'FACT' ? 'fact' : m.derived ? 'sql' : 'dim';
  const sideCard = (link: FocusLink, x: number, y: number) => <div key={`${link.relation.name}:${x}`} className={`sw-fside ${markedModel(link.model) ? `sw-onode-${markedModel(link.model)}` : ''}`} style={{ left: x, top: y, width: FOCUS_SIDE_W, height: FOCUS_SIDE_H }}>
    <button type="button" className="sw-fside-main" title="以它为中心查看" onClick={() => onFocus(link.model.name)}>
      <span className={`sw-ico sw-ico-${tone(link.model)}`}>{tone(link.model) === 'fact' ? '事' : tone(link.model) === 'sql' ? '算' : '维'}</span>
      <span className="sw-onode-text"><strong title={link.model.name}>{link.model.name}</strong><small>{modelTag(link.model, false).label} · {link.model.columns.length} 个字段</small></span>
    </button>
    {link.others > 0 && <span className="sw-fmore" title={`它还有 ${link.others} 条其他关系`} style={x < centerX ? { left: -38 } : { right: -38 }}>+{link.others}</span>}
  </div>;
  const edge = (link: FocusLink, incomingSide: boolean, i: number, n: number) => {
    const y = sideY(n, i) + FOCUS_SIDE_H / 2;
    const cy = rowY(link.centerColumns[0]);
    const [x1, y1, x2, y2] = incomingSide ? [leftX + FOCUS_SIDE_W, y, centerX, cy] : [centerX + FOCUS_CENTER_W, cy, rightX, y];
    const reach = Math.max(40, (x2 - x1) / 2);
    const on = selection?.kind === 'relation' && selection.name === link.relation.name;
    const [a, b] = joinEnds(link.relation.joinType);
    const centerIsLeft = link.relation.left === center.name;
    const [nearX1, nearX2] = incomingSide ? [centerIsLeft ? b : a, centerIsLeft ? a : b] : [centerIsLeft ? a : b, centerIsLeft ? b : a];
    const text = incomingSide ? label(link.model, link.otherColumns) : label(center, link.centerColumns);
    return <span key={`${link.relation.name}:e`}>
      <svg className="sw-edges" width={width} height={height} aria-hidden="true" style={{ position: 'absolute', left: 0, top: 0 }}>
        <path d={`M${x1} ${y1} C${x1 + reach} ${y1} ${x2 - reach} ${y2} ${x2} ${y2}`} className={`sw-line ${on ? 'sw-line-on' : markedRelation(link.relation) ? `sw-line-${markedRelation(link.relation)}` : ''}`} />
      </svg>
      <span className="sw-end" style={{ left: x1 + 12, top: y1 - 10 }}>{nearX1 === 'N' ? '*' : nearX1}</span>
      <span className="sw-end" style={{ left: x2 - 12, top: y2 - 10 }}>{nearX2 === 'N' ? '*' : nearX2}</span>
      <button type="button" className={`sw-edge ${on ? 'sw-edge-on' : markedRelation(link.relation) ? `sw-edge-${markedRelation(link.relation)}` : ''}`} style={{ left: (x1 + x2) / 2, top: (y1 + y2) / 2 }}
        title={link.relation.condition} onClick={() => onSelect({ kind: 'relation', name: link.relation.name })}>{text}</button>
    </span>;
  };
  return <div className="sw-focus">
    <div className="sw-focus-bar">
      <button type="button" className="sw-icon-btn" onClick={onBack}>‹ 全图</button>
      <span className="sw-focus-sep" />
      <span className={`sw-ico sw-ico-${tone(center)}`}>{tone(center) === 'fact' ? '事' : tone(center) === 'sql' ? '算' : '维'}</span>
      <strong>{center.name}</strong>
      <span className="sw-muted sw-xs">直接关联 {incoming.length + outgoing.length} 条关系 · {objects} 个对象</span>
      <span className="sw-grow" />
      {canEdit && <button type="button" className="da-btn da-btn-sm" onClick={() => onSelect({ kind: 'newRelation', left: center.name })}>＋ 新建关系</button>}
    </div>
    <div ref={box} className="sw-focus-canvas">
      <div className="sw-focus-world" style={{ width, height }}>
        <span className="sw-focus-cap" style={{ left: leftX }}>多条对应一个「{center.name}」· {incoming.length}</span>
        <span className="sw-focus-cap" style={{ right: 24 }}>「{center.name}」对应的对象 · {outgoing.length}</span>
        {incoming.map((l, i) => edge(l, true, i, incoming.length))}
        {outgoing.map((l, i) => edge(l, false, i, outgoing.length))}
        {incoming.map((l, i) => sideCard(l, leftX, sideY(incoming.length, i)))}
        {outgoing.map((l, i) => sideCard(l, rightX, sideY(outgoing.length, i)))}
        {!incoming.length && !outgoing.length && <div className="sw-focus-none" style={{ top: centerTop + centerH + 16, left: centerX, width: FOCUS_CENTER_W }}>没有关系。{canEdit ? '点右上角"新建关系"把它和其他模型关联起来。' : ''}</div>}
        <div className={`sw-fcenter sw-fcenter-${tone(center)}`} style={{ left: centerX, top: centerTop, width: FOCUS_CENTER_W, height: centerH }}>
          <button type="button" className="sw-fcenter-head" style={{ height: FOCUS_HEAD }} onClick={() => onSelect({ kind: 'model', model: center.name })}>
            <span className={`sw-ico sw-ico-${tone(center)} sw-ico-lg`}>{tone(center) === 'fact' ? '事' : tone(center) === 'sql' ? '算' : '维'}</span>
            <span className="sw-onode-text"><strong>{center.name}</strong><small>{modelTag(center, false).label}{center.description ? ` · ${center.description}` : ''}</small></span>
          </button>
          {keys.map(k => <button key={k.column.name} type="button" style={{ height: FOCUS_ROW }}
            className={`sw-frow ${selection?.kind === 'field' && selection.model === center.name && selection.column === k.column.name ? 'sw-row-on' : changedColumn(center.name, k.column) ? `sw-row-${changedColumn(center.name, k.column)}` : ''}`}
            onClick={() => onSelect({ kind: 'field', model: center.name, column: k.column.name })}>
            <span className={`sw-k ${k.column.primaryKey ? 'sw-k-pk' : 'sw-k-rel'}`}>{k.column.primaryKey ? '主键' : '关联'}</span>
            <span className="sw-row-name">{columnLabel(k.column)}</span>{k.column.displayName && <span className="sw-mono sw-muted">{k.column.name}</span>}
            <span className="sw-grow" /><span className="sw-muted sw-xs">{k.column.primaryKey ? '身份键' : k.target ? `→ ${k.target}` : '关联键'}</span>
          </button>)}
          <div className="sw-fcenter-foot" style={{ height: FOCUS_FOOT }}>
            <span className="sw-muted sw-xs">普通 {plain.length - times} · 日期 {times} · 衍生 {calc} · 规则 {ruleCount}</span>
            <span className="sw-grow" />
            <button type="button" className="sw-link" onClick={() => onSelect({ kind: 'model', model: center.name })}>打开详情</button>
          </div>
        </div>
      </div>
    </div>
    <div className="sw-legend sw-legend-static">
      <span className="sw-muted">点击两侧对象，以它为中心继续查看</span>
      <span><span className="sw-fmore sw-fmore-inline">+n</span>还有 n 条其他关系</span>
      <span>* ── 1 多个对应一个</span>
    </div>
  </div>;
}

// ------------------------------------------------------------------ business rules (spec117 §4)

function RulesView({ rules, snapshot, selection, isNew, editable, onSelect, onMove }: {
  rules: RuleItem[];
  snapshot: SemanticSnapshot;
  selection: Selection;
  isNew: (rule: RuleItem) => MarkTone;
  editable: boolean;
  onSelect: (s: Selection) => void;
  onMove: (rule: RuleItem, heading: string) => Promise<void>;
}) {
  const [error, setError] = useState('');
  const shown = SECTIONS.filter(s => s.kind !== 'other' || rules.some(r => r.heading === s.heading));
  const other = rules.filter(r => !sectionOf(r.heading));
  const on = (r: RuleItem) => selection?.kind === 'rule' && selection.path === r.path && selection.text === r.text;
  const rowClass = (r: RuleItem) => `sw-rule-row ${on(r) ? 'sw-rule-on' : isNew(r) ? `sw-rule-${isNew(r)}` : ''}`;
  const refs = (r: RuleItem) => ruleRefs(r.text, snapshot.models);
  const Refs = ({ r }: { r: RuleItem }) => <>{refs(r).map(t => <span key={t} className="sw-ref">⇢ {t}</span>)}</>;
  const source = (r: RuleItem) => r.path.replace('knowledge/rules/', '');
  return <div className="sw-rules">
    <h2>业务规则</h2>
    <p className="sw-lead">问数助手每次回答前会读这些规则。规则按用途分成几类，每类对应 <code>knowledge/rules/</code> 文件里的一个二级标题（小节）；官方问数技能只按这些标题查找，不在这些标题下的规则不一定会被读到。</p>
    {error && <div role="alert" className="da-alert da-alert-error">{error}</div>}
    {shown.map(s => {
      const list = rules.filter(r => r.heading === s.heading);
      return <section key={s.heading} id={`sw-sec-${s.heading}`} className="sw-rsec">
        <div className="sw-rsec-head"><strong>{s.label}</strong><code>## {s.heading}</code><span className="sw-muted">{s.hint}</span><span className="sw-grow" />
          {editable && <button type="button" className="da-btn da-btn-sm" onClick={() => onSelect({ kind: 'newRule', heading: s.heading })}>＋ 新增</button>}</div>
        {list.length === 0 ? <div className="sw-empty">还没有{s.label}规则。</div> : <div className="sw-table-box"><table className="sw-rt">
          <thead>{s.kind === 'filter' ? <tr><th style={{ width: '18%' }}>适用于</th><th>条件</th><th style={{ width: '20%' }}>例外</th><th style={{ width: '20%' }}>涉及字段</th><th style={{ width: 90 }}>文件</th></tr>
            : s.kind === 'term' ? <tr><th style={{ width: '16%' }}>业务说法</th><th style={{ width: '16%' }}>同义词</th><th>对应</th><th style={{ width: '20%' }}>涉及字段</th><th style={{ width: 90 }}>文件</th></tr>
              : s.kind === 'canonical' ? <tr><th style={{ width: '24%' }}>适用于</th><th>从哪张表或视图取数</th><th style={{ width: '20%' }}>涉及字段</th><th style={{ width: 90 }}>文件</th></tr>
                : <tr><th>规则</th><th style={{ width: '20%' }}>涉及字段</th><th style={{ width: 90 }}>文件</th></tr>}</thead>
          <tbody>{list.map(r => {
            const structured = s.kind === 'filter' ? r.filter : s.kind === 'term' ? r.term : s.kind === 'canonical' ? r.canonical : null;
            return <tr key={`${r.path}:${r.text}`} className={rowClass(r)} tabIndex={0} onKeyDown={event => {
            if (event.target !== event.currentTarget || !['Enter', ' '].includes(event.key)) return;
            event.preventDefault();
            onSelect({ kind: 'rule', path: r.path, text: r.text });
          }} onClick={() => onSelect({ kind: 'rule', path: r.path, text: r.text })}>
            {!structured ? <td colSpan={s.kind === 'canonical' ? 4 : s.kind === 'other' ? 3 : 5}>
              <RawRule text={r.text} /><div className="sw-rule-meta"><Refs r={r} /><span>{source(r)}</span></div>
            </td> : <>
            {r.filter && s.kind === 'filter' && <><td><strong>{r.filter.scope}</strong></td><td>{r.filter.condition}</td><td className="sw-muted">{r.filter.exception || '—'}</td></>}
            {r.term && s.kind === 'term' && <><td><strong>{r.term.term}</strong></td><td className="sw-muted">{r.term.synonyms.join('、') || '—'}</td><td>{r.term.meaning}</td></>}
            {r.canonical && s.kind === 'canonical' && <><td><strong>{r.canonical.scope}</strong></td><td>{r.canonical.target}</td></>}
            <td><Refs r={r} /></td><td className="sw-muted sw-xs">{source(r)}</td>
            </>}
          </tr>; })}</tbody>
        </table></div>}
      </section>;
    })}
    {other.length > 0 && <section id="sw-sec-other" className="sw-rsec">
      <div className="sw-rsec-head"><strong>未归类</strong><span className="sw-muted">不在官方标题下的规则（常见于文档导入时自建的标题），问数助手不一定读到，建议移到对应小节</span></div>
      {other.map(r => <div key={`${r.path}:${r.text}`} className={`sw-warn-box ${on(r) ? 'sw-rule-on' : ''}`}>
        <button type="button" className="sw-warn-text-btn" onClick={() => onSelect({ kind: 'rule', path: r.path, text: r.text })}>
          <RawRule text={r.text} /><span className="sw-muted sw-xs">{source(r)}{r.heading ? ` · ## ${r.heading}` : ''}</span></button>
        {editable && <span className="sw-move">移到
          {SECTIONS.slice(0, 3).map(s => <button key={s.heading} type="button" className="da-btn da-btn-sm" onClick={() => { setError(''); onMove(r, s.heading).catch(e => setError(message(e))); }}>{s.label}</button>)}
        </span>}
      </div>)}
    </section>}
  </div>;
}

function RawRule({ text }: { text: string }) {
  return <span className="sw-raw">{text.split('\n').map((line, i) => <span key={i}>{i > 0 && <br />}{line}</span>)}</span>;
}

// ------------------------------------------------------------------ drawer

function Drawer({ groupId, snapshot, selection, files, views, rules, problems, editable, canEdit, blocked, onAsk, changedColumn, newRule, onSelect, onSave, onGo, onChanged, editGuard }: {
  groupId: string;
  snapshot: SemanticSnapshot;
  selection: Selection;
  files: Map<string, string>;
  views: { name: string; sql: string; description: string | null }[];
  rules: RuleItem[];
  problems: Problem[];
  editable: boolean;
  canEdit: boolean;
  blocked: string | null;
  onAsk?: (text: string) => void;
  changedColumn: (model: string, column: SemanticColumn) => MarkTone;
  newRule: (rule: RuleItem) => MarkTone;
  onSelect: (s: Selection) => void;
  onSave: (edit: DraftItemEdit) => Promise<void>;
  onGo: (p: Problem) => void;
  onChanged: () => void;
  editGuard: FieldEditGuard;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');
  const [calcOpen, setCalcOpen] = useState(false);
  const clearFeedback = useCallback(() => { setError(''); setSaved(''); }, []);
  const run = async (action: () => Promise<void>, done: string, after?: () => void) => {
    setBusy(true); clearFeedback();
    try { await action(); setSaved(done); after?.(); } catch (e) { setError(message(e)); } finally { setBusy(false); }
  };
  const feedback = <>
    {error && <div role="alert" className="sw-error">{error}</div>}
    {saved && <div role="status" className="sw-ok">{saved}</div>}
  </>;
  const ask = (text: string) => onAsk && <button type="button" className="da-btn da-btn-sm" onClick={() => onAsk(text)}>问助手</button>;
  const disabledTitle = blocked ?? undefined;

  if (!selection) return null;

  if (selection.kind === 'problems') return <>
    <div className="sw-dh"><strong>需要处理</strong><span className="sw-grow" /><CloseButton onSelect={onSelect} /></div>
    <div className="sw-db">
      {problems.length === 0 ? <div className="sw-empty">没有需要处理的问题。</div>
        : problems.map(p => <div key={p.key} className="sw-prob"><span className="sw-chip sw-chip-warn">{p.tag}</span><span className="sw-grow">{p.text}</span>
          {p.go && <button type="button" className="sw-link" onClick={() => onGo(p)}>去处理</button>}</div>)}
    </div>
  </>;

  if (selection.kind === 'view') {
    const v = views.find(x => x.name === selection.name);
    return <>
      <div className="sw-dh"><span className="sw-ico sw-ico-view">视</span><strong>{selection.name}</strong><span className="sw-grow" /><CloseButton onSelect={onSelect} /></div>
      <div className="sw-db">
        <p>{v?.description || '公共视图'}</p>
        <pre className="sw-code">{v?.sql}</pre>
        <p className="sw-muted sw-xs">公共视图的修改、核查与验证在「公共视图」页签进行。</p>
      </div>
    </>;
  }

  if (selection.kind === 'model' || selection.kind === 'field') {
    const model = snapshot.models.find(m => m.name === selection.model);
    if (!model) return <Gone onSelect={onSelect} />;
    const column = selection.kind === 'field' ? model.columns.find(c => c.name === selection.column) : undefined;
    if (selection.kind === 'field' && !column) return <Gone onSelect={onSelect} />;
    const openCalc = () => { clearFeedback(); setCalcOpen(true); };
    const saveField = (edit: DraftItemEdit, done: string, after?: () => void) => run(() => onSave(edit), done, after);
    return <>
      {column ? <FieldDrawer {...{ model, column, snapshot, rules, editable, canEdit, disabledTitle, onSelect, onAsk, busy, editGuard }}
        onEditCalculated={openCalc} onClearFeedback={clearFeedback} onSave={saveField} feedback={calcOpen ? null : feedback} />
        : <ModelDrawer {...{ groupId, model, snapshot, files, rules, editable, canEdit, blocked, onSelect, onChanged, onAsk, changedColumn, newRule }}
          onAddCalculated={openCalc} feedback={calcOpen ? null : feedback} />}
      {calcOpen && editable && <CalculatedColumnDialog model={model} column={column} relations={snapshot.relations}
        busy={busy} blocked={blocked} feedback={feedback} onClose={() => { setError(''); setCalcOpen(false); }}
        onSave={edit => void saveField(edit, column ? '衍生列已保存到草稿。' : '衍生列已加入草稿。', () => setCalcOpen(false))} />}
    </>;
  }

  if (selection.kind === 'relation' || selection.kind === 'newRelation') {
    const relation = selection.kind === 'relation' ? snapshot.relations.find(r => r.name === selection.name) : undefined;
    if (selection.kind === 'relation' && !relation) return <Gone onSelect={onSelect} />;
    return <RelationDrawer {...{ snapshot, relation, editable, canEdit, disabledTitle, onSelect }} left={selection.kind === 'newRelation' ? selection.left : undefined}
      onSave={(edit, done, after) => run(() => onSave(edit), done, after)} busy={busy} feedback={feedback} ask={ask} />;
  }

  if (selection.kind === 'rule' || selection.kind === 'newRule') {
    const rule = selection.kind === 'rule' ? rules.find(r => r.path === selection.path && r.text === selection.text) : undefined;
    if (selection.kind === 'rule' && !rule) return <Gone onSelect={onSelect} />;
    const section = sectionOf(rule ? rule.heading : selection.kind === 'newRule' ? selection.heading : null);
    return <RuleDrawer {...{ rule, section, snapshot, editable, canEdit, disabledTitle, onSelect }}
      onSave={(edit, done, after) => run(() => onSave(edit), done, after)} busy={busy} feedback={feedback} ask={ask} />;
  }
  return null;
}

function CloseButton({ onSelect }: { onSelect: (s: Selection) => void }) {
  return <button type="button" className="sw-link" onClick={() => onSelect(null)}>关闭</button>;
}

function Gone({ onSelect }: { onSelect: (s: Selection) => void }) {
  return <div className="sw-db"><div className="sw-empty">这个对象已经不在当前版本中。</div><button type="button" className="sw-link" onClick={() => onSelect(null)}>返回</button></div>;
}

function ModelDrawer({ groupId, model, snapshot, files, rules, editable, canEdit, blocked, onSelect, onChanged, onAsk, changedColumn, newRule, onAddCalculated, feedback }: {
  groupId: string; model: SemanticModel; snapshot: SemanticSnapshot; files: Map<string, string>; rules: RuleItem[];
  editable: boolean; canEdit: boolean; blocked: string | null; onSelect: (s: Selection) => void; onChanged: () => void; onAsk?: (text: string) => void;
  changedColumn: (model: string, column: SemanticColumn) => MarkTone; newRule: (rule: RuleItem) => MarkTone;
  onAddCalculated: () => void; feedback: ReactNode;
}) {
  const [tab, setTab] = useState<'fields' | 'rules' | 'info' | 'yaml'>('fields');
  const [editing, setEditing] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const text = model.path ? files.get(model.path) : undefined;
  const plainAll = model.columns.filter(c => !c.calculated);
  const times = plainAll.filter(c => roleOf(model, c, snapshot.relations).role === 'TIME');
  const plain = plainAll.filter(c => !times.includes(c));
  const calc = model.columns.filter(c => c.calculated);
  const relations = relationsOf(snapshot, model.name);
  const related = rules.filter(r => ruleRefs(r.text, snapshot.models).some(ref => ref.startsWith(`${model.name}.`)) || r.text.includes(model.name));
  const tag = modelTag(model, false);
  const saveYaml = async () => {
    if (editing === null || !model.path) return;
    setBusy(true); setError(''); setSaved(false);
    try { await editDraftFile(groupId, model.path, editing); setEditing(null); setSaved(true); onChanged(); } catch (e) { setError(message(e)); } finally { setBusy(false); }
  };
  // Columns business rules rely on: renaming or dropping them changes what the rules mean.
  const ruleUse = new Map<string, RuleItem[]>();
  for (const r of rules) for (const ref of ruleRefs(r.text, snapshot.models))
    if (ref.startsWith(`${model.name}.`)) ruleUse.set(ref.slice(model.name.length + 1), [...(ruleUse.get(ref.slice(model.name.length + 1)) ?? []), r]);
  const FieldItem = ({ c }: { c: SemanticColumn }) => {
    const used = ruleUse.get(c.name) ?? [];
    return <button type="button" className="sw-li" onClick={() => onSelect({ kind: 'field', model: model.name, column: c.name })}>
      <span className={`sw-k ${c.calculated ? 'sw-k-calc' : c.primaryKey ? 'sw-k-pk' : 'sw-k-plain'}`}>{c.calculated ? '衍生' : c.primaryKey ? '主键' : roleOf(model, c, snapshot.relations).role === 'TIME' ? '日期' : '普通'}</span>
      <span className="sw-li-main"><span><b>{columnLabel(c)}</b> {c.displayName && <span className="sw-mono sw-muted">{c.name}</span>}</span>
        <span className="sw-li-desc">{c.calculated ? `= ${c.expression ?? ''}` : c.description || '还没有说明'}</span></span>
      <ToneTag tone={changedColumn(model.name, c)} />
      {used.length > 0 && <span className="sw-rule-use" title={`被 ${used.length} 条业务规则引用：\n${used.map(r => ruleSummary(r)).join('\n')}`}>规则 {used.length}</span>}
    </button>;
  };
  return <>
    <div className="sw-dh sw-dh-tabs">
      <div className="sw-dh-row"><span className={`sw-ico ${model.tableRole === 'FACT' ? 'sw-ico-fact' : model.derived ? 'sw-ico-sql' : 'sw-ico-dim'}`}>{model.tableRole === 'FACT' ? '事' : model.derived ? '算' : '维'}</span>
        <strong title={model.name}>{model.name}</strong><span className="sw-chip">{tag.label}</span><span className="sw-grow" /><CloseButton onSelect={onSelect} /></div>
      <div role="tablist" className="sw-dtabs">
        {([['fields', '字段'], ['rules', `规则与原文 ${related.length}`], ['info', '表信息'], ['yaml', 'YAML']] as const).map(([k, l]) =>
          <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{l}</button>)}
      </div>
    </div>
    <div className="sw-db">
      {tab === 'fields' && <>
        <FieldGroup label="普通列" count={plain.length}>
          {plain.map(c => <FieldItem key={c.name} c={c} />)}
        </FieldGroup>
        <FieldGroup label="日期列" count={times.length}>
          {times.map(c => <FieldItem key={c.name} c={c} />)}
          {!times.length && <p className="sw-hint">没有识别到日期列。</p>}
        </FieldGroup>
        {editable && <button type="button" className="sw-link sw-add-calc" aria-label="新增衍生列"
          disabled={!canEdit} title={blocked ?? undefined} onClick={onAddCalculated}>＋ 新增衍生列</button>}
        {feedback}
        <FieldGroup label="衍生列" count={calc.length}>
          {calc.map(c => <FieldItem key={c.name} c={c} />)}
          {!calc.length && <p className="sw-hint">暂无衍生列。</p>}
        </FieldGroup>
        <FieldGroup label="关联字段" count={relations.length}>
        {relations.map(r => {
          const target = r.left === model.name ? r.right : r.left;
          const keys = (r.left === model.name ? r.sourceColumns : r.targetColumns).join('、');
          return <button key={r.name} type="button" className="sw-li" onClick={() => onSelect({ kind: 'relation', name: r.name })}>
            <span className="sw-k sw-k-rel">关联</span><span className="sw-li-main"><span><b>{target}</b></span><span className="sw-li-desc">按 {keys || r.condition} 关联，问数时可直接使用 {target} 的字段</span></span>
            <span className="sw-role">{joinLabel(r.left === model.name ? r.joinType : reverse(r.joinType))}</span>
          </button>;
        })}
        {!relations.length && <p className="sw-hint">没有关系。{editable && '可以在左侧"关系"旁点 + 新增。'}</p>}
        </FieldGroup>
      </>}
      {tab === 'info' && <dl className="sw-dl">
        <dt>表角色</dt><dd>{model.tableRole === 'FACT' ? '事实表：记录发生的事（订单、访问）' : model.tableRole === 'DIMENSION' ? '维度表：描述对象（客户、商品）' : model.derived ? '由 SQL 定义的模型' : '未标注'}</dd>
        <dt>说明</dt><dd>{model.description || '—'}</dd>
        <dt>字段</dt><dd>{plain.length} 个普通列，{times.length} 个日期列，{calc.length} 个衍生列</dd>
        <dt>文件</dt><dd className="sw-mono">{model.path ?? '—'}</dd>
        {editable && <dd className="sw-hint" style={{ gridColumn: '1 / -1' }}>表说明与表角色请在 YAML 页修改，或让建模助手修改。</dd>}
      </dl>}
      {tab === 'rules' && <RuleSources groupId={groupId} model={model} rules={related} editable={editable} onSelect={onSelect} newRule={newRule} />}
      {tab === 'yaml' && (text === undefined ? <div className="sw-empty">这个版本没有可显示的 YAML。</div> : <>
        <div className="sw-dh-row"><span className="sw-mono sw-muted">{model.path}</span><span className="sw-grow" />
          {editable && <button type="button" className="da-btn da-btn-sm" disabled={!canEdit || busy} title={blocked ?? undefined}
            onClick={() => { setEditing(editing === null ? text : null); setError(''); setSaved(false); }}>{editing === null ? '编辑' : '取消编辑'}</button>}</div>
        {editing === null ? <div className="sw-yaml"><YamlContent text={text} /></div>
          : <textarea className="sw-yaml-edit" aria-label="编辑 YAML" spellCheck={false} value={editing} onChange={e => setEditing(e.target.value)} />}
        {editing !== null && <div className="sw-actions"><button type="button" className="da-btn da-btn-primary da-btn-sm" disabled={busy} onClick={() => void saveYaml()}>{busy ? 'Wren 校验中…' : '校验并保存到草稿'}</button></div>}
        {error && <div role="alert" className="sw-error">{error}</div>}
        {saved && <div className="sw-ok">已通过 Wren 校验并保存到草稿，记为"你的编辑"。</div>}
        <p className="sw-hint">这就是 Wren 读取的文件。{editable ? '有管理权限的成员可以直接编辑，保存前会跑与助手相同的写入校验（YAML、validate、build、dry-plan），不通过不写入。' : '只读。'}</p>
      </>)}
    </div>
    {onAsk && <div className="sw-df"><button type="button" className="da-btn da-btn-sm" onClick={() => onAsk(`请调整模型 ${model.name}：`)}>问助手修改这张表</button></div>}
  </>;
}

/**
 * spec117 规则与原文: the rules that mention this model with their exact text, and the passages of
 * the business documents that mention the model or its columns (MANAGE only; documents are draft).
 */
function RuleSources({ groupId, model, rules, editable, onSelect, newRule }: {
  groupId: string; model: SemanticModel; rules: RuleItem[]; editable: boolean; onSelect: (s: Selection) => void; newRule: (rule: RuleItem) => MarkTone;
}) {
  const [docs, setDocs] = useState<{ name: string; content: string }[] | null>(null);
  useEffect(() => {
    if (!editable) return undefined;
    let cancelled = false;
    void listDocuments(groupId).then(list => Promise.all(list.filter(d => d.status !== 'DELETED').map(d => getDocumentContent(groupId, d.name))))
      .then(value => { if (!cancelled) setDocs(value); }).catch(() => { if (!cancelled) setDocs([]); });
    return () => { cancelled = true; };
  }, [groupId, editable]);
  const terms = [model.name, ...model.columns.flatMap(c => [c.displayName ?? '', c.name.length >= 4 ? c.name : ''])].filter(t => t.trim().length >= 2);
  const passages = (docs ?? []).flatMap(d => d.content.split(/\n\s*\n/).map(p => p.trim()).filter(p => p && terms.some(t => p.includes(t)))
    .map(p => ({ doc: d.name, text: p.length > 400 ? `${p.slice(0, 400)}…` : p }))).slice(0, 8);
  const sourceOf = (r: RuleItem) => (docs ?? []).find(d => r.text.includes(d.name) || r.text.includes(d.name.replace(/\.md$/, '')))?.name;
  return <>
    <h4>引用它的规则 <span className="sw-muted">{rules.length}</span></h4>
    {rules.length === 0 && <div className="sw-empty">没有规则提到这张表。</div>}
    {rules.map(r => <div key={`${r.path}:${r.text}`} className="sw-src">
      <button type="button" className="sw-li" onClick={() => onSelect({ kind: 'rule', path: r.path, text: r.text })}>
        <span className="sw-ico sw-ico-rule">规</span><span className="sw-li-main"><span>{ruleSummary(r)} <ToneTag tone={newRule(r)} /></span>
          <span className="sw-li-desc">{sectionOf(r.heading)?.label ?? '未归类'} · {r.path.replace('knowledge/rules/', '')}{sourceOf(r) ? ` · 来源 ${sourceOf(r)}` : ''}</span></span>
      </button>
      <details className="sw-src-raw"><summary>规则原文</summary><pre className="sw-code">{r.text}</pre></details>
    </div>)}
    <h4>业务文档中的原文 <span className="sw-muted">{docs ? passages.length : ''}</span></h4>
    {!editable ? <p className="sw-hint">业务文档只对有管理权限的成员可见。</p>
      : docs === null ? <p className="sw-hint">读取文档中…</p>
        : passages.length === 0 ? <p className="sw-hint">{docs.length ? '文档中没有提到这张表或它的字段。' : '这个知识库还没有业务文档，可在"资料"页上传。'}</p>
          : passages.map((p, i) => <div key={i} className="sw-passage"><span className="sw-chip">{p.doc}</span><p>{p.text}</p></div>)}
  </>;
}

const reverse = (type: string) => type === 'MANY_TO_ONE' ? 'ONE_TO_MANY' : type === 'ONE_TO_MANY' ? 'MANY_TO_ONE' : type;

function fieldValues(column: SemanticColumn) {
  return {
    displayName: column.displayName ?? '',
    description: column.description ?? '',
    synonyms: (column.synonyms ?? []).join('、'),
    semanticType: column.semanticType ?? null,
  };
}

function FieldGroup({ label, count, children }: { label: string; count: number; children: ReactNode }) {
  const [open, setOpen] = useState(true);
  return <details className="sw-field-group" open={open} onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>{label} <span className="sw-muted">{count}</span></summary>
    {children}
  </details>;
}

type FieldValues = ReturnType<typeof fieldValues>;

function FieldDrawer({ model, column, snapshot, rules, editable, canEdit, disabledTitle, onSelect, onAsk, onEditCalculated, onClearFeedback, onSave, busy, feedback, editGuard }: {
  model: SemanticModel; column: SemanticColumn; snapshot: SemanticSnapshot; rules: RuleItem[]; editable: boolean; canEdit: boolean; disabledTitle?: string;
  onSelect: (s: Selection) => void; onAsk?: (text: string) => void;
  onEditCalculated: () => void; onClearFeedback: () => void; editGuard: FieldEditGuard;
  onSave: (edit: DraftItemEdit, done: string, after?: () => void) => Promise<void>; busy: boolean; feedback: ReactNode;
}) {
  const calculated = column.calculated;
  const inferred = roleOf(model, column, snapshot.relations);
  const [editor, setEditor] = useState<{ initial: FieldValues; values: FieldValues } | null>(null);
  const currentValues = fieldValues(column);
  const values = editor?.values ?? currentValues;
  const initial = editor?.initial ?? currentValues;
  const displayNameChanged = values.displayName !== initial.displayName;
  const descriptionChanged = values.description !== initial.description;
  const synonymsChanged = values.synonyms !== initial.synonyms;
  const roleChanged = values.semanticType !== initial.semanticType;
  const dirty = displayNameChanged || descriptionChanged || synonymsChanged || roleChanged;
  const editing = editable && editor !== null;
  const inputDisabled = !canEdit || busy;
  const role = values.semanticType as Role | null;
  const hasRole = !!role && Object.prototype.hasOwnProperty.call(ROLE_LABEL, role);
  const shownRole = hasRole ? role : inferred.role;
  const ref = `${model.name}.${column.name}`;
  const usedBy = rules.filter(r => ruleRefs(r.text, snapshot.models).includes(ref));

  useLayoutEffect(() => {
    if (!editing) return;
    editGuard.current = () => {
      if (busy) return false;
      if (dirty && !window.confirm('当前字段有未保存的修改。放弃这些修改并继续？')) return false;
      onClearFeedback();
      setEditor(null);
      return true;
    };
    const warnBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    if (dirty) window.addEventListener('beforeunload', warnBeforeUnload);
    return () => {
      editGuard.current = null;
      if (dirty) window.removeEventListener('beforeunload', warnBeforeUnload);
    };
  }, [editing, dirty, busy, editGuard, onClearFeedback]);

  const edit = () => {
    onClearFeedback();
    setEditor({ initial: currentValues, values: currentValues });
  };
  const change = (patch: Partial<FieldValues>) => {
    setEditor(previous => previous && { ...previous, values: { ...previous.values, ...patch } });
  };
  const cancel = () => { onClearFeedback(); setEditor(null); };
  const submit = () => {
    if (!editing || !dirty || inputDisabled) return;
    const patch: DraftItemEdit = {
      category: 'COLUMN', model: model.name, column: column.name,
      displayName: displayNameChanged ? values.displayName : undefined,
      description: descriptionChanged ? values.description : undefined,
      synonyms: synonymsChanged ? splitList(values.synonyms) : undefined,
      semanticType: roleChanged ? values.semanticType ?? '' : undefined,
    };
    void onSave(patch, '已保存到草稿。', () => setEditor(null));
  };
  return <>
    <div className="sw-dh">
      <button type="button" className="sw-back" onClick={() => onSelect({ kind: 'model', model: model.name })}>← {model.name}</button>
      <div className="sw-dh-row">
        <span className={`sw-k ${calculated ? 'sw-k-calc' : column.primaryKey ? 'sw-k-pk' : inferred.role === 'TIME' ? 'sw-k-time' : 'sw-k-plain'}`}>
          {calculated ? '衍生' : column.primaryKey ? '主键' : inferred.role === 'TIME' ? '日期' : '普通'}
        </span>
        <div className="sw-field-title">
          <strong>{columnLabel(column)}</strong>
          <span className="sw-mono sw-muted">{column.displayName && column.displayName !== column.name ? `${column.name} · ` : ''}{column.type || '—'}</span>
        </div>
        {editable && !editing && <button type="button" className="da-btn da-btn-sm" disabled={inputDisabled} title={disabledTitle}
          onClick={calculated ? onEditCalculated : edit}>{calculated ? '编辑衍生列' : '编辑'}</button>}
        {editing && <span className="sw-chip">{dirty ? '未保存' : '编辑中'}</span>}
      </div>
    </div>
    <div className="sw-db">
      {editing ? <label className="sw-fld"><span>显示名</span>
        <input className="da-input" autoFocus disabled={inputDisabled} value={values.displayName} onChange={event => change({ displayName: event.target.value })} placeholder="例如 场景名称（可不填）" />
      </label> : <div className="sw-fld"><span>显示名</span><div className="sw-field-value">{values.displayName || '—'}</div></div>}
      <div className="sw-fld"><span>语义角色</span>
        {editing ? <div role="group" aria-label="语义角色" className="sw-seg">
          {ROLES.map(r => <button key={r} type="button" aria-pressed={shownRole === r} className={shownRole === r ? 'on' : ''} disabled={inputDisabled}
            onClick={() => change({ semanticType: r })}>{ROLE_LABEL[r]}</button>)}
        </div> : <div className="sw-field-value">{ROLE_LABEL[shownRole]}</div>}
        <span className="sw-hint">{ROLE_HINT[shownRole]}{!hasRole ? '（推断）' : ''}</span>
      </div>
      {calculated && <div className="sw-fld"><span>表达式</span><pre className="sw-code sw-expression">{column.expression || '—'}</pre></div>}
      {editing ? <>
        <label className="sw-fld"><span>说明</span>
          <textarea className="da-input" rows={4} disabled={inputDisabled} value={values.description} onChange={event => change({ description: event.target.value })} placeholder="业务含义、单位、取值说明" />
        </label>
        <label className="sw-fld"><span>同义词</span>
          <input className="da-input" disabled={inputDisabled} value={values.synonyms} onChange={event => change({ synonyms: event.target.value })} placeholder="用顿号分隔" />
        </label>
      </> : <>
        <div className="sw-fld"><span>说明</span><div className="sw-field-value">{values.description || '—'}</div></div>
        <div className="sw-fld"><span>同义词</span><div className="sw-field-value">{values.synonyms || '—'}</div></div>
      </>}
      {!!column?.values?.length && <div className="sw-fld"><span>取值</span><div className="sw-tags">{column.values.map(v => <span key={v} className="sw-tag">{v}</span>)}</div></div>}
      {usedBy.length > 0 && <div className="sw-fld"><span>引用它的规则</span>{usedBy.map(r => <button key={`${r.path}:${r.text}`} type="button" className="sw-li" onClick={() => onSelect({ kind: 'rule', path: r.path, text: r.text })}>
        <span className="sw-ico sw-ico-rule">规</span><span className="sw-li-main"><span>{ruleSummary(r)}</span></span></button>)}</div>}
      {column?.userDescribed && <p className="sw-hint">说明由人工填写，建模助手不会改写。</p>}
      {feedback}
    </div>
    {editing ? <div className="sw-df">
      <span className="sw-grow" />
      {dirty && <button type="button" className="da-btn da-btn-primary da-btn-sm" disabled={inputDisabled} title={disabledTitle}
        onClick={submit}>{busy ? '保存中…' : '保存到草稿'}</button>}
      <button type="button" className="da-btn da-btn-sm" disabled={busy} onClick={cancel}>{dirty ? '取消' : '退出编辑'}</button>
    </div> : editable && (onAsk || calculated) && <div className="sw-df">
      {onAsk && <button type="button" className="da-btn da-btn-sm" onClick={() => onAsk(`请完善 ${model.name}.${column.name} 的说明和同义词：`)}>问助手</button>}
      <span className="sw-grow" />
      {calculated && <button type="button" className="da-btn da-btn-sm sw-danger" disabled={!canEdit || busy} title={disabledTitle}
        onClick={() => { if (window.confirm(`删除衍生字段 ${column.name}？引用它的规则和问答需要另行检查。`)) void onSave({ category: 'COLUMN', model: model.name, column: column.name, delete: true }, '已删除', () => onSelect({ kind: 'model', model: model.name })); }}>删除</button>}
    </div>}
  </>;
}

function RelationDrawer({ snapshot, relation, left, editable, canEdit, disabledTitle, onSelect, onSave, busy, feedback, ask }: {
  snapshot: SemanticSnapshot; relation?: SemanticRelation; left?: string; editable: boolean; canEdit: boolean; disabledTitle?: string;
  onSelect: (s: Selection) => void; onSave: (edit: DraftItemEdit, done: string, after?: () => void) => Promise<void>; busy: boolean; feedback: ReactNode;
  ask: (text: string) => ReactNode;
}) {
  const creating = !relation;
  const models = snapshot.models;
  const [leftModel, setLeftModel] = useState(relation?.left ?? left ?? models[0]?.name ?? '');
  const [rightModel, setRightModel] = useState(relation?.right ?? models.find(m => m.name !== (left ?? models[0]?.name))?.name ?? '');
  const [leftColumn, setLeftColumn] = useState(relation?.sourceColumns[0] ?? '');
  const [rightColumn, setRightColumn] = useState(relation?.targetColumns[0] ?? '');
  const [joinType, setJoinType] = useState(relation?.joinType || 'MANY_TO_ONE');
  const [description, setDescription] = useState(relation?.description ?? '');
  const columnsOf = (name: string) => models.find(m => m.name === name)?.columns.filter(c => !c.calculated) ?? [];
  const readOnly = !editable;
  return <>
    <div className="sw-dh"><div className="sw-dh-row"><span className="sw-ico sw-ico-rel">关</span><strong>{creating ? '新增关系' : `${relation!.left} → ${relation!.right}`}</strong><span className="sw-grow" /><CloseButton onSelect={onSelect} /></div></div>
    <div className="sw-db">
      <div className="sw-fld"><span>关联条件</span>
        {creating ? <>
          <div className="sw-pair"><select className="da-input" value={leftModel} onChange={e => { setLeftModel(e.target.value); setLeftColumn(''); }}>{models.map(m => <option key={m.name}>{m.name}</option>)}</select>
            <select className="da-input" value={leftColumn} onChange={e => setLeftColumn(e.target.value)}><option value="">选择字段</option>{columnsOf(leftModel).map(c => <option key={c.name} value={c.name}>{columnLabel(c)}{c.displayName ? ` (${c.name})` : ''}</option>)}</select></div>
          <div className="sw-eq">=</div>
          <div className="sw-pair"><select className="da-input" value={rightModel} onChange={e => { setRightModel(e.target.value); setRightColumn(''); }}>{models.filter(m => m.name !== leftModel).map(m => <option key={m.name}>{m.name}</option>)}</select>
            <select className="da-input" value={rightColumn} onChange={e => setRightColumn(e.target.value)}><option value="">选择字段</option>{columnsOf(rightModel).map(c => <option key={c.name} value={c.name}>{columnLabel(c)}{c.displayName ? ` (${c.name})` : ''}</option>)}</select></div>
        </> : <code className="sw-code">{relation!.condition}</code>}
      </div>
      <div className="sw-fld"><span>对应关系（{creating ? leftModel : relation!.left} 对 {creating ? rightModel : relation!.right}）</span>
        <div role="group" aria-label="对应关系" className="sw-seg">
          {JOINS.map(j => <button key={j} type="button" aria-pressed={joinType === j} className={joinType === j ? 'on' : ''} disabled={readOnly} onClick={() => setJoinType(j)}>{joinLabel(j)}</button>)}
        </div>
        <span className="sw-hint">{JOIN_HINT[joinType]}</span>
      </div>
      <label className="sw-fld"><span>说明</span><textarea className="da-input" rows={2} readOnly={readOnly} value={description} onChange={e => setDescription(e.target.value)} placeholder="例如 每笔订单对应一个客户；客户编号唯一" /></label>
      <div className="sw-file">写入 <span className="sw-mono">relationships.yml</span></div>
      {feedback}
    </div>
    {editable && <div className="sw-df">
      <button type="button" className="da-btn da-btn-primary da-btn-sm" disabled={!canEdit || busy || (creating && (!leftColumn || !rightColumn || !rightModel))} title={disabledTitle}
        onClick={() => void (creating
          ? onSave({ category: 'RELATIONSHIP', leftModel, leftColumn, rightModel, rightColumn, joinType, description }, '关系已通过 Wren 校验并加入草稿。', () => onSelect(null))
          : onSave({ category: 'RELATIONSHIP', name: relation!.name, joinType, description }, '已保存到草稿，记为"你的编辑"。'))}>{busy ? '保存中…' : creating ? '校验并新增' : '保存到草稿'}</button>
      {relation && ask(`请检查关系 ${relation.left} → ${relation.right}（${relation.condition}）：`)}
      <span className="sw-grow" />
      {relation && <button type="button" className="da-btn da-btn-sm sw-danger" disabled={!canEdit || busy} title={disabledTitle}
        onClick={() => { if (window.confirm(`删除关系 ${relation.left} → ${relation.right}？依赖它的问答需要另行检查。`)) void onSave({ category: 'RELATIONSHIP', name: relation.name, delete: true }, '已删除', () => onSelect(null)); }}>删除关系</button>}
    </div>}
  </>;
}

function RuleDrawer({ rule, section, snapshot, editable, canEdit, disabledTitle, onSelect, onSave, busy, feedback, ask }: {
  rule?: RuleItem; section: RuleSection | null; snapshot: SemanticSnapshot; editable: boolean; canEdit: boolean; disabledTitle?: string;
  onSelect: (s: Selection) => void; onSave: (edit: DraftItemEdit, done: string, after?: () => void) => Promise<void>; busy: boolean; feedback: ReactNode;
  ask: (text: string) => ReactNode;
}) {
  const creating = !rule;
  const kind = section?.kind ?? 'other';
  const structured = creating || !!rule?.filter || !!rule?.term || !!rule?.canonical;
  const [scope, setScope] = useState(rule?.filter?.scope ?? rule?.canonical?.scope ?? '');
  const [condition, setCondition] = useState(rule?.filter?.condition ?? '');
  const [exception, setException] = useState(rule?.filter?.exception ?? '');
  const [term, setTerm] = useState(rule?.term?.term ?? '');
  const [synonyms, setSynonyms] = useState((rule?.term?.synonyms ?? []).join('、'));
  const [meaning, setMeaning] = useState(rule?.term?.meaning ?? '');
  const [target, setTarget] = useState(rule?.canonical?.target ?? '');
  const [raw, setRaw] = useState(rule?.text ?? '');
  const [rawMode, setRawMode] = useState(!structured || kind === 'other');
  const readOnly = !editable;
  const text = rawMode ? raw.trim()
    : kind === 'filter' ? (scope.trim() && condition.trim() ? filterText({ scope, condition, exception }) : '')
      : kind === 'term' ? (term.trim() && meaning.trim() ? termText({ term, synonyms: splitList(synonyms), meaning }) : '')
        : kind === 'canonical' ? (scope.trim() && target.trim() ? canonicalText({ scope, target }) : '') : raw.trim();
  const heading = section?.heading ?? rule?.heading ?? 'Default filters';
  const path = rule?.path ?? (kind === 'term' ? TERM_FILE : RULE_FILE);
  const refs = ruleRefs(text, snapshot.models);
  const broken = brokenRefs(text, snapshot.models);
  return <>
    <div className="sw-dh"><div className="sw-dh-row"><span className="sw-ico sw-ico-rule">规</span><strong>{creating ? `新增${section?.label ?? ''}规则` : `${section?.label ?? '未归类'}规则`}</strong><span className="sw-grow" /><CloseButton onSelect={onSelect} /></div></div>
    <div className="sw-db">
      {!rawMode && kind === 'filter' && <>
        <label className="sw-fld"><span>适用于</span><input className="da-input" readOnly={readOnly} value={scope} onChange={e => setScope(e.target.value)} placeholder="例如 订单数、销售额" /></label>
        <label className="sw-fld"><span>条件</span><textarea className="da-input" rows={2} readOnly={readOnly} value={condition} onChange={e => setCondition(e.target.value)} placeholder="例如 排除已取消订单（演示订单.order_status <> '已取消'）" /></label>
        <label className="sw-fld"><span>例外</span><input className="da-input" readOnly={readOnly} value={exception} onChange={e => setException(e.target.value)} placeholder="例如 用户明确要求包含时不排除" /></label>
      </>}
      {!rawMode && kind === 'term' && <>
        <label className="sw-fld"><span>业务说法</span><input className="da-input" readOnly={readOnly} value={term} onChange={e => setTerm(e.target.value)} placeholder="例如 金卡客户" /></label>
        <label className="sw-fld"><span>同义词</span><input className="da-input" readOnly={readOnly} value={synonyms} onChange={e => setSynonyms(e.target.value)} placeholder="用顿号分隔" /></label>
        <label className="sw-fld"><span>对应</span><textarea className="da-input" rows={2} readOnly={readOnly} value={meaning} onChange={e => setMeaning(e.target.value)} placeholder="例如 演示客户.member_level = '金卡会员'" /></label>
      </>}
      {!rawMode && kind === 'canonical' && <>
        <label className="sw-fld"><span>适用于</span><input className="da-input" readOnly={readOnly} value={scope} onChange={e => setScope(e.target.value)} placeholder="例如 客户维度的订单分析" /></label>
        <label className="sw-fld"><span>从哪张表或视图取数</span><input className="da-input" readOnly={readOnly} value={target} onChange={e => setTarget(e.target.value)} placeholder="表或公共视图名" /></label>
      </>}
      {rawMode && <label className="sw-fld"><span>规则原文</span><textarea className="da-input" rows={5} readOnly={readOnly} value={raw} onChange={e => setRaw(e.target.value)} /></label>}
      {editable && kind !== 'other' && <button type="button" className="sw-link" onClick={() => { if (!rawMode) setRaw(text || raw); setRawMode(!rawMode); }}>
        {rawMode ? '按格式填写' : '直接编辑原文'}</button>}
      {!rawMode && !structured && <p className="sw-hint">这条规则不是页面句式，已按原文显示。</p>}
      <div className="sw-fld"><span>涉及字段</span><div>{refs.length ? refs.map(t => <span key={t} className="sw-ref">⇢ {t}</span>) : <span className="sw-hint">未识别到字段；写成"模型.字段"可被识别</span>}</div>
        {broken.length > 0 && <span className="sw-warn-text sw-xs">草稿中不存在：{broken.join('、')}</span>}</div>
      <div className="sw-fld"><span>写入文件的原文</span><pre className="sw-code">{`## ${heading}\n- ${text || '…'}`}</pre></div>
      <div className="sw-file">写入 <span className="sw-mono">{path}</span></div>
      {feedback}
    </div>
    {editable && <div className="sw-df">
      <button type="button" className="da-btn da-btn-primary da-btn-sm" disabled={!canEdit || busy || !text || (!creating && text === rule!.text)} title={disabledTitle}
        onClick={() => void (creating
          ? onSave({ category: 'SEMANTIC', path, heading, text }, '已加入草稿。', () => onSelect({ kind: 'rule', path, text }))
          : onSave({ category: 'SEMANTIC', path: rule!.path, original: rule!.text, text }, '已保存到草稿，记为"你的编辑"。', () => onSelect({ kind: 'rule', path: rule!.path, text })))}>{busy ? '保存中…' : '保存到草稿'}</button>
      {rule && ask(`请检查这条业务规则是否准确：${rule.text}`)}
      <span className="sw-grow" />
      {rule && <button type="button" className="da-btn da-btn-sm sw-danger" disabled={!canEdit || busy} title={disabledTitle}
        onClick={() => { if (window.confirm('删除这条规则？')) void onSave({ category: 'SEMANTIC', path: rule.path, original: rule.text, delete: true }, '已删除', () => onSelect(null)); }}>删除</button>}
    </div>}
  </>;
}
