import React from 'react';

export interface ChatHeaderProps {
  /** Current conversation title (first user question); empty for a new conversation. */
  title: string;
}

/**
 * Conversation header: shows the current conversation title (blank "新对话" until the first
 * question). Session IDs stay in the URL for routing and are not shown as page content.
 */
export default function ChatHeader({ title }: ChatHeaderProps) {
  return (
    <div style={S.root}>
      <div style={{ ...S.titleBlock, paddingLeft: 10 }} className="da-keyline">
        <span className="da-eyebrow">对话</span>
        <span style={S.title}>{title || '新对话'}</span>
      </div>
    </div>
  );
}

const S: Record<string, React.CSSProperties> = {
  root: {
    display: 'flex',
    alignItems: 'center',
    gap: 12,
    height: 'var(--da-shell-header-height)',
    boxSizing: 'border-box',
    padding: '0 24px',
    borderBottom: '1px solid var(--da-border)',
    background: 'rgba(255, 255, 255, 0.82)',
    backdropFilter: 'blur(8px)',
    WebkitBackdropFilter: 'blur(8px)',
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
};
