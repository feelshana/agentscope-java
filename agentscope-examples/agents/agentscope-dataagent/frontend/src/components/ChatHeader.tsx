import React from 'react';
import { useSearchParams } from 'react-router-dom';
import Icon from './Icon';

export interface ChatHeaderProps {
  /** Current conversation title (first user question); empty for a new conversation. */
  title: string;
}

/**
 * Conversation header: shows the current conversation title (blank "新对话" until the first
 * question), plus a subtle session tag. Brand and global actions live in the sidebar instead.
 */
export default function ChatHeader({ title }: ChatHeaderProps) {
  const [searchParams] = useSearchParams();
  const sessionKey = searchParams.get('session');

  return (
    <div style={S.root}>
      <div style={{ ...S.titleBlock, paddingLeft: 10 }} className="da-keyline">
        <span className="da-eyebrow">Conversation</span>
        <span style={S.title}>{title || '新对话'}</span>
      </div>
      {sessionKey && (
        <span style={S.sessionTag} title={sessionKey}>
          <Icon name="link" size="sm" /> session: {sessionKey.slice(0, 8)}…
        </span>
      )}
    </div>
  );
}

const S: Record<string, React.CSSProperties> = {
  root: {
    display: 'flex',
    alignItems: 'center',
    gap: 12,
    padding: '12px 24px',
    borderBottom: '1px solid var(--da-border)',
    background: 'var(--da-surface)',
    flexShrink: 0,
  },
  titleBlock: { display: 'flex', flexDirection: 'column', minWidth: 0, flex: 1 },
  title: {
    fontSize: 16,
    fontWeight: 600,
    lineHeight: '24px',
    color: 'var(--da-text)',
    letterSpacing: '-0.01em',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  sessionTag: {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 4,
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: 11,
    color: 'var(--da-text-3)',
    background: 'var(--da-surface-sunken)',
    border: '1px solid var(--da-border)',
    padding: '3px 8px',
    borderRadius: 999,
    flexShrink: 0,
  },
};
