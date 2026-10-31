import { useSyncExternalStore } from 'react';
import { getToken } from '../api/auth';
import { clearTour, saveTour, TourPosition, TourRoute } from '../api/onboarding';

/**
 * spec105: the guided tour's position. Pages report what really happened through tourSignal, and
 * the tour moves on; it never advances on a timer and never clicks anything for the user.
 */
export type TourStepId =
  | 'create' | 'source' | 'sample' | 'importing' | 'screening' | 'window' | 'start' | 'modeling'
  | 'review' | 'confirm' | 'trial_open' | 'trial_ask' | 'trial_confirm' | 'publish' | 'preparing' | 'ask';

export type TourSignal =
  | 'kb-created' | 'source-chosen' | 'sample-started' | 'data-ready' | 'screening-done'
  | 'window-ready' | 'model-started' | 'model-idle' | 'review-next' | 'confirm-done' | 'trial-opened'
  | 'trial-asked'
  | 'trial-confirmed' | 'published' | 'query-ready' | 'asked';

/** Each step ends on one signal; a signal already seen skips its step (e.g. nothing to screen). */
const ORDER: { id: TourStepId; on: TourSignal }[] = [
  { id: 'create', on: 'kb-created' },
  { id: 'source', on: 'source-chosen' },
  { id: 'sample', on: 'sample-started' },
  { id: 'importing', on: 'data-ready' },
  { id: 'screening', on: 'screening-done' },
  { id: 'window', on: 'window-ready' },
  { id: 'start', on: 'model-started' },
  { id: 'modeling', on: 'model-idle' },
  { id: 'review', on: 'review-next' },
  { id: 'confirm', on: 'confirm-done' },
  { id: 'trial_open', on: 'trial-opened' },
  { id: 'trial_ask', on: 'trial-asked' },
  { id: 'trial_confirm', on: 'trial-confirmed' },
  { id: 'publish', on: 'published' },
  { id: 'preparing', on: 'query-ready' },
  { id: 'ask', on: 'asked' },
];

/** True only at this moment: "nothing to confirm" before modeling says nothing about after it. */
const MOMENTARY = new Set<TourSignal>(['confirm-done', 'query-ready']);

export interface TourState {
  step: TourStepId;
  groupId: string | null;
  route: TourRoute;
  paused: boolean;
}

let state: TourState | null = null;
let stateToken: string | null = null;
let finished = false;
const seen = new Set<TourSignal>();
const listeners = new Set<() => void>();
const emit = () => listeners.forEach(l => l());
const subscribe = (l: () => void) => { listeners.add(l); return () => { listeners.delete(l); }; };
const snapshot = () => (state && stateToken === getToken() ? state : null);

function set(next: TourState | null) {
  state = next;
  stateToken = getToken();
  emit();
  void (next ? saveTour(next) : clearTour());
}

export const useTour = () => useSyncExternalStore(subscribe, snapshot, snapshot);
export const tourActive = () => { const t = snapshot(); return !!t && !t.paused; };

/** Takes over the position saved on the account, after a reload or on another device. */
export function adoptTour(saved: TourPosition | null) {
  if (finished || stateToken === getToken()) return;
  stateToken = getToken();
  state = saved && ORDER.some(s => s.id === saved.step)
    ? { step: saved.step as TourStepId, groupId: saved.groupId, route: saved.route, paused: saved.paused }
    : null;
  seen.clear();
  emit();
}

export function startTour() {
  seen.clear();
  finished = false;
  set({ step: 'create', groupId: null, route: '', paused: false });
}

/** The account's progress was reset; forget everything held for this login. */
export function forgetTour() {
  seen.clear();
  finished = false;
  state = null;
  stateToken = getToken();
  emit();
}

/** The question being checked has no SQL and none is coming; go back and ask again. */
export function retryTrialQuestion() {
  const t = snapshot();
  if (!t || t.paused || t.step !== 'trial_confirm') return;
  seen.delete('trial-asked');
  seen.delete('trial-confirmed');
  set({ ...t, step: 'trial_ask' });
}

export function pauseTour() {
  const t = snapshot();
  if (t) set({ ...t, paused: true });
}

export function resumeTour() {
  const t = snapshot();
  if (t) set({ ...t, paused: false });
}

/** Reports something that really happened; ignored for another knowledge base or outside a tour. */
export function tourSignal(signal: TourSignal, detail: { groupId?: string; route?: TourRoute } = {}) {
  const t = snapshot();
  if (!t || t.paused) return;
  if (t.groupId && detail.groupId && detail.groupId !== t.groupId) return;
  if (MOMENTARY.has(signal) && ORDER.find(s => s.id === t.step)?.on !== signal) return;
  seen.add(signal);
  let index = ORDER.findIndex(s => s.id === t.step);
  if (!seen.has(ORDER[index].on)) {
    // Remember progress such as the chosen route even when it arrives out of order.
    if (detail.groupId && !t.groupId) set({ ...t, groupId: detail.groupId });
    return;
  }
  while (index < ORDER.length && seen.has(ORDER[index].on)) index++;
  if (index >= ORDER.length) { finished = true; set(null); return; }
  set({
    step: ORDER[index].id,
    groupId: detail.groupId ?? t.groupId,
    route: detail.route ?? t.route,
    paused: false,
  });
}
