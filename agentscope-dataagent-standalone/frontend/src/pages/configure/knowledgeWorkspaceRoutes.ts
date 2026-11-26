import type { MutableRefObject } from 'react';
import type { KnowledgeReleaseData } from '../../components/modeling/useKnowledgeReleaseData';
import type { KnowledgeReleaseDecision } from '../../components/modeling/releaseDecision';
import type { QueryCoachData } from '../../components/modeling/useQueryCoachData';

export type ModelingSection = 'graph' | 'rules' | 'coach' | 'calibers' | 'assets' | 'materials' | 'engineering';
export type KnowledgeSection = 'overview' | 'datasets' | 'members' | 'settings' | ModelingSection;

export const knowledgePath = (groupId: string) => `/configure/datasets/${encodeURIComponent(groupId)}`;
export const datasetPath = (groupId: string, datasetId: string) => `${knowledgePath(groupId)}/table/${encodeURIComponent(datasetId)}`;
export const modelingPath = (groupId: string) => `${knowledgePath(groupId)}/modeling`;
export const modelingReviewPath = (groupId: string) => modelingPath(groupId) + '?asset=semantic&view=graph&review=1&mode=board';
export const modelingPreparationPath = (groupId: string) => modelingPath(groupId) + '?asset=modeling&start=1';

export function modelingSection(params: URLSearchParams): ModelingSection {
  const asset = params.get('asset');
  if (asset === 'coach' || asset === 'qa' || asset === 'sql') return 'coach';
  // spec149: caliber lists, only in caliber knowledge bases.
  if (asset === 'calibers') return 'calibers';
  if (asset === 'assets' && !params.get('kind')) return 'assets';
  if (asset === 'overview') return 'materials';
  if (asset === 'engineering' || asset === 'views' || asset === 'cubes' || asset === 'mdl') return 'engineering';
  const view = params.get('view');
  return view === 'rules' || (view !== 'graph' && (asset === 'knowledge' || asset === 'glossary')) ? 'rules' : 'graph';
}

export function modelingParams(section: ModelingSection): Record<string, string> {
  if (section === 'graph' || section === 'rules') return { asset: 'semantic', view: section, mode: 'board' };
  return { asset: section === 'materials' ? 'overview' : section, mode: 'board' };
}

export type KnowledgeModelingHost = {
  versionTarget: HTMLElement | null;
  actionsTarget: HTMLElement | null;
  leave: MutableRefObject<(() => boolean | Promise<boolean>) | null>;
  select: MutableRefObject<((section: ModelingSection) => void) | null>;
  release: KnowledgeReleaseData;
  decision: KnowledgeReleaseDecision;
  coach: QueryCoachData;
  importing: boolean;
  onActivity: (activity: ModelingHeaderActivity | null) => void;
};

export type ModelingHeaderActivity = {
  setup: boolean;
  assistant: 'idle' | 'running' | 'awaiting';
  publishing: boolean;
};
