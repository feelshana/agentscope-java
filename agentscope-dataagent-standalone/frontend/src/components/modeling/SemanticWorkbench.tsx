import { Fragment, lazy, Suspense, useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { MutableRefObject, PointerEvent as ReactPointerEvent, ReactNode } from 'react';
import { sessionUserId } from '../../api/auth';
import { editDraftItem, getDocumentContent, getDraftItems, listDocuments } from '../../api/draftReview';
import type { BusinessDocument, DraftItem, DraftItemEdit } from '../../api/draftReview';
import type { MdlIssue } from '../../api/semanticModeling';
import type { CanvasDraft } from './DraftBar';
import Icon from '../Icon';
import ReadableCode from '../ReadableCode';
import { WorkspaceMenu } from '../KnowledgeWorkspace';
import ModelYamlDialog from './ModelYamlDialog';
import CanvasChanges from './CanvasChanges';
import RulesWorkspace from './RulesWorkspace';
import RuleInspector from './RuleInspector';
import IncompleteViewDialog from './IncompleteViewDialog';
import { joinLabel, modelTag, relationsOf, unrelatedModels } from './semanticSnapshot';
import type { SemanticColumn, SemanticModel, SemanticRelation, SemanticSnapshot } from './semanticSnapshot';
import { NODE_GAP_Y, NODE_H, NODE_W, OVERVIEW_PAD, ROLE_HINT, ROLE_LABEL, focusOf, joinEnds, layoutOverview, roleOf } from './workbenchLayout';
import type { FocusLink, Positions, Role } from './workbenchLayout';
import {
  SECTIONS, brokenRefs, mentionsRuleIdentifier, parseRuleFile, publishedRuleVersion,
  ruleKey, ruleRefs, ruleSummary, sectionOf, splitList,
} from './ruleText';
import type { RuleItem, RuleSection } from './ruleText';
import './SemanticWorkbench.css';

const CalculatedColumnDialog = lazy(() => import('./CalculatedColumnDialog'));

export type WorkbenchView = 'graph' | 'rules';
type CheckFilter = 'attention' | 'advice';
type FieldEditGuard = MutableRefObject<(() => boolean) | null>;
type Selection =
  | { kind: 'model'; model: string }
  | { kind: 'field'; model: string; column: string; fromCheck?: boolean; editDescription?: boolean }
  | { kind: 'relation'; name: string }
  | { kind: 'newRelation'; left: string; fromCheck?: boolean }
  | { kind: 'rule'; path: string; text: string; line?: number; checkAction?: 'classify' | 'references' }
  | { kind: 'newRule'; heading: string }
  | { kind: 'view'; name: string }
  | { kind: 'problems'; filter?: CheckFilter }
  | null;

type Problem =
  | { kind: 'description'; model: SemanticModel; columns: SemanticColumn[] }
  | { kind: 'classification'; rule: RuleItem }
  | { kind: 'reference'; rule: RuleItem; references: string[] }
  | { kind: 'validation'; issue: MdlIssue };
const CHECK_GROUPS: { label: string; kinds: Problem['kind'][] }[] = [
  { label: '校验结果', kinds: ['validation'] },
  { label: '规则问题', kinds: ['reference', 'classification'] },
  { label: '缺字段说明', kinds: ['description'] },
];
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
const MIN_ZOOM = 0.4;
const MAX_ZOOM = 1.6;
/** Fitting never shrinks text below this; larger graphs scroll instead. */
const FIT_FLOOR = 0.7;
const clampZoom = (z: number) => Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Math.round(z * 100) / 100));
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const columnLabel = (c: SemanticColumn) => c.displayName || c.name;
const modelTitle = (model: SemanticModel) => model.description?.match(/^业务名称[：:]\s*([^\r\n]+)/m)?.[1].trim();
const isRuleFile = (path: string) => /^knowledge\/rules\/[^/]+\.md$/.test(path);

function readPositions(groupId: string): Positions {
  try { return JSON.parse(localStorage.getItem(`sw-node-pos:${groupId}`) ?? '{}') as Positions; } catch { return {}; }
}
function savePositions(groupId: string, positions: Positions) {
  try { localStorage.setItem(`sw-node-pos:${groupId}`, JSON.stringify(positions)); } catch { /* per-browser convenience only */ }
}

function readStandaloneModels(key: string): Set<string> {
  try {
    const stored: unknown = JSON.parse(localStorage.getItem(key) ?? '[]');
    return new Set(Array.isArray(stored) ? stored.filter((name): name is string => typeof name === 'string') : []);
  } catch { return new Set(); }
}

/**
 * spec117 语义模型工作台: models, relationships and rules on one page. The draft is edited in
 * place by MANAGE users; every save writes the Wren project files through /draft/items or
 * /draft/edit and lands in the same draft as conversation changes.
 */
