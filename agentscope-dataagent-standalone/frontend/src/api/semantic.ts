import { getToken } from './auth';

export interface SemanticTerm {
  id: string;
  term: string;
  explanation: string | null;
  synonyms: string | null;
}

export interface SemanticTermRequest {
  term: string;
  explanation?: string;
  synonyms?: string;
}

/** specs/026: terms bind to one knowledge base; every call is group-scoped. */
function termsUrl(groupId: string, id?: string): string {
  const base = `/api/groups/${encodeURIComponent(groupId)}/semantic-terms`;
  return id ? `${base}/${encodeURIComponent(id)}` : base;
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

export async function listSemanticTerms(groupId: string, signal?: AbortSignal): Promise<SemanticTerm[]> {
  const res = await fetch(termsUrl(groupId), { headers: jsonHeaders(), signal });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to list terms: ${res.status}`));
  return res.json();
}

export async function createSemanticTerm(
  groupId: string,
  req: SemanticTermRequest,
): Promise<SemanticTerm> {
  const res = await fetch(termsUrl(groupId), {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to create term: ${res.status}`));
  return res.json();
}

export async function updateSemanticTerm(
  groupId: string,
  id: string,
  req: SemanticTermRequest,
): Promise<SemanticTerm> {
  const res = await fetch(termsUrl(groupId, id), {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to update term: ${res.status}`));
  return res.json();
}

export async function deleteSemanticTerm(groupId: string, id: string): Promise<void> {
  const res = await fetch(termsUrl(groupId, id), {
    method: 'DELETE',
    headers: jsonHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to delete term: ${res.status}`));
}
