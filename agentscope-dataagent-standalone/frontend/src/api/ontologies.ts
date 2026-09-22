import { getToken } from './auth';

export interface OntologyEntry {
  id: string;
  name: string;
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/** Fetches the list of available ontology agents from the backend. */
export async function listOntologies(): Promise<OntologyEntry[]> {
  const res = await fetch('/api/ontologies', { headers: authHeaders() });
  if (!res.ok) throw new Error(`Failed to list ontologies: ${res.status}`);
  return res.json();
}
