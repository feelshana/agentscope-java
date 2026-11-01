import type { SemanticModel, SemanticRelation, SemanticSnapshot } from './semanticSnapshot';

export const CARD_WIDTH = 232;
const HEAD = 44;
const ROW = 28;
const MORE = 28;
const COLUMN_GAP = 150;
const ROW_GAP = 28;
const PADDING = 24;
const WRAP = 4;

export interface CardRow { name: string; mark: string }
export interface Card { model: SemanticModel; x: number; y: number; height: number; rows: CardRow[]; hidden: number }
export interface Edge { key: string; relation: SemanticRelation; path: string; x: number; y: number }
export interface Layout { cards: Card[]; edges: Edge[]; width: number; height: number }

/** The many side reads left to right into the one side, so facts land left of dimensions. */
const direction = (r: SemanticRelation) => r.joinType === 'ONE_TO_MANY' ? { from: r.right, to: r.left } : { from: r.left, to: r.right };

/** Longest path from the many side; the bound stops a relationship cycle from looping. */
function columnsOf(snapshot: SemanticSnapshot) {
  const column = new Map(snapshot.models.map(m => [m.name, 0]));
  for (let pass = 0; pass < snapshot.models.length; pass++) {
    let moved = false;
    for (const r of snapshot.relations) {
      const { from, to } = direction(r);
      const next = (column.get(from) ?? 0) + 1;
      if (column.has(to) && (column.get(to) ?? 0) < next) { column.set(to, next); moved = true; }
    }
    if (!moved) break;
  }
  return column;
}

function keyRows(model: SemanticModel, relations: SemanticRelation[]) {
  const marks = new Map<string, string>();
  for (const r of relations) {
    if (r.left === model.name) r.sourceColumns.forEach(c => marks.set(c, r.joinType === 'ONE_TO_MANY' ? '关联键' : 'FK'));
    if (r.right === model.name) r.targetColumns.forEach(c => marks.set(c, r.joinType === 'ONE_TO_MANY' ? 'FK' : '关联键'));
  }
  // A primary key is only ever what the model declares.
  for (const c of model.columns) if (c.primaryKey) marks.set(c.name, 'PK');
  return model.columns.filter(c => marks.has(c.name)).map(c => ({ name: c.name, mark: marks.get(c.name) ?? '' }));
}

export function layoutRelations(snapshot: SemanticSnapshot, expanded: Set<string>): Layout {
  const related = new Set(snapshot.relations.flatMap(r => [r.left, r.right]));
  const connected = snapshot.models.filter(m => related.has(m.name));
  const loose = snapshot.models.filter(m => !related.has(m.name));
  const cards = new Map<string, Card>();
  const build = (model: SemanticModel): Card => {
    const keys = keyRows(model, snapshot.relations);
    const names = new Set(keys.map(k => k.name));
    const rest = model.columns.filter(c => !names.has(c.name)).map(c => ({ name: c.name, mark: '' }));
    const open = expanded.has(model.name);
    const rows = open ? [...keys, ...rest] : keys;
    return { model, x: 0, y: 0, rows, hidden: rest.length, height: HEAD + rows.length * ROW + (rest.length ? MORE : 0) };
  };

  const levels = columnsOf(snapshot);
  const columns: SemanticModel[][] = [];
  connected.forEach(m => { (columns[levels.get(m.name) ?? 0] ??= []).push(m); });
  // One barycenter pass keeps related cards level and lines from crossing needlessly.
  const order = new Map<string, number>();
  columns.forEach(list => {
    const weight = (m: SemanticModel) => {
      const near = snapshot.relations.filter(r => direction(r).to === m.name).map(r => order.get(direction(r).from)).filter((n): n is number => n !== undefined);
      return near.length ? near.reduce((a, b) => a + b, 0) / near.length : Number.MAX_SAFE_INTEGER;
    };
    [...list].sort((a, b) => weight(a) - weight(b)).forEach((m, i) => { order.set(m.name, i); });
    list.sort((a, b) => (order.get(a.name) ?? 0) - (order.get(b.name) ?? 0));
  });

  let height = 0;
  const place = (list: SemanticModel[], index: number) => {
    let y = PADDING;
    for (const model of list) {
      const card = build(model);
      card.x = PADDING + index * (CARD_WIDTH + COLUMN_GAP);
      card.y = y;
      cards.set(model.name, card);
      y += card.height + ROW_GAP;
    }
    height = Math.max(height, y);
  };
  columns.forEach((list, i) => place(list ?? [], i));
  // Unrelated models get their own columns, so they can never read as joined to a neighbour.
  const start = columns.length;
  for (let i = 0; i < loose.length; i += WRAP) place(loose.slice(i, i + WRAP), start + i / WRAP);
  const count = start + Math.ceil(loose.length / WRAP);

  const rowY = (card: Card, column: string) => {
    const index = card.rows.findIndex(r => r.name === column);
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
      const sameColumn = left.x === right.x;
      const forward = left.x <= right.x;
      const x1 = forward ? left.x + CARD_WIDTH : left.x;
      const x2 = sameColumn ? right.x + CARD_WIDTH : forward ? right.x : right.x + CARD_WIDTH;
      const bend = sameColumn ? x1 + 70 : (x1 + x2) / 2;
      edges.push({ key: `${relation.name}:${index}`, relation, path: `M${x1} ${y1} C${bend} ${y1} ${bend} ${y2} ${x2} ${y2}`,
        x: sameColumn ? x1 + 52 : bend, y: (y1 + y2) / 2 });
    });
  }
  return {
    cards: [...cards.values()], edges,
    width: PADDING * 2 + Math.max(count, 1) * CARD_WIDTH + Math.max(count - 1, 0) * COLUMN_GAP,
    height: Math.max(height, PADDING * 2 + HEAD),
  };
}
