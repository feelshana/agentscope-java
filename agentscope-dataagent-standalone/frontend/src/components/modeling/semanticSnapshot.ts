import type { KnowledgeBaseBrowse, MdlModelView, MdlView } from '../../api/semanticModeling';

/** spec106: one shape for the draft (MANAGE) and the published version (MANAGE and QUERY). */
export interface SemanticColumn {
  name: string;
  type: string | null;
  description: string | null;
  calculated: boolean;
  expression: string | null;
  primaryKey: boolean;
  /** spec117: page-maintained properties, absent in the published browse projection. */
  displayName?: string | null;
  semanticType?: string | null;
  synonyms?: string[];
  values?: string[];
  userDescribed?: boolean;
}

export interface SemanticModel {
  name: string;
  description: string | null;
  columns: SemanticColumn[];
  tableRole: string | null;
  derived: boolean;
  /** Project-relative metadata file; present only where YAML can be shown. */
  path: string | null;
}

export interface SemanticRelation {
  name: string;
  left: string;
  right: string;
  joinType: string;
  condition: string;
  sourceColumns: string[];
  targetColumns: string[];
  description: string | null;
}

export interface SemanticSnapshot {
  models: SemanticModel[];
  relations: SemanticRelation[];
}

const fromModelView = (m: MdlModelView, derived: boolean): SemanticModel => ({
  name: m.modelName,
  description: m.description,
  tableRole: m.tableRole,
  derived,
  path: m.path,
  columns: m.columns.map(c => ({
    name: c.name, type: c.type, description: c.description, calculated: c.calculated, expression: c.expression, primaryKey: c.primaryKey,
    displayName: c.displayName ?? null, semanticType: c.semanticType ?? null, synonyms: c.synonyms ?? [], values: c.values ?? [], userDescribed: !!c.userDescribed,
  })),
});

export function draftSnapshot(view: MdlView): SemanticSnapshot {
  return {
    models: [...view.models.map(m => fromModelView(m, false)), ...view.derivedModels.map(m => fromModelView(m, true))],
    relations: view.relations.map(r => ({
      name: r.name, left: r.leftModel, right: r.rightModel, joinType: r.joinType, condition: r.condition,
      sourceColumns: r.sourceColumns, targetColumns: r.targetColumns, description: r.description,
    })),
  };
}

/** The compiled MDL has only the condition; same equality terms MdlWorkspaceReader reads. */
function conditionColumns(condition: string, left: string, right: string) {
  const source: string[] = [];
  const target: string[] = [];
  for (const part of condition.split(/\s+AND\s+/i)) {
    const term = part.trim().match(/^"?([^".\s]+)"?\."?([^".\s=]+)"?\s*=\s*"?([^".\s]+)"?\."?([^".\s=]+)"?$/);
    if (!term) continue;
    if (term[1] === left && term[3] === right) { source.push(term[2]); target.push(term[4]); }
    else if (term[1] === right && term[3] === left) { source.push(term[4]); target.push(term[2]); }
  }
  return { source, target };
}

export function publishedSnapshot(browse: KnowledgeBaseBrowse): SemanticSnapshot {
  return {
    models: browse.models.map(m => ({
      name: m.name,
      description: m.description ?? null,
      tableRole: m.tableRole ?? null,
      derived: !!m.derived,
      path: `models/${m.name}/metadata.yml`,
      columns: (m.columns ?? []).map(c => ({
        name: c.name, type: c.type ?? null, description: c.description ?? null, calculated: !!c.isCalculated,
        expression: c.expression ?? null, primaryKey: !!c.isPrimaryKey || c.name === m.primaryKey,
      })),
    })),
    relations: browse.relationships.map(r => {
      const [left = '', right = ''] = r.models ?? [];
      const { source, target } = conditionColumns(r.condition ?? '', left, right);
      return { name: r.name, left, right, joinType: r.joinType ?? '', condition: r.condition ?? '', sourceColumns: source, targetColumns: target, description: r.description ?? null };
    }),
  };
}

const JOINS: Record<string, { mark: string; label: string }> = {
  MANY_TO_ONE: { mark: 'N : 1', label: '多对一' },
  ONE_TO_MANY: { mark: '1 : N', label: '一对多' },
  ONE_TO_ONE: { mark: '1 : 1', label: '一对一' },
  MANY_TO_MANY: { mark: 'N : N', label: '多对多' },
};
export const joinMark = (type: string) => JOINS[type]?.mark ?? type;
export const joinLabel = (type: string) => JOINS[type]?.label ?? type;

export const relationsOf = (snapshot: SemanticSnapshot, model: string) => snapshot.relations.filter(r => r.left === model || r.right === model);
export const unrelatedModels = (snapshot: SemanticSnapshot) => snapshot.relations.length || snapshot.models.length > 1
  ? snapshot.models.filter(m => !relationsOf(snapshot, m.name).length) : [];

/** Only what the model records; an unmarked table stays a plain model, never a guess. */
export function modelTag(model: SemanticModel, unrelated: boolean): { label: string; tone: 'fact' | 'plain' | 'warn' } {
  if (unrelated) return { label: '未关联', tone: 'warn' };
  if (model.derived) return { label: 'SQL 定义', tone: 'plain' };
  if (model.tableRole === 'FACT') return { label: '事实', tone: 'fact' };
  if (model.tableRole === 'DIMENSION') return { label: '维度', tone: 'plain' };
  return { label: '模型', tone: 'plain' };
}