export default function SemanticWorkbench({
  groupId, snapshot, baseline, files, baselineFiles, views, issues, editable, blocked, view, onViewChange,
  focusRelation, focusModel, onChanged, onAsk, checkTarget, fullscreenVersion, changes, onChanges,
  marked, canvasDraft, onUndoDraft, isSeen, onSeen, onReviewChanges, onExpandBoard, onEngineering, onDocuments, onReviewAsset,
  reveal, showDetail = true, compareWithBaseline = false, fieldEditGuard, rulesReady = true, defaultRailOpen = true,
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
  rulesReady?: boolean;
  defaultRailOpen?: boolean;
  views: { name: string; sql: string; description: string | null }[];
  issues: MdlIssue[];
  editable: boolean;
  /** Why edits are paused right now (the assistant is running), or null. */
  blocked: string | null;
  view: WorkbenchView;
  onViewChange: (view: WorkbenchView) => void;
  focusRelation?: string | null;
  focusModel?: string | null;
  onChanged: () => void;
  onAsk?: (text: string) => void;
  checkTarget?: HTMLElement | null;
  fullscreenVersion?: ReactNode;
  /** Draft changes to mark on the graph and rules (spec117: "在语义模型中查看" from a turn). */
  changes?: ChangeMark | null;
  onChanges?: (changes: ChangeMark | null) => void;
  canvasDraft?: CanvasDraft;
  onUndoDraft?: (path: string | null) => boolean;
  onEngineering?: (path?: string) => void;
  onReviewAsset?: (target: NonNullable<MdlIssue['assetReview']>) => void;
  onDocuments?: () => void;
  /** spec117 §3c: the marked changes, loaded by the page (shared with the 待确认 panel). */
  marked?: DraftItem[];
  isSeen?: (item: DraftItem) => boolean;
  onSeen?: (item: DraftItem) => void;
  onReviewChanges?: () => void;
  onExpandBoard?: () => void;
  /** A change to show, sent from the 待确认 panel; `id` changes on every request. */
  reveal?: { id: number; item: DraftItem } | null;
  /** The detail drawer shares the right panel with the assistant and 待确认. */
  showDetail?: boolean;
}) {
  const [selection, setSelectedObject] = useState<Selection>(focusRelation ? { kind: 'relation', name: focusRelation } : focusModel ? { kind: 'model', model: focusModel } : null);
  const [incompleteView, setIncompleteView] = useState<string | null>(null);
  const standaloneKey = `sw-standalone:v1:${JSON.stringify([sessionUserId() ?? null, groupId])}`;
  const noticeKey = 'kbw.relationNotice.' + groupId;
  const [noticeDismissed, setNoticeDismissed] = useState(() => {
    try { return localStorage.getItem(noticeKey) === 'dismissed'; } catch { return false; }
  });
  const changeNotice = (dismissed: boolean) => {
    try {
      if (dismissed) localStorage.setItem(noticeKey, 'dismissed');
      else localStorage.removeItem(noticeKey);
    } catch { /* Keep the preference for the current visit. */ }
    setNoticeDismissed(dismissed);
  };
  const [standaloneModels, setStandaloneModels] = useState(() => readStandaloneModels(standaloneKey));
  useEffect(() => {
    const sync = (event: StorageEvent) => {
      if (event.key === null || event.key === standaloneKey) setStandaloneModels(readStandaloneModels(standaloneKey));
    };
    window.addEventListener('storage', sync);
    return () => window.removeEventListener('storage', sync);
  }, [standaloneKey]);
  const saveStandalone = (next: Set<string>) => {
    try { localStorage.setItem(standaloneKey, JSON.stringify([...next])); } catch { /* falls back to this visit */ }
    setStandaloneModels(next);
  };
  const setStandalone = (model: string, marked: boolean) => {
    const next = new Set(standaloneModels);
    if (marked) next.add(model);
    else next.delete(model);
    saveStandalone(next);
  };
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
  // The object bar folds to icons; remembered per browser.
  const [railOpen, setRailOpen] = useState(() => {
    try {
      const preference = localStorage.getItem('sw-rail');
      return preference === null ? defaultRailOpen : preference === 'open';
    } catch { return defaultRailOpen; }
  });
  const toggleRail = () => setRailOpen(open => { try { localStorage.setItem('sw-rail', open ? 'closed' : 'open'); } catch { /* convenience only */ } return !open; });
  useEffect(() => {
    if (!full) return undefined;
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !event.defaultPrevented && !document.querySelector('.kw-menu, dialog[open]')) setFull(false);
    };
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

  const [looseColumns, setLooseColumns] = useState(3);
  const layout = useMemo(() => layoutOverview(snapshot, positions, looseColumns), [snapshot, positions, looseColumns]);
  const focused = useMemo(() => focus ? focusOf(snapshot, focus) : null, [snapshot, focus]);
  const independentModels = useMemo(() => unrelatedModels(snapshot), [snapshot]);
  const unrelated = useMemo(() => new Set(independentModels.map(model => model.name)), [independentModels]);
  const relationAdvice = editable ? independentModels.filter(model => !standaloneModels.has(model.name)) : independentModels;
  const associationLabel = (name: string) => !unrelated.has(name) ? '' : editable && standaloneModels.has(name) ? ' · 独立使用' : ' · 未关联';
  const rules = useMemo(() => [...files.entries()].filter(([p]) => isRuleFile(p)).flatMap(([p, c]) => parseRuleFile(p, c)), [files]);
  const publishedRules = useMemo(() => baselineFiles ? [...baselineFiles.entries()].filter(([path]) => isRuleFile(path)).flatMap(([path, content]) => parseRuleFile(path, content)) : [], [baselineFiles]);
  const onlineRules = useMemo(() => new Set(publishedRules.map(ruleKey)), [publishedRules]);
  const isDraftRule = (rule: RuleItem) => editable && !onlineRules.has(ruleKey(rule));
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
    return tone(unseen.rules.has(key), looked.rules.has(key) || looked.added.has(`q:${key}`), comparing && isDraftRule(r), unseen.added.has(`q:${key}`));
  };
  const markedRelation = (r: SemanticRelation): MarkTone => tone(unseen.relations.has(r.name), looked.relations.has(r.name) || looked.added.has(`r:${r.name}`), false, unseen.added.has(`r:${r.name}`));
  const markedModel = (m: SemanticModel): MarkTone => tone(unseen.models.has(m.name), looked.models.has(m.name) || looked.added.has(`m:${m.name}`), false, unseen.added.has(`m:${m.name}`));

  const problems = useMemo<Problem[]>(() => {
    const out: Problem[] = issues.map(issue => ({ kind: 'validation', issue }));
    for (const m of snapshot.models) {
      const missing = m.columns.filter(c => !c.description?.trim());
      if (missing.length) out.push({ kind: 'description', model: m, columns: missing });
    }
    for (const r of rules) {
      if (!sectionOf(r.heading)) out.push({ kind: 'classification', rule: r });
      const references = [...new Set(brokenRefs(r.text, snapshot.models))];
      if (references.length) out.push({ kind: 'reference', rule: r, references });
    }
    return out;
  }, [snapshot.models, rules, issues]);

  // ----- canvas: pan the scroll box, drag cards by their header, zoom with buttons or Ctrl+wheel
  const canvas = useRef<HTMLDivElement>(null);
  const autoFit = useRef(true);
  const pan = useRef<{ x: number; y: number; left: number; top: number } | null>(null);
  const grab = useRef<{ model: string; x: number; y: number; ox: number; oy: number; moved: boolean } | null>(null);
  const suppressClick = useRef(false);
  useEffect(() => {
    const box = canvas.current;
    if (!box) return undefined;
    const wheel = (event: WheelEvent) => {
      if (!event.ctrlKey && !event.metaKey) return;
      event.preventDefault();
      autoFit.current = false;
      setZoom(z => clampZoom(z - event.deltaY * 0.001));
    };
    box.addEventListener('wheel', wheel, { passive: false });
    return () => box.removeEventListener('wheel', wheel);
  }, [view]);
  const modelCount = snapshot.models.length;
  const fit = useCallback(() => {
    const box = canvas.current;
    if (!box?.clientWidth || !box.clientHeight || !layout.width) return;
    const floor = modelCount <= 3 ? MIN_ZOOM : FIT_FLOOR;
    const guideHeight = box.querySelector<HTMLElement>('.sw-relation-guide')?.offsetHeight ?? 0;
    setZoom(clampZoom(Math.max(floor, Math.min(1, (box.clientWidth - 24) / layout.width, (box.clientHeight - 24 - guideHeight) / layout.height))));
    box.scrollTo(0, 0);
  }, [layout.width, layout.height, modelCount]);
  useEffect(() => {
    const box = canvas.current;
    if (!box || view !== 'graph' || focus) return;
    const observer = new ResizeObserver(() => {
      if (snapshot.relations.length === 0 && box.clientWidth > 0) {
        const available = box.clientWidth - 24 - 2 * OVERVIEW_PAD + NODE_GAP_Y;
        setLooseColumns(Math.max(1, Math.min(3, Math.floor(available / (NODE_W + NODE_GAP_Y)))));
      }
      if (autoFit.current) fit();
    });
    observer.observe(box);
    return () => observer.disconnect();
  }, [view, focus, fit, snapshot.relations.length]);
  const zoomBy = (amount: number) => {
    autoFit.current = false;
    setZoom(z => clampZoom(z + amount));
  };
  const fitCanvas = () => { autoFit.current = true; fit(); };

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
    if (target.closest('button, .sw-relation-guide')) return;
    autoFit.current = false;
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
      autoFit.current = false;
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
  const resetLayout = () => { autoFit.current = true; setPositions({}); savePositions(groupId, {}); setTimeout(fit, 0); };
  const locate = (name: string) => {
    const card = layout.nodes.find(c => c.model.name === name);
    const box = canvas.current;
    if (!card || !box) return;
    const world = box.querySelector('.sw-world')?.getBoundingClientRect();
    const viewport = box.getBoundingClientRect();
    box.scrollTo({ left: Math.max(0, (world ? box.scrollLeft + world.left - viewport.left : 0) + card.x * zoom - 40),
      top: Math.max(0, (world ? box.scrollTop + world.top - viewport.top : 0) + card.y * zoom - 40), behavior: 'smooth' });
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
    const relation = snapshot.relations.find(r => r.name.toLowerCase().includes(keyword));
    if (relation) { setSelection({ kind: 'relation', name: relation.name }); return; }
    const matchedView = views.find(v => v.name.toLowerCase().includes(keyword));
    if (matchedView) { setSelection({ kind: 'view', name: matchedView.name }); return; }
    const rule = rules.find(r => r.text.toLowerCase().includes(keyword));
    if (rule && setSelection({ kind: 'rule', path: rule.path, text: rule.text })) onViewChange('rules');
  };

  const selectedModelName = selection?.kind === 'newRelation' ? selection.left : selection && 'model' in selection ? selection.model : undefined;
  const selectedModel = snapshot.models.find(m => m.name === selectedModelName) ?? null;
  const selectedRelation = selection?.kind === 'relation' ? snapshot.relations.find(r => r.name === selection.name) ?? null : null;
  const hot = (r: SemanticRelation) => selectedRelation ? r.name === selectedRelation.name : selectedModel ? r.left === selectedModel.name || r.right === selectedModel.name : false;
  const pick = (next: Selection) => { if (suppressClick.current) { suppressClick.current = false; return; } setSelection(next); };

  const save = async (edit: DraftItemEdit) => { await editDraftItem(groupId, edit); onChanged(); };
  const canEdit = editable && !blocked;
  const withoutRelations = snapshot.models.length > 1 && snapshot.relations.length === 0;
  const canShowNotice = withoutRelations && (editable || relationAdvice.length > 0);
  const adviceInRail = canShowNotice && noticeDismissed;
  const openAdvice = () => {
    if (!setSelection({ kind: 'problems', filter: 'advice' })) return;
    if (!showDetail) onExpandBoard?.();
  };
  const sharedFields = withoutRelations && relationAdvice.length > 1
    ? relationAdvice[0].columns.filter(column => !column.calculated
      && relationAdvice.slice(1).every(model => model.columns.some(candidate => !candidate.calculated && candidate.name === column.name)))
      .slice(0, 3).map(column => column.name)
    : [];

  if (!snapshot.models.length && view === 'graph' && !problems.length) return <div className="sw"><div className="sw-empty-page">暂无模型，请先上传数据或关联数据表。</div></div>;

  const openMark = (item: DraftItem) => {
    if (item.category === 'MODEL') {
      const model = snapshot.models.find(entry => entry.name === item.title);
      const field = item.fields.find(f => model?.columns.some(column => column.name === f.name));
      if (!setSelection(field ? { kind: 'field', model: item.title, column: field.name } : { kind: 'model', model: item.title })) return false;
      onViewChange('graph');
      if (focus && model) setFocus(model.name);
      else setTimeout(() => locate(item.title), 0);
    } else if (item.category === 'RELATIONSHIP' && item.text) {
      if (!setSelection({ kind: 'relation', name: item.text })) return false;
      onViewChange('graph');
    } else if (item.text) {
      if (!setSelection({ kind: 'rule', path: item.path, text: item.text })) return false;
      onViewChange('rules');
    }
    return true;
  };

  const ruleSelected = selection?.kind === 'rule' || selection?.kind === 'newRule';
  const activeRule = selection?.kind === 'rule' ? selectedRule(rules, selection) : undefined;
  const detailOpen = showDetail && selection && (selection.kind === 'problems' || ruleSelected === (view === 'rules'));
  const modelCheck = <button type="button" className="da-btn da-btn-sm sw-check-trigger" data-issues={problems.length > 0 || undefined}
    aria-label={problems.length ? '模型检查 ' + problems.length : '模型检查'} aria-expanded={!!detailOpen && selection?.kind === 'problems'}
    title="查看待核对项和使用建议"
    onClick={() => { if (setSelection({ kind: 'problems' }) && !showDetail) onExpandBoard?.(); }}>
    <Icon name={problems.length ? 'circleAlert' : 'check'} size="sm" />
    <span className="sw-check-label">模型检查</span>{problems.length > 0 && <b className="sw-issue-count">{problems.length}</b>}
  </button>;
  const markControls = editable && canvasDraft && onChanges && (canvasDraft.items.length > 0 || changes) &&
    <CanvasChanges items={changes?.since ? items : canvasDraft.items} review={canvasDraft.review} changes={changes}
      canEdit={canEdit} isSeen={isSeen} onChanges={onChanges} onUndo={onUndoDraft}
      modelLabel={name => {
        const model = snapshot.models.find(m => m.name === name);
        return model ? modelTitle(model) ?? name : name;
      }}
      canLocate={item => item.kind !== 'REMOVED' && (item.category === 'MODEL' ? snapshot.models.some(model => model.name === item.title)
        : !!item.text && (item.category === 'RELATIONSHIP' || isRuleFile(item.path)))}
      onLocate={item => {
        if (!openMark(item)) return false;
        onSeen?.(item);
        if (!showDetail) onExpandBoard?.();
        return true;
      }} />;
  const fullscreenLabel = full ? '退出全屏' : '全屏';
  const fullscreenToggle = <button type="button" className="da-btn da-btn-sm sw-full-toggle" aria-pressed={full}
    aria-label={fullscreenLabel} title={full ? '退出全屏（Esc）' : fullscreenLabel} onClick={() => setFull(!full)}>
    <Icon name={full ? 'minimize' : 'fit'} size="sm" />
  </button>;
  return <div className={`sw ${view === 'graph' ? 'sw-graph' : 'sw-rules'}${full ? ' sw-full' : ''}${detailOpen ? ' sw-inspecting' : ''}`}>
    <LocateEffect locate={reveal} onLocate={openMark} />
    {view === 'graph' && <aside className={`sw-rail ${railOpen ? '' : 'sw-rail-closed'}`} aria-label="对象目录">
      <div className="sw-rail-top">
        {railOpen && <span className="sw-rail-title">对象目录</span>}
        <button type="button" className="sw-rail-fold" onClick={toggleRail} aria-expanded={railOpen} aria-label={railOpen ? '收起对象目录' : '展开对象目录'} title={railOpen ? '收起对象目录' : '展开对象目录'}>{railOpen ? '‹' : <><Icon name="list" size="sm" /><span>对象目录</span></>}</button>
      </div>
      <div className="sw-find">
        <Icon name="search" size="sm" />
        <input className="da-input sw-search" type="search" aria-label="搜索模型、字段、关系、视图和规则，回车定位" placeholder="搜索对象或字段，回车定位"
          value={query} onChange={e => setQuery(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') search(); }} />
      </div>
      <RailGroup label="模型" count={snapshot.models.length} />
      {snapshot.models.map(m => <button key={m.name} type="button" className={`sw-node ${selectedModel?.name === m.name && view === 'graph' ? 'sw-node-on' : ''}`}
          onClick={() => { if (setSelection({ kind: 'model', model: m.name })) { onViewChange('graph'); if (focus) setFocus(m.name); else setTimeout(() => locate(m.name), 0); } }}
          onDoubleClick={() => { if (selection?.kind === 'model' && selection.model === m.name) setFocus(m.name); }}>
          <span className={`sw-ico ${m.tableRole === 'FACT' ? 'sw-ico-fact' : m.derived ? 'sw-ico-sql' : 'sw-ico-dim'}`}>{m.tableRole === 'FACT' ? '事' : m.derived ? '算' : '维'}</span>
          <span className="sw-node-name sw-model-name">
            <ModelName model={m} />
            <small>{m.columns.length} 列{associationLabel(m.name)}</small>
          </span>
          {changedModel(m) && <span className="sw-dot" title="草稿有改动" aria-label="草稿有改动" />}
        </button>)}
      <RailGroup label="关系" count={snapshot.relations.length} action={canEdit && snapshot.models.length > 1 ? () => { if (setSelection({ kind: 'newRelation', left: selectedModel?.name ?? snapshot.models[0].name })) onViewChange('graph'); } : undefined} actionLabel="新增关系" />
      {adviceInRail && <button type="button" className="sw-rail-advice" onClick={openAdvice}>
        <Icon name="graph" size="sm" />关联建议 <b>{relationAdvice.length || independentModels.length}</b>
      </button>}
      {!snapshot.relations.length && <p className={'sw-rail-empty sw-rail-relation' + (canShowNotice ? ' sw-rail-short' : '')}>
        当前模型可分别问数。{!canShowNotice && (canEdit && snapshot.models.length > 1 ? '需要跨表取数时，点上方加号配置关系。' : '跨表取数需要配置关系。')}
      </p>}
      {snapshot.relations.map(r => <button key={r.name} type="button" className={`sw-node ${selectedRelation?.name === r.name ? 'sw-node-on' : ''}`}
        onClick={() => { if (setSelection({ kind: 'relation', name: r.name })) onViewChange('graph'); }}>
        <span className="sw-ico sw-ico-rel">关</span><span className="sw-node-name" title={`${r.left} → ${r.right}`}>{r.left} → {r.right}</span><small>{joinEnds(r.joinType).join(':')}</small>
      </button>)}
      <RailGroup label="视图" count={views.length} action={canEdit && onAsk ? () => onAsk('我想建一个视图：') : undefined} actionLabel="让助手建一个视图" />
      {!views.length && <div className="sw-rail-empty">把常用的多表组合固定成一张表。点 + 在助手里描述，例如"订单带上客户地区和会员等级"。</div>}
      {views.map(v => <button key={v.name} type="button" className={`sw-node ${selection?.kind === 'view' && selection.name === v.name ? 'sw-node-on' : ''}`} onClick={() => setSelection({ kind: 'view', name: v.name })}>
        <span className="sw-ico sw-ico-view">视</span><span className="sw-node-name">{v.name}</span>
      </button>)}
    </aside>}

    <main className="sw-stage">
      {full ? <div className="sw-fullscreen-actions" aria-label="全屏建模工具">
        {fullscreenVersion}
        {modelCheck}
        {editable && onReviewChanges && <button type="button" className="da-btn da-btn-sm" onClick={onReviewChanges}>审阅草稿</button>}
      </div> : checkTarget ? createPortal(modelCheck, checkTarget) : null}
      {comparing && <div className="sw-banner">对比线上版本：黄色为草稿中新增或修改的内容，发布后才对问数生效</div>}
      {blocked && editable && <div className="sw-banner sw-banner-warn">{blocked}</div>}

      {view === 'graph' && focused ? <FocusView focus={focused} snapshot={snapshot} rules={rules} selection={selection} canEdit={canEdit}
        changedColumn={changedColumn} markedRelation={markedRelation} markedModel={markedModel}
        onBack={() => { setFocus(null); }} onFocus={name => { if (setSelection({ kind: 'model', model: name })) setFocus(name); }}
        onSelect={setSelection} />
      : view === 'graph' ? <div className="sw-canvas-wrap">
        <div ref={canvas} className="sw-canvas" onPointerDown={pointerDown} onPointerMove={pointerMove} onPointerUp={pointerUp} onPointerCancel={pointerUp} onPointerLeave={pointerUp}>
        <div className="sw-canvas-content">
        {canShowNotice && !noticeDismissed && relationAdvice.length > 0 && <section className="sw-relation-guide" style={{ marginBottom: 24 - OVERVIEW_PAD * zoom }}>
          <div><h3><Icon name="graph" size="sm" />还没有建立关系</h3>
            <p>{snapshot.models.length} 个模型可独立问数。需要跨表补充字段时，再核对关联键和基数。</p>
            {sharedFields.length > 0 && <p>共同字段 {sharedFields.map(field => <code key={field}>{field}</code>)}<span>需核对含义、粒度和唯一性</span></p>}
          </div>
          {editable && <div className="sw-relation-guide-actions">
            {onAsk && <button type="button" className="da-btn da-btn-primary da-btn-sm" disabled={!canEdit}
              onClick={() => onAsk(`请自动识别本知识库的候选关系。待评估模型：${relationAdvice.map(model => model.name).join('、')}。${sharedFields.length ? '共同字段：' + sharedFields.join('、') + '。' : ''}核对字段含义、表粒度、已声明主键和业务规则，给出候选关系的两侧字段、建议基数、依据及待验证项。没有足够依据时说明哪些模型应独立使用。只给建议，等我确认后再改草稿。`)}>自动识别候选关系</button>}
            <button type="button" className="da-btn da-btn-sm" disabled={!canEdit}
              onClick={() => setSelection({ kind: 'newRelation', left: relationAdvice.find(model => model.name === selectedModel?.name)?.name ?? relationAdvice[0].name, fromCheck: true })}>手动新建</button>
          </div>}
          <button type="button" className="sw-notice-close" aria-label="收起关联提示" onClick={() => changeNotice(true)}><Icon name="close" size="sm" /></button>
        </section>}
        {canShowNotice && !noticeDismissed && relationAdvice.length === 0 && <div className="sw-standalone-summary">
          <Icon name="check" size="sm" /><span>{independentModels.length} 个模型已标记为独立使用</span>
          <button type="button" className="sw-link" onClick={() => {
            if (setSelection({ kind: 'problems', filter: 'advice' })) {
              saveStandalone(new Set([...standaloneModels].filter(name => !unrelated.has(name))));
              if (!showDetail) onExpandBoard?.();
            }
          }}>
            重新显示关联建议
          </button>
          <button type="button" className="sw-notice-close" aria-label="收起关联提示" onClick={() => changeNotice(true)}><Icon name="close" size="sm" /></button>
        </div>}
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
              const previewFields = [...new Map([
                ...m.columns.filter(column => column.primaryKey),
                ...m.columns.filter(column => sharedFields.includes(column.name)),
                ...m.columns,
              ].map(column => [column.name, column])).values()].slice(0, 3);
              const standalone = editable && unrelated.has(m.name) && standaloneModels.has(m.name);
              const unlinked = unrelated.has(m.name) && !standalone;
              return <div key={m.name} data-card-head={m.name} role="button" tabIndex={0} title={m.name} aria-label={m.name} aria-pressed={on}
                className={`sw-onode sw-onode-${tone} ${on ? 'sw-onode-on' : markedModel(m) ? `sw-onode-${markedModel(m)}` : ''}`}
                data-unlinked={unlinked || undefined} data-standalone={standalone || undefined} style={{ left: node.x, top: node.y, width: NODE_W, height: NODE_H }}
                onClick={() => pick({ kind: 'model', model: m.name })} onDoubleClick={() => { if (selection?.kind === 'model' && selection.model === m.name) setFocus(m.name); }}
                onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setSelection({ kind: 'model', model: m.name }); } }}>
                <span className="sw-onode-header"><span className={`sw-ico sw-ico-${tone}`}>{tone === 'fact' ? '事' : tone === 'sql' ? '算' : '维'}</span>
                  <span className="sw-onode-text"><strong title={m.name}>{modelTitle(m) || m.name}</strong></span></span>
                <span className="sw-onode-fields">{previewFields.map(column => <span key={column.name}><code title={column.name}>{column.name}</code><small>{column.type}</small></span>)}</span>
                <span className="sw-onode-foot" data-unlinked={unlinked || undefined}>{standalone ? <span>独立使用</span> : unlinked && <span>未关联</span>}{m.columns.length - calc} 列{calc ? ` · ${calc} 衍生` : ''}</span>
                {markedModel(m) === 'red' && <span className="sw-badge-red" title="草稿改动未看">改</span>}
                {markedModel(m) === 'added' && <span className="sw-badge-new">新</span>}
              </div>;
            })}
          </div>
        </div>
        </div>
        </div>
        <details className="sw-canvas-help">
          <summary aria-label="画布操作说明" title="画布操作说明">?</summary>
          <div className="sw-canvas-help-copy">
            <span>单击看详情，双击聚焦关系</span>
            <span>拖动节点移动，拖动画布平移</span>
            <span>Ctrl + 滚轮缩放，N — 1 表示多对一</span>
          </div>
        </details>
      </div> : <RulesWorkspace groupId={groupId} rules={rules} models={snapshot.models} selected={activeRule} ready={rulesReady} hasInspector={!!detailOpen}
        isDraft={isDraftRule} isMarked={newRule} editable={editable} canEdit={canEdit} viewActions={<>{markControls}{fullscreenToggle}</>}
        onSelectRule={rule => {
          if (setSelection({ kind: 'rule', path: rule.path, text: rule.text, line: rule.line }) && !showDetail) onExpandBoard?.();
        }}
        onCreate={heading => { if (setSelection({ kind: 'newRule', heading }) && !showDetail) onExpandBoard?.(); }}
        onMove={async (candidates, heading) => {
          if (!canEdit) return false;
          if (candidates.some(rule => hasDuplicateRule(rules, rule))) throw new Error('同一文件包含原文相同的规则，请在「工程与快照」按行核对后再移动。');
          if (!setSelection(null)) return false;
          let completed = 0;
          try {
            for (const rule of candidates) {
              await editDraftItem(groupId, { category: 'SEMANTIC', path: rule.path, original: rule.text, heading });
              completed++;
            }
            return true;
          } catch (error) {
            throw new Error(`已移动 ${completed} / ${candidates.length} 条。其余规则可重试。 ${message(error)}`);
          } finally {
            if (completed) onChanged();
          }
        }} onAsk={onAsk} onDocuments={onDocuments} />}
      {view === 'graph' && <div className="sw-canvas-actions" role="group" aria-label="关系图操作">
        {markControls}
        <div className="sw-float">
          {!focused && <>
            <button type="button" className="sw-icon-btn" aria-label="缩小" onClick={() => zoomBy(-0.1)}>−</button>
            <span className="sw-zoom">{Math.round(zoom * 100)}%</span>
            <button type="button" className="sw-icon-btn" aria-label="放大" onClick={() => zoomBy(0.1)}>+</button>
            <button type="button" className="sw-icon-btn" onClick={fitCanvas}>适应</button>
            <button type="button" className="sw-icon-btn" onClick={resetLayout}>自动布局</button>
          </>}
          {fullscreenToggle}
        </div>
      </div>}
    </main>

    {detailOpen && <aside className={`sw-drawer${ruleSelected ? ' sw-rule-inspector' : ''}`} aria-label="详情">
      <Drawer key={selectionKey} {...{ groupId, snapshot, selection, files, baselineFiles, views, rules, problems, relationAdvice, standaloneModels, editable, canEdit, blocked, onAsk, changedColumn, newRule, editGuard, onEngineering, onDocuments, onReviewAsset }}
        publishedRules={publishedRules} isDraftRule={isDraftRule}
        onRestoreNotice={adviceInRail ? () => changeNotice(false) : undefined}
        independentCount={independentModels.length - relationAdvice.length}
        onSetStandalone={editable ? setStandalone : undefined}
        onInspectIncompleteView={editable ? setIncompleteView : undefined}
        onSelect={next => {
          if (!setSelection(next) || !next || next.kind === 'problems') return;
          const destination = next.kind === 'rule' || next.kind === 'newRule' ? 'rules' : 'graph';
          if (destination !== view) onViewChange(destination);
          const targetModel = next.kind === 'newRelation' ? next.left : 'model' in next ? next.model : undefined;
          if (targetModel && 'fromCheck' in next && next.fromCheck) {
            if (focus) setFocus(targetModel);
            else setTimeout(() => locate(targetModel), 0);
          }
        }} onSave={save} onChanged={onChanged} />
    </aside>}
    {editable && incompleteView && <IncompleteViewDialog groupId={groupId} name={incompleteView} disabled={!canEdit}
      onClose={() => setIncompleteView(null)} onRemoved={() => { setIncompleteView(null); onChanged(); }} />}
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

