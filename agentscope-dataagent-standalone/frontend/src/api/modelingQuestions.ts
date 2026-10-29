import { apiFetch, getToken } from './auth';

export interface ModelingQuestion {
  id: string;
  question: string;
  definition: string;
  sql: string;
  modeling?: { strategy: string; reason: string; assets: { kind: string; name: string }[] } | null;
}

export interface QuestionValidation {
  validationId: string;
  status: string;
  executedAt: string;
  result: { columns: string[]; rows: Record<string, unknown>[]; truncated?: boolean } | null;
  error: string | null;
  truncated: boolean;
  confirmedBy: string | null;
  confirmedAt: string | null;
  modelHash: string;
  evidence?: { sql: string; views: { name: string; sql: string; description: string | null; path: string }[] } | null;
}

export interface QuestionReview {
  revision: string;
  question: ModelingQuestion;
  status: 'DRAFT' | 'EXECUTED' | 'CONFIRMED' | 'STALE' | 'FAILED' | 'REJECTED';
  validation: QuestionValidation | null;
  coverage?: { strategy: string; ready: boolean; message: string; assets: { kind: string; name: string }[] };
  published?: { available: boolean; current: boolean };
}

async function request<T>(groupId: string, suffix = '', body?: unknown): Promise<T> {
  const token = getToken();
  const response = await apiFetch(
    `/api/dataset-groups/${encodeURIComponent(groupId)}/modeling/questions${suffix}`,
    {
      method: body === undefined ? 'GET' : 'POST',
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    },
  );
  if (!response.ok) {
    const detail = await response.json().catch(() => ({})) as { message?: string; detail?: string };
    throw new Error(detail.message || detail.detail || `常用问题操作失败（${response.status}）`);
  }
  return response.json() as Promise<T>;
}

export const getModelingQuestions = (groupId: string) => request<QuestionReview[]>(groupId);
export const validateModelingQuestion = (groupId: string, id: string) =>
  request<QuestionReview>(groupId, `/${encodeURIComponent(id)}/validate`, {});
/** spec091: confirmation needs no prior trial run; the server re-checks SQL policy and time window. */
export const decideModelingQuestion = (groupId: string, id: string, accepted: boolean) =>
  request<QuestionReview>(groupId, `/${encodeURIComponent(id)}/decision`, { accepted });
/** spec103: the knowledge base's recommended question; {@code manual} once the modeler edited it. */
export interface RecommendedQuestion { question: string | null; manual: boolean }
export const getRecommendedTrialQuestion = (groupId: string) =>
  request<RecommendedQuestion>(groupId, '/recommended');
/** Saves the modeler's own question; an empty text returns it to generation. */
export const editRecommendedQuestion = (groupId: string, question: string) =>
  request<RecommendedQuestion>(groupId, '/recommended', { question });
export const regenerateRecommendedQuestion = (groupId: string) =>
  request<RecommendedQuestion>(groupId, '/recommended/regenerate', {});
/** Suggested question texts for prefilling the question form; empty when unavailable. */
export const getQuestionSuggestions = (groupId: string) =>
  request<{ questions: string[] }>(groupId, '/suggestions').then(r => r.questions ?? []);

export const correctModelingQuestionSql = (groupId: string, id: string, revision: string, sql: string) =>
  request<QuestionReview>(groupId, `/${encodeURIComponent(id)}/sql`, { revision, sql });
export const archiveModelingQuestion = (groupId: string, id: string, revision: string) =>
  request<{ archived: boolean }>(groupId, `/${encodeURIComponent(id)}/archive`, { revision });
