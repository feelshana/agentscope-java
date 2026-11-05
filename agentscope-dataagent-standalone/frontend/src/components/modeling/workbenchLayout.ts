import type { SemanticColumn, SemanticModel, SemanticRelation, SemanticSnapshot } from './semanticSnapshot';

/**
 * spec117 关系图: every model is a card listing its fields in three kinds — plain columns (primary
 * key marked), calculated columns and the related models a relationship brings in (shown only,
 * never written as Wren relationship columns). Lines run from key row to key row.
 */
export const CARD_W = 272;
export const HEAD = 52;
export const ROW = 28;
const MORE = 28;
const GAP_X = 140;
const GAP_Y = 32;
const PAD = 32;
const COLLAPSED = 12;
const WRAP = 4;

export type FieldKind = 'pk' | 'plain' | 'calc' | 'rel';
export interface CardRow { key: string; kind: FieldKind; name: string; column?: SemanticColumn; relation?: SemanticRelation; target?: string }
export interface Card { model: SemanticModel; x: number; y: number; height: number; rows: CardRow[]; hidden: number }
export interface Edge { key: string; relation: SemanticRelation; path: string; mid: { x: number; y: number }; from: { x: number; y: number }; to: { x: number; y: number } }
export interface Layout { cards: Card[]; edges: Edge[]; width: number; height: number }
export type Positions = Record<string, { x: number; y: number }>;

/** The many side reads left to right into the one side, so facts land left of dimensions. */
const direction = (r: SemanticRelation) => r.joinType === 'ONE_TO_MANY' ? { from: r.right, to: r.left } : { from: r.left, to: r.right };

function levels(snapshot: SemanticSnapshot) {
  const level = new Map(snapshot.models.map(m => [m.name, 0]));
  for (let pass = 0; pass < snapshot.models.length; pass++) {
    let moved = false;
    for (const r of snapshot.relations) {
      const { from, to } = direction(r);
      const next = (level.get(from) ?? 0) + 1;
      if (level.has(to) && (level.get(to) ?? 0) < next) { level.set(to, next); moved = true; }
    }
    if (!moved) break;
  }
  return level;
}

/** The rows of a card: keys and plain columns first, then calculated, then related models. */
export function cardRows(model: SemanticModel, relations: SemanticRelation[]): CardRow[] {
  const keys = new Set<string>();
  for (const r of relations) {
    if (r.left === model.name) r.sourceColumns.forEach(c => keys.add(c));
    if (r.right === model.name) r.targetColumns.forEach(c => keys.add(c));
  }
  const plain = model.columns.filter(c => !c.calculated);
  const ordered = [...plain.filter(c => c.primaryKey), ...plain.filter(c => !c.primaryKey && keys.has(c.name)), ...plain.filter(c => !c.primaryKey && !keys.has(c.name))];
  const rows: CardRow[] = ordered.map(c => ({ key: `c:${c.name}`, kind: c.primaryKey ? 'pk' : 'plain', name: c.name, column: c }));
  model.columns.filter(c => c.calculated).forEach(c => rows.push({ key: `c:${c.name}`, kind: 'calc', name: c.name, column: c }));
  relations.filter(r => r.left === model.name || r.right === model.name).forEach(r => {
    const target = r.left === model.name ? r.right : r.left;
    rows.push({ key: `r:${r.name}`, kind: 'rel', name: target, relation: r, target });
  });
  return rows;
}

