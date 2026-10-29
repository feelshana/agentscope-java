import { apiFetch, getToken } from './auth';

/** spec095: one Naming-conventions entry of a knowledge base, draft against the published one. */
export type TermStatus = 'PUBLISHED' | 'ADDED' | 'MODIFIED' | 'DELETED';

export interface BusinessTerm {
  /** Null when the entry does not follow the "术语"（同义词：…）= 解释 format. */
  term: string | null;
  synonyms: string[];
  explanation: string | null;
  /** The bullet text; sent back as `original` to edit or delete the entry. */
  text: string;
  structured: boolean;
  status: TermStatus;
  /** The published text of a modified or deleted entry. */
  publishedText: string | null;
}

export interface BusinessTerms {
  /** True with knowledge-base MANAGE: the draft is shown and can be edited. */
  editable: boolean;
  publishedVersion: number;
  rows: BusinessTerm[];
}

export interface TermChange {
  original?: string;
  term?: string;
  synonyms?: string;
  explanation?: string;
  /** Free-form entry text, used for entries that are not in the structured format. */
  text?: string;
  delete?: boolean;
}

function termsUrl(groupId: string): string {
  return `/api/dataset-groups/${encodeURIComponent(groupId)}/terms`;
}

function jsonHeaders(): Record<string, string> {
  const token = getToken();
  return {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

async function errorMessage(res: Response, fallback: string): Promise<string> {
  try {
    const body = (await res.json()) as { message?: string };
    return body.message || fallback;
  } catch {
    return fallback;
  }
}

export async function listBusinessTerms(groupId: string, signal?: AbortSignal): Promise<BusinessTerms> {
  const res = await apiFetch(termsUrl(groupId), { headers: jsonHeaders(), signal });
  if (!res.ok) throw new Error(await errorMessage(res, `读取业务术语失败：${res.status}`));
  return res.json();
}

/** Adds (no `original`), edits or deletes one entry in the draft; returns the refreshed list. */
export async function changeBusinessTerm(groupId: string, change: TermChange): Promise<BusinessTerms> {
  const res = await apiFetch(termsUrl(groupId), {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(change),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `保存业务术语失败：${res.status}`));
  return res.json();
}
