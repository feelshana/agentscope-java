import { apiFetch, getToken } from './auth';

/** spec149: where a caliber row last came from. */
export interface CaliberSource { kind: 'document' | 'chat' | 'edit'; document: string | null; user: string | null; at: string; hash: string }

/** A value written in a caliber the table is not known to contain (sample: not seen in a sample). */
export interface CaliberValueIssue { column: string; value: string; kind: 'missing' | 'sample' }

export interface CaliberRow {
  name: string;
  group: string | null;
  condition: string;
  where: Record<string, string[]>;
  synonyms: string[];
  note: string | null;
  pending: string | null;
  combine: string | null;
  source: CaliberSource | null;
  valueIssues: CaliberValueIssue[];
  review: CaliberReviewState;
  reviewedBy: string | null;
  verifiedBy: string | null;
  unit: string | null;
}

/** spec153 P1.5: a column the list uses, with its display name and counted values (null: not counted). */
export interface CaliberColumn { name: string; label: string | null; values: string[] | null }

/** A person's edit of one caliber; blank texts are removed, the name cannot change. */
export interface CaliberRowEdit {
  group: string; synonyms: string[]; where: Record<string, string[]>;
  unit: string; note: string; pending: string;
  /** Rows of several values add up (combine: sum), confirmed by the person. */
  combine: boolean;
}

/** A document's version of a caliber kept back because a person had changed it. */
export interface CaliberConflict { list: string; caliber: string; keptKind: string; document: string | null; proposed: Record<string, unknown> | null; at: string }

/** spec149 P5: a table selection key (business or audience) and its wordings. */
export interface CaliberKey { name: string; synonyms: string[] }
export interface CaliberMeasure { column: string; name: string; label: string | null; description: string | null; synonyms: string[]; isDefault: boolean }
/** A scope dimension: summary value, default when the user names none, and allowed values. */
export interface CaliberScope { column: string; name: string; label: string | null; total: string | null; defaultValue: string | null; values: string[] }
export interface CaliberScenario {
  subject: string;
  description: string | null;
  time: { column: string; grain: string; format: string } | null;
  measures: CaliberMeasure[];
  fixedScope: Record<string, string>;
  useFor: string[];
  notFor: string[];
  business: CaliberKey | null;
  audience: CaliberKey | null;
  scope: CaliberScope[];
}

export type CaliberPublishedState = 'NONE' | 'CURRENT' | 'OLDER';

/** NO_DOCUMENT: a caliber knowledge base's table with neither a list nor a declaring document (spec153). */
export type CaliberTableStatus = 'DRAFTABLE' | 'OUTDATED' | 'CURRENT' | 'MANUAL' | 'NO_DOCUMENT';

export interface CaliberTable {
  model: string;
  listPath: string | null;
  subject: string | null;
  status: CaliberTableStatus;
  documents: string[];
  changedDocuments: string[];
  viewName: string | null;
  cubeName: string | null;
  errors: string[];
  rows: CaliberRow[];
  conflicts: CaliberConflict[];
  scenarioReviewed: boolean;
  /** Whether the draft list ships on the next publish; otherwise the published one keeps serving. */
  publishable: boolean;
  /** NONE: never published (query uses the base model); CURRENT: published as drafted; OLDER: query uses an earlier version. */
  published: CaliberPublishedState;
  viewYaml: string | null;
  cubeYaml: string | null;
  ruleWarnings: string[];
  /** Reviewed together as the table description ("表说明已核对"). */
  scenario: CaliberScenario | null;
  /** Scope totals, defaults and listed values the table is not known to contain. */
  scopeIssues: CaliberValueIssue[];
  /** spec153 P1.5: columns the list uses, for the row edit form. */
  columns: CaliberColumn[];
}

export async function getCalibers(groupId: string): Promise<CaliberTable[]> {
  const token = getToken();
  const response = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/calibers`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!response.ok) {
    const detail = await response.json().catch(() => ({})) as { message?: string; detail?: string };
    throw new Error(detail.message || detail.detail || `读取口径清单失败（${response.status}）`);
  }
  return response.json() as Promise<CaliberTable[]>;
}

/** spec149 P2: a row's review state. */
export type CaliberReviewState = 'REVIEW_PENDING' | 'VERIFY_PENDING' | 'PROBLEM' | 'PASSED';

/** A generated verification question and how the draft expands it (never executed). */
/** {@code segment}: this caliber's SELECT in the generated View (all conditions on the table); {@code expanded}: the Cube query over the View. */
export interface CaliberCheck { caliber: string; question: string; query: string; segment: string; expanded: string | null; error: string | null }

async function post<T>(groupId: string, suffix: string, body: unknown): Promise<T> {
  const token = getToken();
  const response = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/calibers${suffix}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    const detail = await response.json().catch(() => ({})) as { message?: string; detail?: string };
    throw new Error(detail.message || detail.detail || `口径操作失败（${response.status}）`);
  }
  return response.json() as Promise<T>;
}

/** A person's review of the scenario and/or rows as currently shown. */
export const reviewCalibers = (groupId: string, listPath: string, scenario: boolean, calibers: string[]) =>
  post<CaliberTable>(groupId, '/review', { listPath, scenario, calibers });
export const verifyCalibers = (groupId: string, listPath: string, calibers?: string[]) =>
  post<CaliberCheck[]>(groupId, '/verify', { listPath, calibers: calibers ?? null });
export const editCaliber = (groupId: string, listPath: string, name: string, row: CaliberRowEdit) =>
  post<CaliberTable>(groupId, '/row', { listPath, name, row });
export const judgeCalibers =(groupId: string, listPath: string, verdicts: Record<string, 'EXPECTED' | 'PROBLEM'>) =>
  post<CaliberTable>(groupId, '/verdict', { listPath, verdicts });
