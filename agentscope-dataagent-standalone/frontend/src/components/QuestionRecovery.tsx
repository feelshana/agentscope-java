import React from 'react';
import Icon from './Icon';

export default function QuestionRecovery({ question, draft, disabled, onEdit }: {
  question: string; draft: string; disabled: boolean; onEdit: (question: string) => void;
}) {
  if (!question.trim()) return null;
  return <div className="da-query-recovery">
    <button type="button" className="da-btn da-btn-sm" disabled={disabled} onClick={() => {
      if (draft.trim() && draft !== question && !window.confirm('输入框已有未发送内容，要替换为这次的问题吗？')) return;
      onEdit(question);
    }}><Icon name="edit" size="sm" />修改问题再问</button>
    <span>仅填入输入框；请核对当前知识库与条件后发送。</span>
  </div>;
}