export function layoutWorkbench(snapshot: SemanticSnapshot, expanded: Set<string>, moved: Positions): Layout {
  const related = new Set(snapshot.relations.flatMap(r => [r.left, r.right]));
  const level = levels(snapshot);
  const columns: SemanticModel[][] = [];
  snapshot.models.filter(m => related.has(m.name)).forEach(m => { (columns[level.get(m.name) ?? 0] ??= []).push(m); });
  const loose = snapshot.models.filter(m => !related.has(m.name));
  const cards = new Map<string, Card>();
  const build = (model: SemanticModel): Card => {
    const all = cardRows(model, snapshot.relations);
    // Keys, calculated and related rows always show; only ordinary columns fold away.
    const foldable = all.filter(r => r.kind === 'plain' && !snapshot.relations.some(rel =>
      (rel.left === model.name && rel.sourceColumns.includes(r.name)) || (rel.right === model.name && rel.targetColumns.includes(r.name))));
    const open = expanded.has(model.name) || all.length <= COLLAPSED;
    const hide = open ? new Set<string>() : new Set(foldable.slice(Math.max(0, COLLAPSED - (all.length - foldable.length))).map(r => r.key));
    const rows = all.filter(r => !hide.has(r.key));
    const foldable2 = all.length > COLLAPSED && foldable.length > 0;
    return { model, x: 0, y: 0, rows, hidden: hide.size, height: HEAD + rows.length * ROW + (foldable2 ? MORE : 0) };
  };
  let height = 0;
  let count = 0;
  const place = (list: SemanticModel[], index: number) => {
    let y = PAD;
    for (const model of list) {
      const card = build(model);
      card.x = PAD + index * (CARD_W + GAP_X);
      card.y = y;
      cards.set(model.name, card);
      y += card.height + GAP_Y;
    }
    count = Math.max(count, index + 1);
  };
  columns.forEach((list, i) => place(list ?? [], i));
  const start = columns.length;
  for (let i = 0; i < loose.length; i += WRAP) place(loose.slice(i, i + WRAP), start + i / WRAP);
  // A dragged card keeps the user's position (per browser, spec117).
  for (const card of cards.values()) {
    const p = moved[card.model.name];
    if (p) { card.x = p.x; card.y = p.y; }
  }
  let width = 0;
  for (const card of cards.values()) { width = Math.max(width, card.x + CARD_W + PAD); height = Math.max(height, card.y + card.height + PAD); }

  const rowY = (card: Card, column: string) => {
    const index = card.rows.findIndex(r => r.kind !== 'rel' && r.name === column);
    return card.y + (index < 0 ? HEAD / 2 : HEAD + index * ROW + ROW / 2);
  };
  const edges: Edge[] = [];
  for (const relation of snapshot.relations) {
    const left = cards.get(relation.left);
    const right = cards.get(relation.right);
    if (!left || !right) continue;
    const pairs = relation.sourceColumns.length ? relation.sourceColumns.map((c, i) => [c, relation.targetColumns[i] ?? '']) : [['', '']];
    pairs.forEach(([source, target], index) => {
      const y1 = rowY(left, source);
      const y2 = rowY(right, target);
      const overlap = left.x < right.x + CARD_W && right.x < left.x + CARD_W;
      const forward = left.x + CARD_W / 2 <= right.x + CARD_W / 2;
      const x1 = overlap ? left.x + CARD_W : forward ? left.x + CARD_W : left.x;
      const x2 = overlap ? right.x + CARD_W : forward ? right.x : right.x + CARD_W;
      const out1 = overlap || forward ? 1 : -1;
      const out2 = overlap ? 1 : forward ? -1 : 1;
      const reach = overlap ? 70 : Math.max(40, Math.abs(x2 - x1) / 2);
      const c1 = x1 + out1 * reach;
      const c2 = x2 + out2 * reach;
      edges.push({
        key: `${relation.name}:${index}`, relation,
        path: `M${x1} ${y1} C${c1} ${y1} ${c2} ${y2} ${x2} ${y2}`,
        mid: { x: overlap ? Math.max(x1, x2) + 52 : (x1 + x2) / 2, y: (y1 + y2) / 2 },
        from: { x: x1 + out1 * 12, y: y1 - 9 }, to: { x: x2 + out2 * 12, y: y2 - 9 },
      });
    });
  }
  return { cards: [...cards.values()], edges, width: Math.max(width, PAD * 2 + CARD_W), height: Math.max(height, PAD * 2 + HEAD) };
}

/** Both ends of a join type, read from the left model to the right one. */
export function joinEnds(type: string): [string, string] {
  switch (type) {
    case 'MANY_TO_ONE': return ['N', '1'];
    case 'ONE_TO_MANY': return ['1', 'N'];
    case 'ONE_TO_ONE': return ['1', '1'];
    case 'MANY_TO_MANY': return ['N', 'N'];
    default: return ['', ''];
  }
}

export type Role = 'IDENTIFIER' | 'DIMENSION' | 'TIME' | 'MEASURE';
export const ROLE_LABEL: Record<Role, string> = { IDENTIFIER: '标识', DIMENSION: '维度', TIME: '时间', MEASURE: '度量' };
export const ROLE_HINT: Record<Role, string> = {
  IDENTIFIER: '区分每一行或关联其他表，计数时去重',
  DIMENSION: '用来分组和筛选',
  TIME: '按日、月统计，时间门禁也用它',
  MEASURE: '可以求和、求平均的数值',
};

/** The recorded role, or a guess from keys and type that the page marks as 推断 and never writes. */
export function roleOf(model: SemanticModel, column: SemanticColumn, relations: SemanticRelation[]): { role: Role; inferred: boolean } {
  const recorded = column.semanticType as Role | null | undefined;
  if (recorded && recorded in ROLE_LABEL) return { role: recorded, inferred: false };
  const key = column.primaryKey || relations.some(r => (r.left === model.name && r.sourceColumns.includes(column.name)) || (r.right === model.name && r.targetColumns.includes(column.name)));
  const type = (column.type ?? '').toUpperCase();
  if (key) return { role: 'IDENTIFIER', inferred: true };
  if (/DATE|TIME/.test(type)) return { role: 'TIME', inferred: true };
  if (/INT|DEC|NUM|DOUBLE|FLOAT|REAL/.test(type) && !/(_id|编号|编码|code)$/i.test(column.name)) return { role: 'MEASURE', inferred: true };
  return { role: 'DIMENSION', inferred: true };
}

// ------------------------------------------------------------------ spec117 overview and focus

export const NODE_W = 228;
export const NODE_H = 64;
const NODE_GAP_X = 132;
const NODE_GAP_Y = 28;

