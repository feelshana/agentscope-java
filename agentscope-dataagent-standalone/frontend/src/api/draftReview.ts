import { apiFetch, getToken } from './auth';

/** Where a draft change came from (spec092); "platform" means no recorded writer. */
export type ChangeSource = 'auto' | 'document' | 'question' | 'chat' | 'confirm' | 'edit' | 'upload' | 'platform';

export interface DraftChange {
  path: string;
  category: 'MODEL' | 'RELATIONSHIP' | 'RULE' | 'CAVEAT' | 'DOCUMENT' | 'EXAMPLE' | 'SAMPLE' | 'OTHER';
  kind: 'ADDED' | 'MODIFIED' | 'REMOVED';
  summary: string;
  sources: ChangeSource[];
  authors: string[];
  lastChangedAt: string | null;
  userContentChanged: boolean;
  revertible: boolean;
}

export interface DraftReview {
  changes: DraftChange[];
  currentUser: string;
  changesByOthers: number;
  assistantRunning: boolean;
}

export interface BusinessDocument {
  name: string;
  status: 'ADDED' | 'MODIFIED' | 'UNCHANGED' | 'DELETED';
  chars: number;
  updatedAt: string | null;
  previousVersions: number;
}

const base = (groupId: string) => `/api/dataset-groups/${encodeURIComponent(groupId)}/modeling`;

function headers(json = false): Record<string, string> {
  const token = getToken();
  return {
    ...(json ? { 'Content-Type': 'application/json' } : {}),
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

async function read<T>(response: Response, fallback: string): Promise<T> {
  if (!response.ok) {
    const detail = await response.json().catch(() => ({})) as { message?: string; detail?: string };
    throw new Error(detail.message || detail.detail || `${fallback}（${response.status}）`);
  }
  return response.json() as Promise<T>;
}

export async function getDraftReview(groupId: string): Promise<DraftReview> {
  return read(await apiFetch(`${base(groupId)}/draft`, { headers: headers() }), '读取草稿修改失败');
}

export async function revertDraftFile(groupId: string, path: string): Promise<DraftReview> {
  return read(await apiFetch(`${base(groupId)}/draft/revert`, {
    method: 'POST', headers: headers(true), body: JSON.stringify({ path }),
  }), '撤销修改失败');
}

export async function discardDraft(groupId: string): Promise<DraftReview> {
  return read(await apiFetch(`${base(groupId)}/draft/discard`, { method: 'POST', headers: headers() }), '放弃草稿失败');
}

export async function listDocuments(groupId: string): Promise<BusinessDocument[]> {
  return read(await apiFetch(`${base(groupId)}/documents`, { headers: headers() }), '读取业务文档失败');
}

/** Adds a document, or replaces the one with the same name (the old version is kept). */
export async function uploadDocument(groupId: string, file: File): Promise<BusinessDocument> {
  const form = new FormData();
  form.append('file', file, file.name);
  return read(await apiFetch(`${base(groupId)}/documents`, { method: 'PUT', headers: headers(), body: form }), '上传业务文档失败');
}

export async function deleteDocument(groupId: string, name: string): Promise<void> {
  await read(await apiFetch(`${base(groupId)}/documents/${encodeURIComponent(name)}`, {
    method: 'DELETE', headers: headers(),
  }), '删除业务文档失败');
}

export const SOURCE_LABEL: Record<ChangeSource, string> = {
  auto: '自动建模', document: '文档更新', question: '问题处理', chat: '你的对话', confirm: '你的确认',
  edit: '你的编辑', upload: '上传', platform: '平台',
};

/** The user's own edit of a drafted text file in the review page. */
export async function editDraftFile(groupId: string, path: string, content: string): Promise<DraftReview> {
  return read(await apiFetch(`${base(groupId)}/draft/edit`, {
    method: 'POST', headers: headers(true), body: JSON.stringify({ path, content }),
  }), '保存修改失败');
}

/** A question the assistant needs the user to decide; the first option is recommended. */
export interface Confirmation {
  id: string;
  question: string;
  basis: string;
  options: { label: string; rule: string }[];
  createdAt: string;
}

export interface ConfirmationAnswer { id: string; answer: string | null; dismissed: boolean }

export interface ConfirmationDecision { question: string; answer: string }

export async function listConfirmations(groupId: string): Promise<Confirmation[]> {
  return read(await apiFetch(`${base(groupId)}/confirmations`, { headers: headers() }), '读取待确认问题失败');
}

/** Removes the answered questions; returns the decisions to hand to the assistant. */
export async function resolveConfirmations(groupId: string, answers: ConfirmationAnswer[]): Promise<ConfirmationDecision[]> {
  return read(await apiFetch(`${base(groupId)}/confirmations/resolve`, {
    method: 'POST', headers: headers(true), body: JSON.stringify({ answers }),
  }), '提交确认失败');
}

/** Files the review page lets the user edit directly; documents and samples are not among them. */
export const EDITABLE = (path: string) =>
  /^(models\/|knowledge\/(rules|caveats|glossary|metrics)\/|relationships\.yml$)/.test(path);

/** spec092 §7 "AI 建模结果": one table, relationship or rule bullet that differs from the published version. */
export interface DraftItem {
  id: string;
  category: 'MODEL' | 'RELATIONSHIP' | 'SEMANTIC' | 'NOTE';
  kind: 'ADDED' | 'MODIFIED' | 'REMOVED';
  title: string;
  summary: string | null;
  /** Changed descriptions of a model; name "__table__" is the table description. */
  fields: { name: string; before: string | null; after: string | null }[];
  /** Model: table description; relationship: its name; bullet: its full text. */
  text: string | null;
  basis: ChangeSource[];
  path: string;
  editable: boolean;
  deletable: boolean;
}

export interface DraftItemEdit {
  category: DraftItem['category'];
  path?: string;
  model?: string;
  tableDescription?: string;
  columns?: Record<string, string>;
  name?: string;
  joinType?: string;
  condition?: string;
  original?: string;
  text?: string;
  delete?: boolean;
}

/** {@code since} (ISO time) limits the items to writes from one turn on. */
export async function getDraftItems(groupId: string, since?: string): Promise<DraftItem[]> {
  const query = since ? `?since=${encodeURIComponent(since)}` : '';
  return read(await apiFetch(`${base(groupId)}/draft/items${query}`, { headers: headers() }), '读取建模结果失败');
}

/** Edits or deletes one item; returns all items of the draft afterwards. */
export async function editDraftItem(groupId: string, edit: DraftItemEdit): Promise<DraftItem[]> {
  return read(await apiFetch(`${base(groupId)}/draft/items`, {
    method: 'POST', headers: headers(true), body: JSON.stringify(edit),
  }), '保存修改失败');
}