function ModelIdentifier({ name }: { name: string }) {
  const parts = name.split('_');
  return parts.map((part, index) => <Fragment key={index}>{part}{index < parts.length - 1 && <>_<wbr /></>}</Fragment>);
}

function ModelName({ model }: { model: SemanticModel }) {
  const alias = modelTitle(model);
  return <span className="sw-model-label" title={model.name}>
    {alias && <strong className="sw-model-alias">{alias}</strong>}
    <span className="sw-model-identifier"><ModelIdentifier name={model.name} /></span>
  </span>;
}

function RailGroup({ label, count, action, actionLabel = '新增' }: { label: string; count: number; action?: () => void; actionLabel?: string }) {
  return <div className="sw-rail-group">
    <span>{label} <small>{count}</small></span>
    {action && <button type="button" aria-label={actionLabel} title={actionLabel} onClick={action}>+</button>}
  </div>;
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
      <span className="sw-onode-text"><ModelName model={link.model} /><small>{modelTag(link.model, false).label} · {link.model.columns.length} 个字段</small></span>
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
            <span className="sw-onode-text"><ModelName model={center} /><small>{modelTag(center, false).label}{center.description ? ` · ${center.description}` : ''}</small></span>
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

function selectedRule(rules: RuleItem[], selection: Extract<Selection, { kind: 'rule' }>) {
  const matches = rules.filter(rule => rule.path === selection.path && rule.text === selection.text);
  return matches.find(rule => rule.line === selection.line) ?? matches[0];
}

function hasDuplicateRule(rules: RuleItem[], rule: RuleItem) {
  return rules.filter(item => item.path === rule.path && item.text === rule.text).length > 1;
}

// ------------------------------------------------------------------ drawer

function Drawer({ groupId, snapshot, selection, files, baselineFiles, views, rules, publishedRules, isDraftRule, problems, relationAdvice, standaloneModels, editable, canEdit, blocked, onAsk, changedColumn, newRule, onSelect, onSave, onChanged, editGuard, onEngineering, onDocuments, onReviewAsset, onInspectIncompleteView, onSetStandalone, onRestoreNotice, independentCount }: {
  groupId: string;
  snapshot: SemanticSnapshot;
  selection: Selection;
  files: Map<string, string>;
  baselineFiles?: Map<string, string> | null;
  onEngineering?: (path?: string) => void;
  onDocuments?: () => void;
  onReviewAsset?: (target: NonNullable<MdlIssue['assetReview']>) => void;
  onInspectIncompleteView?: (name: string) => void;
  views: { name: string; sql: string; description: string | null }[];
  publishedRules: RuleItem[];
  isDraftRule: (rule: RuleItem) => boolean;
  rules: RuleItem[];
  problems: Problem[];
  relationAdvice: SemanticModel[];
  standaloneModels: ReadonlySet<string>;
  independentCount: number;
  onRestoreNotice?: () => void;
  onSetStandalone?: (model: string, marked: boolean) => void;
  editable: boolean;
  canEdit: boolean;
  blocked: string | null;
  onAsk?: (text: string) => void;
  changedColumn: (model: string, column: SemanticColumn) => MarkTone;
  newRule: (rule: RuleItem) => MarkTone;
  onSelect: (s: Selection) => void;
  onSave: (edit: DraftItemEdit) => Promise<void>;
  onChanged: () => void;
  editGuard: FieldEditGuard;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');
  const [calcOpen, setCalcOpen] = useState(() => canEdit && selection?.kind === 'field' && !!selection.editDescription
    && !!snapshot.models.find(model => model.name === selection.model)?.columns.find(column => column.name === selection.column)?.calculated);
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

  if (selection.kind === 'problems') return <CheckDrawer initialFilter={selection.filter}
    {...{ problems, relationAdvice, editable, canEdit, onSelect, onAsk, onReviewAsset, onEngineering, onInspectIncompleteView, blocked }}
    independentCount={independentCount} onRestoreNotice={onRestoreNotice}
    onMarkStandalone={onSetStandalone && (model => onSetStandalone(model, true))} />;

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
    const markedStandalone = standaloneModels.has(model.name);
    const openCalc = () => { clearFeedback(); setCalcOpen(true); };
    const saveField = (edit: DraftItemEdit, done: string, after?: () => void) => run(() => onSave(edit), done, after);
    return <>
      {column ? <FieldDrawer {...{ model, column, snapshot, rules, editable, canEdit, disabledTitle, onSelect, onAsk, busy, editGuard }}
        fromCheck={selection.kind === 'field' && selection.fromCheck} editDescription={selection.kind === 'field' && selection.editDescription}
        onEditCalculated={openCalc} onClearFeedback={clearFeedback} onSave={saveField} feedback={calcOpen ? null : feedback} />
        : <ModelDrawer {...{ groupId, model, snapshot, files, baselineFiles, rules, editable, canEdit, blocked, onSelect, onChanged, onAsk, changedColumn, newRule, onSave, editGuard, onEngineering, onDocuments }}
          standalonePreference={onSetStandalone && (markedStandalone || relationAdvice.some(candidate => candidate.name === model.name))
            ? { marked: markedStandalone, toggle: () => onSetStandalone(model.name, !markedStandalone) } : undefined}
          onAddCalculated={openCalc} feedback={calcOpen ? null : feedback} />}
      {calcOpen && editable && <Suspense fallback={null}><CalculatedColumnDialog model={model} column={column} relations={snapshot.relations}
        busy={busy} blocked={blocked} feedback={feedback} onClose={() => { setError(''); setCalcOpen(false); }}
        onSave={edit => void saveField(edit, column ? '衍生列已保存到草稿。' : '衍生列已加入草稿。', () => setCalcOpen(false))} /></Suspense>}
    </>;
  }

  if (selection.kind === 'relation' || selection.kind === 'newRelation') {
    const relation = selection.kind === 'relation' ? snapshot.relations.find(r => r.name === selection.name) : undefined;
    if (selection.kind === 'relation' && !relation) return <Gone onSelect={onSelect} />;
    return <RelationDrawer {...{ snapshot, relation, editable, canEdit, disabledTitle, onSelect }} left={selection.kind === 'newRelation' ? selection.left : undefined}
      fromCheck={selection.kind === 'newRelation' && selection.fromCheck}
      onSave={(edit, done, after) => run(() => onSave(edit), done, after)} busy={busy} feedback={feedback} ask={ask} />;
  }

  if (selection.kind === 'rule' || selection.kind === 'newRule') {
    const rule = selection.kind === 'rule' ? selectedRule(rules, selection) : undefined;
    if (selection.kind === 'rule' && !rule) return <Gone onSelect={onSelect} />;
    const section = sectionOf(rule ? rule.heading : selection.kind === 'newRule' ? selection.heading : null);
    const duplicate = !!rule && hasDuplicateRule(rules, rule);
    return <RuleInspector {...{ groupId, rule, section, snapshot, editable }}
      canEdit={canEdit && !duplicate} disabledTitle={duplicate ? '同一文件有原文相同的规则，请在工程中按行编辑。' : disabledTitle}
      rules={rules} before={rule && editable ? publishedRuleVersion(rule, rules, publishedRules) : undefined}
      draft={!!rule && isDraftRule(rule)} editGuard={editGuard} onAsk={onAsk}
      checkAction={selection.kind === 'rule' ? selection.checkAction : undefined}
      onDismiss={() => onSelect(null)}
      onBackToChecks={() => onSelect({ kind: 'problems', filter: 'attention' })}
      onSelectRule={peer => onSelect({ kind: 'rule', path: peer.path, text: peer.text, line: peer.line })}
      onEngineering={onEngineering}
      onOpenField={(model, column) => onSelect({ kind: 'field', model, column })}
      onSaved={(path, text) => onSelect(selection.kind === 'rule' && selection.checkAction
        ? { kind: 'problems', filter: 'attention' } : { kind: 'rule', path, text })}
      onMoved={() => onSelect(selection.kind === 'rule' && selection.checkAction === 'classify' ? { kind: 'problems', filter: 'attention' } : null)}
      onSave={(edit, done, after) => run(() => onSave(edit), done, after)} busy={busy} feedback={feedback} />;
  }
  return null;
}

function CheckDrawer({ problems, relationAdvice, editable, canEdit, blocked, onSelect, onAsk, onEngineering, onReviewAsset, onInspectIncompleteView, onMarkStandalone, initialFilter, independentCount, onRestoreNotice }: {
  problems: Problem[]; relationAdvice: SemanticModel[]; editable: boolean; canEdit: boolean; blocked: string | null;
  initialFilter?: CheckFilter;
  independentCount: number;
  onRestoreNotice?: () => void;
  onSelect: (s: Selection) => void; onAsk?: (text: string) => void; onEngineering?: (path?: string) => void;
  onReviewAsset?: (target: NonNullable<MdlIssue['assetReview']>) => void;
  onInspectIncompleteView?: (name: string) => void;
  onMarkStandalone?: (name: string) => void;
}) {
  const [filter, setFilter] = useState(initialFilter);
  const adviceTab = useRef<HTMLButtonElement>(null);
  const active = filter ?? (!problems.length && relationAdvice.length ? 'advice' : 'attention');
  const fieldAction = (model: SemanticModel, column: SemanticColumn) =>
    <button key={column.name} type="button" className="sw-link" title={column.name}
      onClick={() => onSelect({ kind: 'field', model: model.name, column: column.name, fromCheck: true, editDescription: canEdit })}>
      {canEdit ? '补充 ' : '查看 '}{columnLabel(column)}{canEdit ? ' 的说明' : ''}
    </button>;
  return <>
    <div className="sw-dh sw-check-head">
      <div className="sw-dh-row">
        <span className="sw-check-icon" data-issues={problems.length > 0 || undefined}><Icon name={problems.length ? 'circleAlert' : 'check'} size="sm" /></span>
        <strong>模型检查</strong><span className="sw-grow" /><CloseButton onSelect={onSelect} />
      </div>
      <div className="sw-seg sw-check-filters" role="group" aria-label="检查分类">
        <button type="button" className={active === 'attention' ? 'on' : ''} aria-pressed={active === 'attention'} onClick={() => setFilter('attention')}>待核对 <b>{problems.length}</b></button>
        <button ref={adviceTab} type="button" className={active === 'advice' ? 'on' : ''} aria-pressed={active === 'advice'} onClick={() => setFilter('advice')}>使用建议 <b>{relationAdvice.length}</b></button>
      </div>
    </div>
    <div className="sw-db">
      {active === 'attention' ? (problems.length === 0 ? <div className="sw-empty">没有待核对的检查项。</div>
        : CHECK_GROUPS.map(group => {
          const matches = problems.filter(problem => group.kinds.includes(problem.kind));
          if (!matches.length) return null;
          return <section key={group.label} className="sw-check-group">
            <h4>{group.label} <span className="sw-muted">{matches.length}</span></h4>
            <ul>{matches.map((problem, index) => {
              if (problem.kind === 'description') {
                const { model, columns } = problem;
                return <li key={model.name} className="sw-prob">
                  <div className="sw-prob-copy"><ModelName model={model} />
                    <span>{columns.length} 个字段缺说明。建议补充业务含义、单位或适用口径。</span>
                  </div>
                  {columns.length === 1 ? <div className="sw-check-actions">{fieldAction(model, columns[0])}</div>
                    : <details className="sw-check-fields"><summary>展开{canEdit ? '待补' : '相关'}字段</summary>
                      <div className="sw-check-actions">{columns.map(column => fieldAction(model, column))}</div>
                    </details>}
                </li>;
              }
              if (problem.kind === 'validation') {
                const { issue } = problem;
                const target = issue.assetReview;
                const incomplete = issue.incompleteView;
                return <li key={`${issue.source}:${issue.message}:${index}`} className="sw-prob">
                  <div className="sw-prob-copy"><strong>{issue.severity === 'error' ? '校验错误' : '校验提示'}</strong><span>{issue.message}</span></div>
                  <div className="sw-check-actions">
                    {incomplete && onInspectIncompleteView ? <button type="button" className="sw-link" onClick={() => onInspectIncompleteView(incomplete)}>检查未完成视图</button>
                      : onReviewAsset && target ? <button type="button" className="sw-link" onClick={() => onReviewAsset(target)}>{target.step === 'VERIFY_PENDING' ? '去验证' : '去核查'}</button>
                      : onEngineering && <button type="button" className="sw-link" onClick={() => onEngineering()}>打开工程</button>}
                    {editable && onAsk && <button type="button" className="sw-link" disabled={!canEdit} title={blocked ?? undefined}
                      onClick={() => onAsk(`请根据当前草稿排查这条校验结果，先给原因、影响与处理步骤，等我确认后再修改。\n${issue.message}`)}>让助手排查</button>}
                  </div>
                </li>;
              }
              const { rule } = problem;
              const isClassification = problem.kind === 'classification';
              return <li key={`${problem.kind}:${rule.path}:${rule.text}`} className="sw-prob">
                <div className="sw-prob-copy">
                  <strong>{isClassification ? '规则未归类' : '引用字段不存在'}</strong>
                  <span className="sw-check-rule-preview" title={ruleSummary(rule)}>{ruleSummary(rule)}</span>
                  <span>{isClassification ? '问数助手按官方分类查找规则。请选择适用的分类。'
                    : `${problem.references.join('、')} 在当前模型中不存在。请在规则中改用实际字段。`}</span>
                </div>
                <div className="sw-check-actions"><button type="button" className="sw-link" onClick={() =>
                  onSelect({ kind: 'rule', path: rule.path, text: rule.text, checkAction: isClassification ? 'classify' : 'references' })}>
                  {canEdit ? isClassification ? '归类规则' : '修正规则引用' : '查看规则'}
                </button></div>
              </li>;
            })}</ul>
          </section>;
        })) : relationAdvice.length === 0 ? <div className="sw-empty">{independentCount ? independentCount + ' 个模型已标记为独立使用，可分别问数。' : '暂无使用建议。'}</div>
        : <section className="sw-check-group sw-check-advice">
          <h4>未关联模型 <span className="sw-muted">{relationAdvice.length}</span></h4>
          <div className="sw-check-guidance">
            <p><strong>单表能回答</strong>，可独立使用。</p>
            <p><strong>不确定该关联哪张表</strong>，可让助手评估当前知识库里的候选模型。</p>
            <p><strong>需要汇总多个同类来源</strong>，先核对粒度与口径，再评估用公共视图合并。</p>
          </div>
          <ul>{relationAdvice.map(model => <li key={model.name} className="sw-prob">
            <div className="sw-prob-copy"><ModelName model={model} /><span className="sw-muted">可独立问数</span></div>
            {editable && <div className="sw-check-actions">
              {onMarkStandalone && <button type="button" className="sw-link"
                title="从使用建议移出，可在模型的表属性中恢复"
                aria-label={`标记 ${modelTitle(model) ?? model.name} 为独立使用`}
                onClick={() => { setFilter('advice'); onMarkStandalone(model.name); adviceTab.current?.focus(); }}>标记为独立使用</button>}
              <button type="button" className="sw-link" disabled={!canEdit} title={blocked ?? undefined}
                onClick={() => onSelect({ kind: 'newRelation', left: model.name, fromCheck: true })}>配置关联</button>
              {onAsk && <button type="button" className="sw-link" disabled={!canEdit} title={blocked ?? undefined}
                onClick={() => onAsk([
                  `请评估当前未关联模型「${model.name}」适合与本知识库中的哪些模型关联。`,
                  '先查看候选模型的字段含义、表粒度、已声明的主键和现有关系，以及相关业务规则。逐项给出候选模型、关联的业务用途、两侧字段、建议的基数和依据。多对一要核对目标侧关联键的唯一性，源侧可以有重复。区分模型声明与实际数据中待核实的情况。',
                  '不要仅凭字段同名推荐关联。若涉及汇总表，说明直接关联会不会让指标重复计数，也不要据此推断人数可以跨期相加。没有足够依据时，明确建议保持独立或说明需补充哪项证据。',
                  '本轮只给评估建议，请不要修改草稿、建立关系或创建公共视图。',
                ].join('\n'))}>让助手评估关联</button>}
            </div>}
          </li>)}</ul>
        </section>}
      {active === 'advice' && onRestoreNotice && <div className="sw-advice-footer">
        <button type="button" className="da-btn da-btn-sm" onClick={onRestoreNotice}>显示在画布</button>
      </div>}
    </div>
  </>;
}

function BackToChecks({ onSelect, filter = 'attention' }: { onSelect: (s: Selection) => void; filter?: CheckFilter }) {
  return <button type="button" className="sw-back" onClick={() => onSelect({ kind: 'problems', filter })}>← 模型检查</button>;
}

function CloseButton({ onSelect }: { onSelect: (s: Selection) => void }) {
  return <button type="button" className="sw-link" onClick={() => onSelect(null)}>关闭</button>;
}

function Gone({ onSelect }: { onSelect: (s: Selection) => void }) {
  return <div className="sw-db"><div className="sw-empty">这个对象已经不在当前版本中。</div><button type="button" className="sw-link" onClick={() => onSelect(null)}>返回</button></div>;
}

type FieldGroupKey = 'plain' | 'time' | 'calculated' | 'related';

function ModelDrawer({ groupId, model, snapshot, files, baselineFiles, rules, editable, canEdit, blocked, onSelect, onChanged, onAsk, changedColumn, newRule, onAddCalculated, feedback, onSave, editGuard, onEngineering, onDocuments, standalonePreference }: {
  groupId: string; model: SemanticModel; snapshot: SemanticSnapshot; files: Map<string, string>; rules: RuleItem[];
  baselineFiles?: Map<string, string> | null; onEngineering?: (path: string) => void; onDocuments?: () => void;
  standalonePreference?: { marked: boolean; toggle: () => void };
  editable: boolean; canEdit: boolean; blocked: string | null; onSelect: (s: Selection) => void; onChanged: () => void; onAsk?: (text: string) => void;
  changedColumn: (model: string, column: SemanticColumn) => MarkTone; newRule: (rule: RuleItem) => MarkTone;
  onAddCalculated: () => void; feedback: ReactNode;
  onSave: (edit: DraftItemEdit) => Promise<void>;
  editGuard: FieldEditGuard;
}) {
  const [tab, setTab] = useState<'fields' | 'rules' | 'info'>('fields');
  const tabId = useId();
  const [fieldQuery, setFieldQuery] = useState('');
  const [yaml, setYaml] = useState<'view' | 'edit' | null>(null);
  const [copyStatus, setCopyStatus] = useState('');
  const [description, setDescription] = useState<string | null>(null);
  const [descriptionBusy, setDescriptionBusy] = useState(false);
  const [descriptionError, setDescriptionError] = useState('');
  const fieldGroupKey = `sw-field-groups:v1:${JSON.stringify([groupId, model.name])}`;
  const [collapsed, setCollapsed] = useState<FieldGroupKey[]>(() => {
    try {
      const stored: unknown = JSON.parse(sessionStorage.getItem(fieldGroupKey) ?? '[]');
      return Array.isArray(stored) ? stored : [];
    } catch { return []; }
  });
  useEffect(() => {
    try { sessionStorage.setItem(fieldGroupKey, JSON.stringify(collapsed)); } catch { /* Optional browser preference. */ }
  }, [fieldGroupKey, collapsed]);
  const groupProps = (key: FieldGroupKey) => ({
    open: !!fieldQuery.trim() || !collapsed.includes(key),
    onToggle: (open: boolean) => {
      if (fieldQuery.trim()) return;
      setCollapsed(previous => {
        if (open === !previous.includes(key)) return previous;
        return open ? previous.filter(group => group !== key) : [...previous, key];
      });
    },
  });
  const modelPath = model.path;
  const source = modelPath ? files.get(modelPath) : undefined;
  const alias = modelTitle(model);
  const keys = model.columns.filter(column => column.primaryKey);
  const openYaml = (mode: 'view' | 'edit') => {
    if (editGuard.current?.() !== false) setYaml(mode);
  };
  const plainAll = model.columns.filter(c => !c.calculated);
  const times = plainAll.filter(c => roleOf(model, c, snapshot.relations).role === 'TIME');
  const plain = plainAll.filter(c => !times.includes(c));
  const calc = model.columns.filter(c => c.calculated);
  const relations = relationsOf(snapshot, model.name);
  const keyword = fieldQuery.trim().toLocaleLowerCase();
  const matches = (value?: string | null) => value?.toLocaleLowerCase().includes(keyword) ?? false;
  const filterFields = (columns: SemanticColumn[]) => columns.filter(c => matches(c.name) || matches(c.displayName));
  const visiblePlain = filterFields(plain);
  const visibleTimes = filterFields(times);
  const visibleCalc = filterFields(calc);
  const visibleRelations = relations.filter(r => matches(r.name) || matches(r.left === model.name ? r.right : r.left)
    || (r.left === model.name ? r.sourceColumns : r.targetColumns).some(matches));
  const saveDescription = async () => {
    if (!model.path || description === null || !canEdit) return;
    setDescriptionBusy(true); setDescriptionError('');
    try {
      await onSave({ category: 'MODEL', model: model.name, tableDescription: description.trim() });
      setDescription(null);
    } catch (e) { setDescriptionError(message(e)); } finally { setDescriptionBusy(false); }
  };
  // Columns business rules rely on: renaming or dropping them changes what the rules mean.
  const { ruleUse, relatedFields, related } = useMemo(() => {
    const ruleUse = new Map<string, RuleItem[]>();
    const relatedFields = new Map<RuleItem, SemanticColumn[]>();
    for (const rule of rules) {
      const refs = new Set(ruleRefs(rule.text, snapshot.models));
      const columns = model.columns.filter(column => refs.has(`${model.name}.${column.name}`));
      if (columns.length) relatedFields.set(rule, columns);
      for (const column of columns) {
        const uses = ruleUse.get(column.name);
        if (uses) uses.push(rule);
        else ruleUse.set(column.name, [rule]);
      }
    }
    const related = rules.filter(rule => relatedFields.has(rule) || mentionsRuleIdentifier(rule.text, model.name));
    return { ruleUse, relatedFields, related };
  }, [model, rules, snapshot.models]);
  const tag = modelTag(model, false);
  const tabs = [['fields', '字段 ' + model.columns.length], ['info', '表属性'], ['rules', '关联规则 ' + related.length]] as const;
  const fieldItem = (column: SemanticColumn) => {
    const used = ruleUse.get(column.name) ?? [];
    return <button key={column.name} type="button" className="sw-li sw-field-item"
      onClick={() => onSelect({ kind: 'field', model: model.name, column: column.name })}>
      <span className="sw-li-main">
        <span><b>{columnLabel(column)}</b> {column.displayName && column.displayName !== column.name && <span className="sw-mono sw-muted">{column.name}</span>}</span>
        <span className="sw-li-desc">{column.calculated ? '= ' + (column.expression ?? '') : column.description || '还没有说明'}</span>
      </span>
      {column.primaryKey && <span className="sw-k sw-k-pk">主键</span>}
      {column.userDescribed && <span className="sw-field-origin">人工说明</span>}
      <ToneTag tone={changedColumn(model.name, column)} />
      {used.length > 0 && <span className="sw-rule-use" title={used.map(rule => ruleSummary(rule)).join('\n')}>规则 {used.length}</span>}
    </button>;
  };
  return <>
    <div className="sw-dh sw-model-head">
      <div className="sw-model-heading">
        <span className={`sw-ico ${model.tableRole === 'FACT' ? 'sw-ico-fact' : model.derived ? 'sw-ico-sql' : 'sw-ico-dim'}`}>{model.tableRole === 'FACT' ? '事' : model.derived ? '算' : '维'}</span>
        <div className="sw-model-identity">
          <h3 className={alias ? undefined : 'sw-mono'}>{alias || <ModelIdentifier name={model.name} />}</h3>
          {alias && <code className="sw-mono" title={model.name}><ModelIdentifier name={model.name} /></code>}
          <span className="sw-model-meta">{tag.label} · {model.columns.length} 个字段{relations.length > 0 ? ` · ${relations.length} 条关系` : ''}</span>
          {copyStatus && <span className="sw-model-meta" role="status">{copyStatus}</span>}
        </div>
        <div className="sw-model-head-actions">
          {source !== undefined && <button type="button" className="da-btn da-btn-sm sw-yaml-trigger" title="查看模型 YAML" onClick={() => openYaml('view')}>YAML</button>}
          <WorkspaceMenu label="模型更多" items={[
            ...(source !== undefined ? [{ label: '查看 YAML', icon: 'code' as const, onClick: () => openYaml('view') }] : []),
            ...(canEdit && source !== undefined ? [{ label: '编辑 YAML', icon: 'edit' as const, onClick: () => openYaml('edit') }] : []),
            { label: '复制技术名', icon: 'copy', onClick: async () => {
              try { await navigator.clipboard.writeText(model.name); setCopyStatus('已复制技术名'); }
              catch { setCopyStatus('复制失败，可选中技术名复制'); }
            } },
            ...(source !== undefined && modelPath && onEngineering ? [{ label: '在工程中打开', icon: 'folder' as const, onClick: () => onEngineering(modelPath) }] : []),
            { label: '关闭面板', icon: 'close', onClick: () => onSelect(null) },
          ]} />
          <button type="button" className="sw-link sw-model-close" aria-label="关闭" title="关闭面板" onClick={() => onSelect(null)}><Icon name="close" size="sm" /></button>
        </div>
      </div>
      <div role="tablist" className="sw-model-tabs" aria-label="模型详情">
        {tabs.map(([key, label], index) => <button key={key} id={`${tabId}-${key}`} type="button" role="tab"
          aria-selected={tab === key} aria-controls={`${tabId}-panel`} tabIndex={tab === key ? 0 : -1}
          onClick={() => { if (key !== tab && editGuard.current?.() !== false) setTab(key); }}
          onKeyDown={event => {
            const direction = event.key === 'ArrowLeft' ? -1 : event.key === 'ArrowRight' ? 1 : 0;
            if (!direction) return;
            event.preventDefault();
            if (editGuard.current?.() === false) return;
            const next = (index + direction + tabs.length) % tabs.length;
            setTab(tabs[next][0]);
            event.currentTarget.parentElement?.querySelectorAll('button')[next]?.focus();
          }}>{label}</button>)}
      </div>
    </div>
    <div className="sw-db" role="tabpanel" id={`${tabId}-panel`} aria-labelledby={`${tabId}-${tab}`}>
      {tab === 'fields' && <>
        <div className="sw-find sw-field-find">
          <Icon name="search" size="sm" />
          <input type="search" className="da-input sw-field-search" aria-label="搜索表内字段" placeholder="搜索字段名或显示名"
            value={fieldQuery} onChange={event => setFieldQuery(event.target.value)} />
        </div>
        {!!keyword && !visiblePlain.length && !visibleTimes.length && !visibleCalc.length && !visibleRelations.length &&
          <p className="sw-hint" role="status">没有匹配的字段。</p>}
        {(!keyword || visiblePlain.length > 0) && <FieldGroup {...groupProps('plain')} label="普通列" count={visiblePlain.length}>
          {visiblePlain.map(fieldItem)}
        </FieldGroup>}
        {(!keyword || visibleTimes.length > 0) && <FieldGroup {...groupProps('time')} label="日期列" count={visibleTimes.length}>
          {visibleTimes.map(fieldItem)}
          {!visibleTimes.length && <p className="sw-hint">没有识别到日期列。</p>}
        </FieldGroup>}
        {feedback}
        {(!keyword || visibleCalc.length > 0) && <FieldGroup {...groupProps('calculated')} label="衍生列" count={visibleCalc.length}
          action={editable && <button type="button" className="sw-link sw-add-calc" aria-label="新增衍生列"
            disabled={!canEdit} title={blocked ?? undefined} onClick={() => { if (editGuard.current?.() !== false) onAddCalculated(); }}>＋ 新增衍生列</button>}>
          {visibleCalc.map(fieldItem)}
          {!visibleCalc.length && <p className="sw-hint">暂无衍生列。</p>}
        </FieldGroup>}
        {(!keyword || visibleRelations.length > 0) && <FieldGroup {...groupProps('related')} label="关联字段" count={visibleRelations.length}>
        {visibleRelations.map(r => {
          const target = r.left === model.name ? r.right : r.left;
          const keys = (r.left === model.name ? r.sourceColumns : r.targetColumns).join('、');
          return <button key={r.name} type="button" className="sw-li" onClick={() => onSelect({ kind: 'relation', name: r.name })}>
            <span className="sw-k sw-k-rel">关联</span><span className="sw-li-main"><span><b>{target}</b></span><span className="sw-li-desc">按 {keys || r.condition} 关联，问数时可直接使用 {target} 的字段</span></span>
            <span className="sw-role">{joinLabel(r.left === model.name ? r.joinType : reverse(r.joinType))}</span>
          </button>;
        })}
        {!relations.length && <p className="sw-hint">没有关系。{editable && '可以在左侧"关系"旁点 + 新增。'}</p>}
        </FieldGroup>}
      </>}
      {tab === 'info' && <dl className="sw-dl sw-model-properties">
        {alias && <><dt>业务名称</dt><dd>{alias}</dd></>}
        <dt>技术名</dt><dd className="sw-mono"><ModelIdentifier name={model.name} /></dd>
        <dt>表角色</dt><dd>{model.tableRole === 'FACT' ? '事实表' : model.tableRole === 'DIMENSION' ? '维度表' : model.derived ? '由 SQL 定义的模型' : '未标注'}</dd>
        {standalonePreference && <><dt>关联建议</dt><dd className="sw-standalone-preference">
          <span className="sw-muted">{standalonePreference.marked ? '已标记为独立使用' : '在使用建议中显示'}</span>
          <button type="button" className="sw-link" onClick={standalonePreference.toggle}>
            {standalonePreference.marked ? '重新显示关联建议' : '标记为独立使用'}
          </button>
        </dd></>}
        <dt>主键</dt><dd>{keys.length ? keys.map(column => <button key={column.name} className="sw-link sw-mono" type="button"
          onClick={() => onSelect({ kind: 'field', model: model.name, column: column.name })}>{column.name}</button>) : '未配置'}</dd>
        {modelPath && <><dt>工程文件</dt><dd className="sw-mono">{modelPath}</dd></>}
        <dt>表说明</dt><dd>{description === null ? <>{model.description || '—'}{editable && <div><button type="button" className="sw-link" disabled={!canEdit || !modelPath} onClick={() => setDescription(model.description ?? '')}>编辑表说明</button></div>}</>
           : <form onSubmit={event => { event.preventDefault(); void saveDescription(); }}>
             <textarea className="da-input" aria-label="表说明" rows={4} value={description} disabled={descriptionBusy} onChange={event => setDescription(event.target.value)} />
             <div className="sw-actions"><button type="button" className="da-btn da-btn-sm" disabled={descriptionBusy} onClick={() => { setDescription(null); setDescriptionError(''); }}>取消</button>
               <button type="submit" className="da-btn da-btn-sm da-btn-primary" disabled={!canEdit || descriptionBusy || description.trim() === (model.description ?? '')}>保存到草稿</button></div>
             {descriptionError && <p role="alert" className="sw-error">{descriptionError}</p>}
           </form>}</dd>
      </dl>}
      {tab === 'rules' && <RuleSources {...{ groupId, model, editable, canEdit, onSelect, newRule, relatedFields, onDocuments }} rules={related} />}
    </div>
    {yaml && modelPath && source !== undefined && <ModelYamlDialog groupId={groupId} name={model.name} path={modelPath} source={source}
      publishedSource={editable ? baselineFiles?.get(modelPath) : undefined} editable={editable} canEdit={canEdit}
      startEditing={yaml === 'edit'} onClose={() => setYaml(null)} onChanged={onChanged} />}
    {onAsk && <div className="sw-df"><button type="button" className="da-btn da-btn-sm" onClick={() => onAsk(`请调整模型 ${model.name}：`)}>问助手修改这张表</button></div>}
  </>;
}

/**
 * spec117 规则与原文: the rules that mention this model with their exact text, and the passages of
 * the business documents that mention the model or its columns (MANAGE only; documents are draft).
 */
function RuleSources({ groupId, model, rules, editable, canEdit, onSelect, newRule, relatedFields, onDocuments }: {
  groupId: string; model: SemanticModel; rules: RuleItem[]; editable: boolean; canEdit: boolean;
  onSelect: (s: Selection) => void; newRule: (rule: RuleItem) => MarkTone;
  relatedFields: ReadonlyMap<RuleItem, SemanticColumn[]>; onDocuments?: () => void;
}) {
  const [docs, setDocs] = useState<{ name: string; content: string }[] | null>(null);
  const [documentError, setDocumentError] = useState(false);
  useEffect(() => {
    if (!editable) return undefined;
    let cancelled = false;
    void listDocuments(groupId).then(list => Promise.all(list.filter(d => d.status !== 'DELETED').map(d => getDocumentContent(groupId, d.name))))
      .then(value => { if (!cancelled) setDocs(value); }).catch(() => { if (!cancelled) setDocumentError(true); });
    return () => { cancelled = true; };
  }, [groupId, editable]);
  const terms = [model.name, ...model.columns.flatMap(c => [c.displayName ?? '', c.name.length >= 4 ? c.name : ''])].filter(t => t.trim().length >= 2);
  const passages = (docs ?? []).flatMap(d => d.content.split(/\n\s*\n/).map(p => p.trim()).filter(p => p && terms.some(t => p.includes(t)))
    .map(p => ({ doc: d.name, text: p.length > 400 ? `${p.slice(0, 400)}…` : p })));
  return <div className="sw-model-rules">
    <header>
      <h4>引用它的规则 <span className="sw-muted">{rules.length}</span></h4>
      {canEdit && <WorkspaceMenu label="新增关联规则" triggerLabel="新增规则" triggerIcon="plus"
        items={SECTIONS.slice(0, 3).map(section => ({ label: section.label, icon: 'file', onClick: () => onSelect({ kind: 'newRule', heading: section.heading }) }))} />}
    </header>
    {rules.length === 0 && <div className="sw-empty">没有规则提到这张表。</div>}
    {rules.map(r => {
      const label = sectionOf(r.heading)?.label ?? '未归类';
      const title = r.filter?.scope ?? r.term?.term ?? r.canonical?.scope ?? label;
      const summary = r.filter?.condition ?? r.term?.meaning ?? r.canonical?.target ?? ruleSummary(r);
      const fields = relatedFields.get(r) ?? [];
      return <article key={`${r.path}:${r.text}`} className="sw-rule-card">
        <div className="sw-rule-card-top">
          <button type="button" className="sw-rule-title" onClick={() => onSelect({ kind: 'rule', path: r.path, text: r.text })}>{title}</button>
          {title !== label && <span className="sw-chip">{label}</span>}<ToneTag tone={newRule(r)} />
        </div>
        <p>{summary}</p>
        {fields.length > 0 && <div className="sw-rule-fields"><span>关联字段</span>{fields.map(column =>
          <button key={column.name} type="button" className="sw-chip" title={column.name} onClick={() => onSelect({ kind: 'field', model: model.name, column: column.name })}>{columnLabel(column)}</button>)}</div>}
        <details className="sw-src-raw">
          <summary title={r.path}>规则原文 · {r.path.split('/').pop()}{r.heading ? ` / ${r.heading}` : ''}</summary>
          <blockquote>{r.text}</blockquote>
        </details>
      </article>;
    })}
    {editable && <div className="sw-rule-materials">
      <div>
        <span>{documentError ? '业务文档读取失败' : docs === null ? '正在查找相关资料…' : passages.length ? `文档中有 ${passages.length} 条可能相关的段落` : '没有匹配到业务文档段落'}</span>
        {onDocuments && <button type="button" className="sw-link" onClick={onDocuments}>去资料查看</button>}
      </div>
      {passages.length > 0 && <details><summary>预览相关段落</summary>
        {passages.slice(0, 8).map((p, i) => <div key={i} className="sw-passage"><span className="sw-chip">{p.doc}</span><p>{p.text}</p></div>)}
      </details>}
    </div>}
  </div>;
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

function FieldGroup({ label, count, open, onToggle, action, children }: {
  label: string; count: number; open: boolean; onToggle: (open: boolean) => void; action?: ReactNode; children: ReactNode;
}) {
  return <div className={`sw-field-group-wrap${action ? ' sw-field-group-with-action' : ''}`}>
    <details className="sw-field-group" open={open} onToggle={event => {
      if (event.currentTarget.open !== open) onToggle(event.currentTarget.open);
    }}>
      <summary>{label} <span className="sw-muted">{count}</span></summary>
      {children}
    </details>
    {action && <div className="sw-field-group-action">{action}</div>}
  </div>;
}

type FieldValues = ReturnType<typeof fieldValues>;

function FieldDrawer({ model, column, snapshot, rules, editable, canEdit, disabledTitle, onSelect, onAsk, onEditCalculated, onClearFeedback, onSave, busy, feedback, editGuard, fromCheck, editDescription }: {
  model: SemanticModel; column: SemanticColumn; snapshot: SemanticSnapshot; rules: RuleItem[]; editable: boolean; canEdit: boolean; disabledTitle?: string;
  onSelect: (s: Selection) => void; onAsk?: (text: string) => void;
  fromCheck?: boolean; editDescription?: boolean;
  onEditCalculated: () => void; onClearFeedback: () => void; editGuard: FieldEditGuard;
  onSave: (edit: DraftItemEdit, done: string, after?: () => void) => Promise<void>; busy: boolean; feedback: ReactNode;
}) {
  const calculated = column.calculated;
  const inferred = roleOf(model, column, snapshot.relations);
  const currentValues = fieldValues(column);
  const [editor, setEditor] = useState<{ initial: FieldValues; values: FieldValues; focusDescription?: boolean } | null>(() =>
    editDescription && canEdit && !calculated ? { initial: currentValues, values: currentValues, focusDescription: true } : null);
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
      <div className="sw-dh-row">
        {fromCheck ? <BackToChecks onSelect={onSelect} />
          : <button type="button" className="sw-back" onClick={() => onSelect({ kind: 'model', model: model.name })}>← {model.name}</button>}
        <span className="sw-grow" /><CloseButton onSelect={onSelect} />
      </div>
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
      {fromCheck && <p className="sw-hint" title={model.name}>{modelTitle(model) ?? model.name}{editing && !column.description?.trim() ? ' · 补充字段的业务含义、单位或适用口径。' : ''}</p>}
      {editing ? <label className="sw-fld"><span>显示名</span>
        <input className="da-input" autoFocus={!editor?.focusDescription} disabled={inputDisabled} value={values.displayName} onChange={event => change({ displayName: event.target.value })} placeholder="例如 场景名称（可不填）" />
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
          <textarea className="da-input" rows={4} autoFocus={editor?.focusDescription} disabled={inputDisabled} value={values.description} onChange={event => change({ description: event.target.value })} placeholder="业务含义、单位、取值说明" />
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

function RelationDrawer({ snapshot, relation, left, fromCheck, editable, canEdit, disabledTitle, onSelect, onSave, busy, feedback, ask }: {
  snapshot: SemanticSnapshot; relation?: SemanticRelation; left?: string; editable: boolean; canEdit: boolean; disabledTitle?: string;
  fromCheck?: boolean;
  onSelect: (s: Selection) => void; onSave: (edit: DraftItemEdit, done: string, after?: () => void) => Promise<void>; busy: boolean; feedback: ReactNode;
  ask: (text: string) => ReactNode;
}) {
  const creating = !relation;
  const models = snapshot.models;
  const [leftModel, setLeftModel] = useState(relation?.left ?? left ?? models[0]?.name ?? '');
  const [rightModel, setRightModel] = useState(relation?.right ?? (fromCheck ? '' : models.find(m => m.name !== (left ?? models[0]?.name))?.name ?? ''));
  const [leftColumn, setLeftColumn] = useState(relation?.sourceColumns[0] ?? '');
  const [rightColumn, setRightColumn] = useState(relation?.targetColumns[0] ?? '');
  const [joinType, setJoinType] = useState(relation?.joinType || (fromCheck ? '' : 'MANY_TO_ONE'));
  const [description, setDescription] = useState(relation?.description ?? '');
  const columnsOf = (name: string) => models.find(m => m.name === name)?.columns.filter(c => !c.calculated) ?? [];
  const readOnly = !editable;
  return <>
    <div className="sw-dh">
      {fromCheck && <BackToChecks onSelect={onSelect} filter="advice" />}
      <div className="sw-dh-row"><span className="sw-ico sw-ico-rel">关</span><strong>{creating ? '新增关系' : `${relation!.left} → ${relation!.right}`}</strong><span className="sw-grow" /><CloseButton onSelect={onSelect} /></div>
    </div>
    <div className="sw-db">
      {fromCheck && <p className="sw-check-guidance">选择需要补充字段的目标表，核对两端字段的业务含义与唯一性，再选择对应关系。</p>}
      <div className="sw-fld"><span>关联条件</span>
        {creating ? <>
          <div className="sw-pair"><select className="da-input" aria-label="起始模型" value={leftModel} onChange={e => {
            const next = e.target.value;
            setLeftModel(next); setLeftColumn('');
            if (next === rightModel) { setRightModel(''); setRightColumn(''); }
          }}>{models.map(m => <option key={m.name} value={m.name}>{modelTitle(m) ?? m.name}</option>)}</select>
            <select className="da-input" aria-label="起始字段" value={leftColumn} onChange={e => setLeftColumn(e.target.value)}><option value="">选择字段</option>{columnsOf(leftModel).map(c => <option key={c.name} value={c.name}>{columnLabel(c)}{c.displayName ? ` (${c.name})` : ''}</option>)}</select></div>
          <div className="sw-eq">=</div>
          <div className="sw-pair"><select className="da-input" aria-label="目标模型" value={rightModel} onChange={e => { setRightModel(e.target.value); setRightColumn(''); }}>
            <option value="">选择目标模型</option>
            {models.filter(m => m.name !== leftModel).map(m => <option key={m.name} value={m.name}>{modelTitle(m) ?? m.name}</option>)}</select>
            <select className="da-input" aria-label="目标字段" disabled={!rightModel} value={rightColumn} onChange={e => setRightColumn(e.target.value)}><option value="">选择字段</option>{columnsOf(rightModel).map(c => <option key={c.name} value={c.name}>{columnLabel(c)}{c.displayName ? ` (${c.name})` : ''}</option>)}</select></div>
        </> : <code className="sw-code">{relation!.condition}</code>}
      </div>
      <div className="sw-fld"><span>对应关系{(!creating || rightModel) && `（${creating ? leftModel : relation!.left} 对 ${creating ? rightModel : relation!.right}）`}</span>
        <div role="group" aria-label="对应关系" className="sw-seg">
          {JOINS.map(j => <button key={j} type="button" aria-pressed={joinType === j} className={joinType === j ? 'on' : ''} disabled={readOnly} onClick={() => setJoinType(j)}>{joinLabel(j)}</button>)}
        </div>
        <span className="sw-hint">{joinType ? JOIN_HINT[joinType] : '请选择两表匹配时的对应关系。'}</span>
      </div>
      <label className="sw-fld"><span>说明</span><textarea className="da-input" rows={2} readOnly={readOnly} value={description} onChange={e => setDescription(e.target.value)} placeholder="例如 每笔订单对应一个客户；客户编号唯一" /></label>
      <div className="sw-file">写入 <span className="sw-mono">relationships.yml</span></div>
      {feedback}
    </div>
    {editable && <div className="sw-df">
      <button type="button" className="da-btn da-btn-primary da-btn-sm" disabled={!canEdit || busy || (creating && (!leftColumn || !rightColumn || !rightModel || !joinType))} title={disabledTitle}
        onClick={() => void (creating
          ? onSave({ category: 'RELATIONSHIP', leftModel, leftColumn, rightModel, rightColumn, joinType, description }, '关系已通过 Wren 校验并加入草稿。', () => onSelect(fromCheck ? { kind: 'problems', filter: 'advice' } : null))
          : onSave({ category: 'RELATIONSHIP', name: relation!.name, joinType, description }, '已保存到草稿，记为"你的编辑"。'))}>{busy ? '保存中…' : creating ? '校验并新增' : '保存到草稿'}</button>
      {relation && ask(`请检查关系 ${relation.left} → ${relation.right}（${relation.condition}）：`)}
      <span className="sw-grow" />
      {relation && <button type="button" className="da-btn da-btn-sm sw-danger" disabled={!canEdit || busy} title={disabledTitle}
        onClick={() => { if (window.confirm(`删除关系 ${relation.left} → ${relation.right}？依赖它的问答需要另行检查。`)) void onSave({ category: 'RELATIONSHIP', name: relation.name, delete: true }, '已删除', () => onSelect(null)); }}>删除关系</button>}
    </div>}
  </>;
}
