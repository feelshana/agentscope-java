import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getDraftReview, listConfirmations } from '../../api/draftReview';
import type { Confirmation, DraftReview } from '../../api/draftReview';
import { getScreeningStatus } from '../../api/fieldScreening';
import type { ScreeningStatus } from '../../api/fieldScreening';
import { getModelingWorkflow } from '../../api/modelingWorkflow';
import type { ModelingWorkflow } from '../../api/modelingWorkflow';
import { getAssetCatalog } from '../../api/semanticAssets';
import { getCalibers } from '../../api/calibers';
import type { CaliberTable } from '../../api/calibers';
import type { MdlIssue } from '../../api/semanticModeling';
import type { AssetReviewTarget } from './releaseDecision';

type ReleaseData = {
  workflow: ModelingWorkflow | null;
  review: DraftReview | null;
  screening: ScreeningStatus[] | null;
  confirmations: Confirmation[] | null;
  assetTasks: AssetReviewTarget[];
  incompleteViews: string[];
  /** spec153: caliber lists of a caliber knowledge base; null elsewhere. */
  calibers: CaliberTable[] | null;
  publishedViews?: number;
  loading: boolean;
  error: string | null;
};

export function useKnowledgeReleaseData(groupId: string, enabled: boolean, hasWorkspace: boolean, caliberKb = false) {
  const [data, setData] = useState<ReleaseData>({
    workflow: null, review: null, screening: null, confirmations: null,
    assetTasks: [], incompleteViews: [], calibers: null, loading: true, error: null,
  });
  const sequence = useRef(0);
  const [failedPublish, setFailedPublish] = useState<{ revision: string; issue: MdlIssue }>();
  const revision = useMemo(() => JSON.stringify([data.workflow?.publishedVersion, data.workflow?.engineeringCheckedAt, data.review?.changes]),
    [data.workflow?.publishedVersion, data.workflow?.engineeringCheckedAt, data.review?.changes]);
  const recordPublicationFailure = (issue?: MdlIssue) => setFailedPublish(issue ? { revision, issue } : undefined);
  const reload = useCallback(async () => {
    if (!enabled) return;
    const request = ++sequence.current;
    setData(previous => ({ ...previous, loading: true, error: null }));
    const [workflow, review, screening, confirmations, catalog, calibers] = await Promise.allSettled([
      getModelingWorkflow(groupId),
      hasWorkspace ? getDraftReview(groupId) : null,
      hasWorkspace ? getScreeningStatus(groupId) : null,
      hasWorkspace ? listConfirmations(groupId) : null,
      hasWorkspace ? getAssetCatalog(groupId) : null,
      hasWorkspace && caliberKb ? getCalibers(groupId) : null,
    ]);
    if (sequence.current !== request) return;
    const error = [workflow, review, screening, confirmations, catalog, calibers].find(item => item.status === 'rejected');
    setData(previous => ({
      workflow: workflow.status === 'fulfilled' ? workflow.value : previous.workflow,
      review: review.status === 'fulfilled' ? review.value : previous.review,
      screening: screening.status === 'fulfilled' ? screening.value : previous.screening,
      confirmations: confirmations.status === 'fulfilled' ? confirmations.value : previous.confirmations,
      assetTasks: catalog.status === 'fulfilled' ? (catalog.value?.draft ?? []).flatMap<AssetReviewTarget>(asset =>
        asset.status === 'REVIEW_PENDING' || asset.status === 'VERIFY_PENDING'
          ? [{ kind: asset.kind, name: asset.name, step: asset.status }] : []) : previous.assetTasks,
      incompleteViews: catalog.status === 'fulfilled' ? [...new Set(catalog.value?.incompleteViews ?? [])] : previous.incompleteViews,
      calibers: calibers.status === 'fulfilled' ? calibers.value : previous.calibers,
      publishedViews: catalog.status === 'fulfilled' ? catalog.value?.published.length : previous.publishedViews,
      loading: false,
      error: error ? (error.reason instanceof Error ? error.reason.message : '无法读取知识库状态') : null,
    }));
  }, [enabled, groupId, hasWorkspace, caliberKb]);
  useEffect(() => {
    void reload();
    const updated = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) void reload();
    };
    window.addEventListener('modeling:updated', updated);
    return () => { sequence.current++; window.removeEventListener('modeling:updated', updated); };
  }, [groupId, reload]);

  const setScreening = (screening: ScreeningStatus[]) => {
    setData(previous => ({ ...previous, screening }));
  };
  return {
    ...data, reload, setScreening, recordPublicationFailure,
    publicationFailure: failedPublish?.revision === revision ? failedPublish.issue : undefined,
  };
}

export type KnowledgeReleaseData = ReturnType<typeof useKnowledgeReleaseData>;
