import { useEffect, useSyncExternalStore } from 'react';
import { apiFetch, getToken } from './auth';

/** spec104: the current user's new-user checklist, as returned by GET /api/onboarding. */
export type OnboardingTaskId = 'data' | 'publish' | 'ask' | 'analyze';
export type OnboardingTipId = 'follow_ups' | 'analysis_params';
export type TourRoute = '' | 'upload' | 'datasource';

/** spec105: where the guided tour stands, saved on the account. */
export interface TourPosition {
  step: string;
  groupId: string | null;
  route: TourRoute;
  paused: boolean;
}

export interface OnboardingStatus {
  stage: 'full' | 'collapsed' | 'hidden';
  tasks: { id: OnboardingTaskId; done: boolean }[];
  completed: number;
  daysLeft: number;
  dismissed: boolean;
  tipsSeen: OnboardingTipId[];
  starters: { groupId: string; groupName: string; question: string; shared: boolean }[];
  queryable: boolean;
  unpublished: string[];
  tour: TourPosition | null;
  samples: string[];
  demoSource: boolean;
}

function authHeaders() {
  return { Authorization: `Bearer ${getToken()}` };
}

async function call(path: string, method: 'GET' | 'POST' = 'GET'): Promise<OnboardingStatus> {
  const res = await apiFetch(`/api/onboarding${path}`, { method, headers: authHeaders() });
  if (!res.ok) throw new Error(`引导状态读取失败（${res.status}）`);
  return (await res.json()) as OnboardingStatus;
}

// One status shared by the home page, the sidebar entry, the help panel and the tips.
// Keyed by the login token, so switching accounts never shows the previous user's state.
let current: OnboardingStatus | null = null;
let currentToken: string | null = null;
let loadedAt = 0;
let inflight: Promise<void> | null = null;
let inflightToken: string | null = null;
let revision = 0;
let helpOpen = false;
const listeners = new Set<() => void>();
const emit = () => listeners.forEach(l => l());
const subscribe = (l: () => void) => { listeners.add(l); return () => { listeners.delete(l); }; };

function apply(next: Promise<OnboardingStatus>): Promise<void> {
  const token = getToken();
  const requestRevision = ++revision;
  return next.then(s => {
    if (getToken() !== token || requestRevision !== revision) return;
    current = s; currentToken = token; loadedAt = Date.now(); emit();
  }).catch(() => undefined);
}

const snapshot = () => (current && currentToken === getToken() ? current : null);

/** Reloads the status; calls within a few seconds share one request unless forced. */
export function refreshOnboarding(force = false): Promise<void> {
  const token = getToken();
  if (!token) return Promise.resolve();
  if (inflight && inflightToken === token) return inflight;
  if (!force && snapshot() && Date.now() - loadedAt < 3000) return Promise.resolve();
  const pending = apply(call('')).finally(() => {
    if (inflight === pending) { inflight = null; inflightToken = null; }
  });
  inflightToken = token;
  inflight = pending;
  return pending;
}

export const dismissOnboarding = () => apply(call('/dismiss', 'POST'));
export const reopenOnboarding = () => apply(call('/reopen', 'POST'));
export const resetOnboarding = () => apply(call('/reset', 'POST'));
export const markTipSeen = (tip: OnboardingTipId) => {
  if (current && snapshot() && !current.tipsSeen.includes(tip)) {
    current = { ...current, tipsSeen: [...current.tipsSeen, tip] };
    emit();
  }
  return apply(call(`/tips/${tip}`, 'POST'));
};

/** Best effort: a lost save only means the tour resumes one step earlier. */
export async function saveTour(tour: TourPosition): Promise<void> {
  await apiFetch('/api/onboarding/tour', {
    method: 'PUT',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(tour),
  }).catch(() => undefined);
}

export async function clearTour(): Promise<void> {
  await apiFetch('/api/onboarding/tour', { method: 'DELETE', headers: authHeaders() }).catch(() => undefined);
  void refreshOnboarding(true);
}

/** A built-in demo file as a File, ready for the normal upload flow. */
export async function fetchSample(name: string): Promise<File> {
  const res = await apiFetch(`/api/onboarding/samples/${encodeURIComponent(name)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`示例文件读取失败（${res.status}）`);
  return new File([await res.blob()], name, { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
}

export function setHelpOpen(open: boolean) {
  helpOpen = open;
  emit();
}

/** Current status (null until loaded); loads it on first use. */
export function useOnboarding(): OnboardingStatus | null {
  const token = getToken();
  const status = useSyncExternalStore(subscribe, snapshot, snapshot);
  useEffect(() => { void refreshOnboarding(); }, [token]);
  return status;
}

export function useHelpOpen(): boolean {
  return useSyncExternalStore(subscribe, () => helpOpen, () => false);
}
