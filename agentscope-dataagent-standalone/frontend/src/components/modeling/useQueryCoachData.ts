import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { sessionUserId } from '../../api/auth';
import { getModelingQuestions } from '../../api/modelingQuestions';
import type { QuestionReview } from '../../api/modelingQuestions';
import { listAssetProposals } from '../../api/semanticAssets';
import type { AssetProposal } from '../../api/semanticAssets';
import type { CoachQuestion } from './queryCoachScope';
import { isScopedQuestion } from './queryCoachScope';

export type QueryCoachData = {
  reviews: QuestionReview[];
  pendingRemovals: QuestionReview[];
  proposals: AssetProposal[];
  previousSql: Record<string, string>;
  previousDefinitions: Record<string, string>;
  loading: boolean;
  error: string;
  reload: () => void;
  submitted: CoachQuestion[];
  trackSubmission: (questions: CoachQuestion[]) => void;
  updateReview: (review: QuestionReview, previousRevision: string) => void;
  removeQuestion: (id: string) => void;
};

export function useQueryCoachData(groupId: string, enabled: boolean): QueryCoachData {
  const [entries, setEntries] = useState<QuestionReview[]>([]);
  const reviews = useMemo(() => entries.filter(r => r.status !== 'REMOVAL_PENDING'), [entries]);
  const pendingRemovals = useMemo(() => entries.filter(r => r.status === 'REMOVAL_PENDING'), [entries]);
  const [proposals, setProposals] = useState<AssetProposal[]>([]);
  const [previousSql, setPreviousSql] = useState<Record<string, string>>({});
  const [previousDefinitions, setPreviousDefinitions] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const user = sessionUserId();
  const submissionKey = user ? `qc-submissions:${JSON.stringify([user, groupId])}` : undefined;
  const [submitted, setSubmitted] = useState<CoachQuestion[]>(() => {
    if (!submissionKey) return [];
    try {
      const saved: unknown = JSON.parse(sessionStorage.getItem(submissionKey) ?? '[]');
      return Array.isArray(saved) ? saved.filter(question => typeof question?.id === 'string'
        && isScopedQuestion(question.id) && typeof question.question === 'string') : [];
    } catch { return []; }
  });
  const trackSubmission = (questions: CoachQuestion[]) => {
    if (!questions.length) return;
    setSubmitted(previous => {
      const known = new Map(previous.map(question => [question.id, question]));
      for (const question of questions) known.set(question.id, question);
      return [...known.values()];
    });
  };
  const updateReview = (review: QuestionReview, previousRevision: string) => {
    setEntries(previous => previous.map(row =>
      row.question.id === review.question.id && row.revision === previousRevision ? review : row));
  };
  const removeQuestion = (id: string) => {
    setSubmitted(previous => previous.filter(question => question.id !== id));
    setEntries(previous => previous.flatMap(row => {
      if (row.question.id !== id) return [row];
      return row.published?.available ? [{ ...row, status: 'REMOVAL_PENDING', published: { available: true, current: false } }] : [];
    }));
  };
  useEffect(() => {
    if (!submissionKey) return;
    try {
      if (submitted.length) sessionStorage.setItem(submissionKey, JSON.stringify(submitted));
      else sessionStorage.removeItem(submissionKey);
    } catch { /* Keep the in-memory list if browser storage is unavailable. */ }
  }, [submissionKey, submitted]);
  const latestReviews = useRef<QuestionReview[]>([]);
  const generation = useRef(0);
  const reload = useCallback(() => {
    if (!enabled) return;
    const request = ++generation.current;
    setLoading(true);
    setError('');
    void Promise.all([getModelingQuestions(groupId, true), listAssetProposals(groupId)]).then(([next, proposed]) => {
      if (request !== generation.current) return;
      const byId = new Map(next.map(row => [row.question.id, row.question]));
      const changed = latestReviews.current.filter(({ question }) =>
        question.sql && byId.get(question.id)?.sql && byId.get(question.id)?.sql !== question.sql);
      const changedDefinitions = latestReviews.current.filter(({ question }) =>
        byId.has(question.id) && byId.get(question.id)?.definition !== question.definition);
      latestReviews.current = next;
      if (changed.length) setPreviousSql(previous => ({
        ...previous, ...Object.fromEntries(changed.map(({ question }) => [question.id, question.sql])),
      }));
      if (changedDefinitions.length) setPreviousDefinitions(previous => ({
        ...previous, ...Object.fromEntries(changedDefinitions.map(({ question }) => [question.id, question.definition])),
      }));
      setEntries(next);
      setSubmitted(previous => previous.some(question => byId.has(question.id))
        ? previous.filter(question => !byId.has(question.id)) : previous);
      setProposals(proposed.filter(proposal => !proposal.explicit));
    }).catch(e => {
      if (request === generation.current) setError(e instanceof Error ? e.message : String(e));
    }).finally(() => {
      if (request === generation.current) setLoading(false);
    });
  }, [groupId, enabled]);
  useEffect(() => {
    reload();
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) reload();
    };
    window.addEventListener('modeling:updated', update);
    return () => { generation.current++; window.removeEventListener('modeling:updated', update); };
  }, [groupId, reload]);
  return { reviews, pendingRemovals, proposals, previousSql, previousDefinitions, loading: enabled && loading, error, reload, submitted, trackSubmission, updateReview, removeQuestion };
}
