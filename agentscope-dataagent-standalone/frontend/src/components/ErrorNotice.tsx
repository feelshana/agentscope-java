import React from 'react';
import { friendlyError, type ErrorContext } from '../utils/friendlyError';
import './ConversationPolish.css';

export default function ErrorNotice({ error, context = 'chat', secrets, children }: {
  error: string; context?: ErrorContext; secrets?: string[]; children?: React.ReactNode;
}) {
  const message = friendlyError(error, context, secrets);
  return <div className="da-error-notice" role="alert">
    <strong>{message.summary}</strong>
    {message.action && <p>{message.action}</p>}
    {children && <div className="da-error-actions">{children}</div>}
    {message.detail && <details><summary>技术详情</summary><pre>{message.detail}</pre></details>}
  </div>;
}
