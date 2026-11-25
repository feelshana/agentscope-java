import type { DraftChange } from '../../api/draftReview';
import type { ModelingWorkflow } from '../../api/modelingWorkflow';
import type { MdlIssue } from '../../api/semanticModeling';
import { draftChangeGroups } from './draftChangeGroups';

export type AssetReviewTarget = NonNullable<MdlIssue['assetReview']>;
export type ReviewSection = 'changes' | 'tasks' | 'checks';
export type KnowledgeReleaseDecision = ReturnType<typeof releaseDecision>;

function releaseBlocker(workflow: ModelingWorkflow | null, assets: readonly AssetReviewTarget[], incompleteViews: readonly string[], publicationFailure?: MdlIssue) {
  if (incompleteViews.length) return { kind: 'incomplete' as const, name: incompleteViews[0] };
  if (assets.length) return { kind: 'asset' as const, target: assets[0] };
  if (publicationFailure?.incompleteView) return { kind: 'incomplete' as const, name: publicationFailure.incompleteView };
  if (publicationFailure?.assetReview) return { kind: 'asset' as const, target: publicationFailure.assetReview };
  const blocker = workflow?.blockers.find(item => item.code !== 'ENGINEERING_REQUIRED');
  if (blocker?.code === 'DATA_REQUIRED') return { kind: 'data' as const, message: blocker.message };
  if (blocker?.code === 'MODEL_REQUIRED') return { kind: 'model' as const, message: blocker.message };
  if (blocker || publicationFailure || workflow?.engineeringStatus === 'FAILED') {
    return { kind: 'engineering' as const, message: publicationFailure?.message ?? blocker?.message ?? '模型结构校验未通过，请修正后重新校验' };
  }
  return null;
}

export function releaseDecision({
  workflow, files, assetTasks, incompleteViews, confirmations, questionIds, removalIds, publicationFailure,
}: {
  workflow: ModelingWorkflow | null;
  files: readonly DraftChange[];
  assetTasks: readonly AssetReviewTarget[];
  incompleteViews: readonly string[];
  confirmations: number;
  questionIds: readonly string[];
  removalIds: readonly string[];
  publicationFailure?: MdlIssue;
}) {
  const excluded = new Set(questionIds.map(id => `knowledge/sql/${id}.md`));
  const removed = new Set(files.filter(file => file.kind === 'REMOVED').map(file => file.path));
  const removals = removalIds.filter(id => removed.has(`knowledge/sql/${id}.md`));
  for (const id of removals) excluded.add(`knowledge/questions/${id}.yml`);
  const changes = files.filter(file => !excluded.has(file.path));
  return {
    changes,
    objectCount: draftChangeGroups(changes).length,
    taskCount: confirmations + questionIds.length + assetTasks.length + incompleteViews.length,
    republish: files.length === 0 && !!workflow && workflow.publishedVersion > 0 && !workflow.queryAvailable,
    blocker: releaseBlocker(workflow, assetTasks, incompleteViews, publicationFailure),
    removals,
  };
}
