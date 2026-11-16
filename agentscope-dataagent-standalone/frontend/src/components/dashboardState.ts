import type { TurnEntry } from '../api/sessions';
import type { Dashboard } from '../api/dashboards';

export function dashboardStatus(dashboard: Pick<Dashboard, 'revision' | 'publishedRevision' | 'canEdit'> & { deletionPending?: boolean }) {
  if (dashboard.deletionPending) return { kind: 'deleting', label: '清理待重试' };
  if (!dashboard.publishedRevision) return { kind: 'draft', label: '草稿' };
  return dashboard.canEdit && dashboard.revision !== dashboard.publishedRevision
    ? { kind: 'changed', label: '有未发布修改' } : { kind: 'published', label: '已发布' };
}

export function dashboardPublishHint(dashboard: Dashboard, busy: boolean): string {
  if (dashboard.deletionPending) return '请先完成看板清理';
  if (!dashboard.canPublish) return '需要知识库管理权限才能发布';
  if (busy) return '请等待本轮生成完成';
  if (!dashboard.html) return '先生成看板，再进行发布';
  if (!dashboard.verified) return '请让助手完成构建检查后再发布';
  if (dashboard.revision === dashboard.publishedRevision) return '与已发布版一致，没有需要发布的修改';
  return '';
}

/** Preserve the original answer while folding implementation details out of the main conversation. */
export function dashboardReplyParts(text: string): { main: string; details: string } {
  const section = /^(?:#{1,4}\s*)?(?:\*\*)?(?:实现说明|技术说明|技术细节|构建说明|数据与生成说明)(?:\*\*)?\s*[:：]?\s*$/m.exec(text);
  if (section && section.index > 0) return { main: text.slice(0, section.index).trim(), details: text.slice(section.index).trim() };
  const paragraph = /\n\s*\n/.exec(text);
  if (text.length > 600 && paragraph && /`|\bSQL\b|formatter|dataagent\.|代码|\bCSS\b|JavaScript|\bHTML\b/i.test(text)) {
    return { main: text.slice(0, paragraph.index).trim(), details: text.slice(paragraph.index).trim() };
  }
  return { main: text, details: '' };
}

export type DashboardMessage = { id: string; role: 'user' | 'assistant'; text: string };

/** Tool completion is not the same as successful page generation. */
export function dashboardToolProgress(type: string, tool?: string, result?: string): string | null {
  if (type === 'tool_call') return tool === 'wren_genbi' ? '正在构建或检查看板…'
    : ['write_file', 'edit_file'].includes(tool || '') ? '正在写入页面…' : '正在分析数据与指标…';
  if (type !== 'tool_result') return null;
  if (/^\s*"?Error:|参数校验失败|执行失败/i.test(result || '')) return '本次工具执行未成功，助手正在处理…';
  return ['write_file', 'edit_file'].includes(tool || '') ? '页面已写入，正在继续检查…' : '正在继续处理…';
}

export function dashboardErrorText(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error || '操作失败，请重试');
  return /ServiceUnavailable|status\s*503|服务暂时不可用/i.test(message)
    ? '模型服务暂时不可用，本轮尚未完成，请稍后重试。' : message;
}

/** Tool reasoning stays in generation status; keep the last answer of each persisted turn. */
export function dashboardMessages(history: TurnEntry[]): DashboardMessage[] {
  const result: DashboardMessage[] = [];
  let answer = -1;
  for (const turn of history) {
    if (turn.role.toUpperCase() === 'USER') {
      result.push({ id: turn.id, role: 'user', text: turn.content || '' }); answer = -1;
    } else if (turn.role.toUpperCase() === 'ASSISTANT' && turn.content?.trim()) {
      const message: DashboardMessage = { id: turn.id, role: 'assistant', text: dashboardErrorText(turn.content) };
      if (answer < 0) { answer = result.length; result.push(message); }
      else result[answer] = message;
    }
  }
  return result;
}

export interface DashboardBridgeMessage { kind: 'host-ready' | 'runtime-error' | 'query' | 'thumbnail'; channel?: string; id?: number; sql?: string; error?: string; image?: string }

export function dashboardThumbnailValid(image: unknown): image is string {
  return typeof image === 'string' && image.length < 450000 && /^data:image\/png;base64,[A-Za-z0-9+/]+=*$/.test(image);
}

/** Only messages from this preview's exact window and channel may reach authenticated APIs. */
export function dashboardBridgeMessage(event: Pick<MessageEvent, 'source' | 'data'>,
  source: MessageEventSource | null | undefined, channel: string): DashboardBridgeMessage | null {
  if (!source || event.source !== source || !event.data || typeof event.data !== 'object') return null;
  const data = event.data as Record<string, unknown>;
  if (data.kind === 'host-ready') return { kind: 'host-ready' };
  if (data.channel !== channel) return null;
  if (data.kind === 'thumbnail') return dashboardThumbnailValid(data.image) ? { kind: 'thumbnail', image: data.image } : null;
  if (data.kind === 'runtime-error') return { kind: 'runtime-error', error: String(data.error || '页面运行错误').slice(0, 300) };
  if (data.kind !== 'query' || !Number.isSafeInteger(data.id) || (data.id as number) < 1
    || typeof data.sql !== 'string' || !data.sql.trim() || data.sql.length > 20000) return null;
  return { kind: 'query', id: data.id as number, sql: data.sql };
}
