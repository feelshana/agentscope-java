import type { KnowledgeScope } from '../api/chat';
import Icon from './Icon';

/** Historical selection, never inferred from the conversation's latest picker value. */
export default function KnowledgeScopeLabel({ scope, showUnknown = false }: { scope?: KnowledgeScope | null; showUnknown?: boolean }) {
  if (!scope) return showUnknown ? <div className="da-turn-scope"><Icon name="info" size="sm" />知识库未记录</div> : null;
  const names = scope.groupIds.map(id => scope.groupNames[id] || '指定知识库');
  return <div className="da-turn-scope" title={names.join('、')}><Icon name={names.length ? 'book' : 'chat'} size="sm" />
    {scope.allGroups ? `全部可用 · ${names.length} 个知识库` : names.length > 1 ? names.join('、') : names[0] || '普通聊天'}
  </div>;
}
