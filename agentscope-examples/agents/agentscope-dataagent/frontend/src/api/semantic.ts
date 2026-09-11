import { getToken } from './auth';

export interface SemanticTerm {
  id: string;
  term: string;
  explanation: string | null;
  synonyms: string | null;
  scope: string;
}

export interface SemanticTermRequest {
  term: string;
  explanation?: string;
  synonyms?: string;
  scope?: string;
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

export async function listSemanticTerms(): Promise<SemanticTerm[]> {
  const res = await fetch('/api/semantic-terms', { headers: jsonHeaders() });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to list terms: ${res.status}`));
  return res.json();
}

export async function createSemanticTerm(req: SemanticTermRequest): Promise<SemanticTerm> {
  const res = await fetch('/api/semantic-terms', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to create term: ${res.status}`));
  return res.json();
}

export async function updateSemanticTerm(
  id: string,
  req: SemanticTermRequest,
): Promise<SemanticTerm> {
  const res = await fetch(`/api/semantic-terms/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to update term: ${res.status}`));
  return res.json();
}

export async function deleteSemanticTerm(id: string): Promise<void> {
  const res = await fetch(`/api/semantic-terms/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: jsonHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to delete term: ${res.status}`));
}
