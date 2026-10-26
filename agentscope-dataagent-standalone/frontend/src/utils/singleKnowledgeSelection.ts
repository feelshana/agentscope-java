/** Old multi-library scopes need an explicit new choice, never a silent first item. */
export function singleKnowledgeSelection(ids: string[] | undefined, allGroups = false) {
  const unique = [...new Set((ids ?? []).filter(Boolean))];
  const needsSelection = allGroups || unique.length > 1;
  return { groupIds: needsSelection ? [] : unique, needsSelection };
}

export const SINGLE_KNOWLEDGE_NOTICE = '此会话原先选择了多个知识库，请重新选择一个，或明确清空选择进行普通聊天。';
