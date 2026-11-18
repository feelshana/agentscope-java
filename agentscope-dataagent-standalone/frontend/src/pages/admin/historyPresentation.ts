import type { AdminHistorySession, HistoryQuery } from '../../api/adminHistory';

export const historyTypes: { value: HistoryQuery['assistantType']; label: string }[] = [
  { value: 'query', label: '问数' }, { value: 'modeling', label: '建模' },
  { value: 'dashboard', label: '仪表盘' }, { value: 'all', label: '全部' },
];

export function historyAssistantLabel(type: AdminHistorySession['assistantType'] | 'all'): string {
  return historyTypes.find(item => item.value === type)?.label ?? '其他助手';
}

export function historyCount(type: HistoryQuery['assistantType']) {
  if (type === 'query') return { label: '提问数', totalUnit: '次提问', cellUnit: '问', search: '搜索会话标题或提问内容' };
  if (type === 'all') return { label: '用户消息数', totalUnit: '条用户消息', cellUnit: '条', search: '搜索会话标题或用户消息' };
  return { label: '对话轮次', totalUnit: '轮对话', cellUnit: '轮', search: '搜索会话标题或对话内容' };
}

export function collapseHistoryMessage(text: string): boolean {
  return text.length > 1000 || text.split('\n').length > 18;
}
