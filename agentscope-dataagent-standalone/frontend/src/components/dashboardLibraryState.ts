import type { Dashboard } from '../api/dashboards';
import { dashboardStatus } from './dashboardState';

export type DashboardScope = 'all' | 'mine' | 'manage' | 'use';
export type DashboardSort = 'updated' | 'created' | 'name';
export type DashboardView = 'grid' | 'rows';
export function dashboardNameFromKnowledgeBase(name: string): string {
  return name.replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, 80).replace(/[\ud800-\udbff]$/, '');
}
export const dashboardStatuses = [
  ['draft', '草稿'], ['published', '已发布'], ['changed', '有未发布修改'], ['deleting', '清理待重试'],
] as const;

export function selectDashboards(items: Dashboard[], groups: ReadonlyMap<string, { name: string }>, scope: DashboardScope, search: string, status: string, sort: DashboardSort) {
  const term = search.trim().toLocaleLowerCase();
  const time = (value?: string | null) => value && Number.isFinite(Date.parse(value)) ? Date.parse(value) : -Infinity;
  return items.filter(item => (scope === 'all' || (scope === 'mine' ? item.owned : scope === 'manage' ? item.canPublish : !item.canPublish))
    && (status === 'all' || dashboardStatus(item).kind === status)
    && `${item.title} ${item.description || ''} ${groups.get(item.groupId)?.name || ''}`.toLocaleLowerCase().includes(term))
    .sort((a, b) => {
      if (sort === 'name') return a.title.localeCompare(b.title, 'zh-CN') || a.id.localeCompare(b.id);
      const left = time(sort === 'created' ? a.createdAt : a.updatedAt);
      const right = time(sort === 'created' ? b.createdAt : b.updatedAt);
      return (left === right ? 0 : left > right ? -1 : 1) || a.id.localeCompare(b.id);
    });
}

/** The token subject scopes a local display preference only; authorization remains server-side. */
export function dashboardViewKey(token: string | null): string | null {
  try {
    const subject: unknown = JSON.parse(atob((token || '').split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).sub;
    return typeof subject === 'string' && subject ? `dataagent.dashboard-view:${subject}` : null;
  } catch { return null; }
}

export function readDashboardView(key: string | null): DashboardView {
  try { return key && localStorage.getItem(key) === 'rows' ? 'rows' : 'grid'; } catch { return 'grid'; }
}

export function saveDashboardView(key: string | null, view: DashboardView) {
  try { if (key) localStorage.setItem(key, view); } catch { /* Storage may be unavailable in embedded mode. */ }
}