export interface OverviewNode { model: SemanticModel; x: number; y: number }
export interface OverviewEdge { key: string; relation: SemanticRelation; path: string; mid: { x: number; y: number }; from: { x: number; y: number }; to: { x: number; y: number } }
export interface Overview { nodes: OverviewNode[]; edges: OverviewEdge[]; width: number; height: number }

/** 全图: models as compact nodes (fields stay folded), many side left of the one side. */
export function layoutOverview(snapshot: SemanticSnapshot, moved: Positions): Overview {
  const related = new Set(snapshot.relations.flatMap(r => [r.left, r.right]));
  const level = levels(snapshot);
  const columns: SemanticModel[][] = [];
  snapshot.models.filter(m => related.has(m.name)).forEach(m => { (columns[level.get(m.name) ?? 0] ??= []).push(m); });
  const loose = snapshot.models.filter(m => !related.has(m.name));
  for (let i = 0; i < loose.length; i += 6) columns.push(loose.slice(i, i + 6));
  const tallest = Math.max(1, ...columns.map(c => (c ?? []).length));
  const nodes: OverviewNode[] = [];
  columns.forEach((list, index) => {
    const items = list ?? [];
    // Shorter columns are centred against the tallest one.
    const offset = ((tallest - items.length) * (NODE_H + NODE_GAP_Y)) / 2;
    items.forEach((model, i) => nodes.push({ model, x: PAD + index * (NODE_W + NODE_GAP_X), y: PAD + offset + i * (NODE_H + NODE_GAP_Y) }));
  });
  for (const node of nodes) {
    const p = moved[node.model.name];
    if (p) { node.x = p.x; node.y = p.y; }
  }
  const at = new Map(nodes.map(n => [n.model.name, n]));
  const edges: OverviewEdge[] = [];
  snapshot.relations.forEach((relation, index) => {
    const a = at.get(relation.left);
    const b = at.get(relation.right);
    if (!a || !b) return;
    const forward = a.x + NODE_W / 2 <= b.x + NODE_W / 2;
    const overlap = a.x < b.x + NODE_W && b.x < a.x + NODE_W;
    const x1 = overlap || forward ? a.x + NODE_W : a.x;
    const x2 = overlap ? b.x + NODE_W : forward ? b.x : b.x + NODE_W;
    const y1 = a.y + NODE_H / 2;
    const y2 = b.y + NODE_H / 2;
    const out1 = overlap || forward ? 1 : -1;
    const out2 = overlap ? 1 : forward ? -1 : 1;
    const reach = overlap ? 60 : Math.max(36, Math.abs(x2 - x1) / 2);
    edges.push({
      key: `${relation.name}:${index}`, relation,
      path: `M${x1} ${y1} C${x1 + out1 * reach} ${y1} ${x2 + out2 * reach} ${y2} ${x2} ${y2}`,
      mid: { x: overlap ? Math.max(x1, x2) + 44 : (x1 + x2) / 2, y: (y1 + y2) / 2 },
      from: { x: x1 + out1 * 10, y: y1 - 9 }, to: { x: x2 + out2 * 10, y: y2 - 9 },
    });
  });
  let width = PAD * 2 + NODE_W;
  let height = PAD * 2 + NODE_H;
  for (const n of nodes) { width = Math.max(width, n.x + NODE_W + PAD); height = Math.max(height, n.y + NODE_H + PAD); }
  return { nodes, edges, width, height };
}

/** One neighbour in the focus view, with the columns that join it to the centre. */
export interface FocusLink { model: SemanticModel; relation: SemanticRelation; centerColumns: string[]; otherColumns: string[]; others: number }
export interface Focus { center: SemanticModel; incoming: FocusLink[]; outgoing: FocusLink[] }

/**
 * 聚焦: neighbours that point many-to-one into the centre go left; the ones the centre points to
 * (and one-to-one, many-to-many) go right. `others` is the neighbour's remaining relationships.
 */
export function focusOf(snapshot: SemanticSnapshot, name: string): Focus | null {
  const center = snapshot.models.find(m => m.name === name);
  if (!center) return null;
  const incoming: FocusLink[] = [];
  const outgoing: FocusLink[] = [];
  for (const r of snapshot.relations) {
    if (r.left !== name && r.right !== name) continue;
    const centerIsLeft = r.left === name;
    const otherName = centerIsLeft ? r.right : r.left;
    const model = snapshot.models.find(m => m.name === otherName);
    if (!model || otherName === name) continue;
    const link: FocusLink = {
      model, relation: r,
      centerColumns: centerIsLeft ? r.sourceColumns : r.targetColumns,
      otherColumns: centerIsLeft ? r.targetColumns : r.sourceColumns,
      others: snapshot.relations.filter(x => x !== r && (x.left === otherName || x.right === otherName)).length,
    };
    const centerIsOneSide = (r.joinType === 'MANY_TO_ONE' && !centerIsLeft) || (r.joinType === 'ONE_TO_MANY' && centerIsLeft);
    (centerIsOneSide ? incoming : outgoing).push(link);
  }
  return { center, incoming, outgoing };
}
