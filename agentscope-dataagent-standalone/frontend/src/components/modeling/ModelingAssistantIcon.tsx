import type { ReactNode } from 'react';
import './ModelingAssistantIcon.css';

/** Shared decorative identity for the modeling assistant, including its live indicator. */
export default function ModelingAssistantIcon({ message = false, className = '', children }: { message?: boolean; className?: string; children?: ReactNode }) {
  return <span className={`modeling-ai-icon${message ? ' modeling-ai-icon-message' : ''}${className ? ` ${className}` : ''}`} aria-hidden="true">AI{children}</span>;
}
