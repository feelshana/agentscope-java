import React from 'react';
import { useNavigate } from 'react-router-dom';
import Icon from './Icon';

export interface BackToChatHeaderProps {
  title: string;
  subtitle?: string;
  backTo?: string;
  backLabel?: string;
}

/** One consistent way back from a secondary page, without a second back row. */
export default function BackToChatHeader({
  title,
  subtitle,
  backTo = '/chat',
  backLabel = '返回对话',
}: BackToChatHeaderProps) {
  const navigate = useNavigate();
  return (
    <header style={S.root}>
      <button type="button" className="da-header-back" onClick={() => navigate(backTo)} aria-label={backLabel}>
        <Icon name="back" size="sm" />
        <span>{backLabel}</span>
      </button>
      <span className="da-header-divider" aria-hidden="true" />
      <div style={S.titleBlock}>
        <span style={S.title} title={title}>{title}</span>
        {subtitle && <span style={S.subtitle} title={subtitle}>{subtitle}</span>}
      </div>
    </header>
  );
}

const S: Record<string, React.CSSProperties> = {
  root: {
    display: 'flex', alignItems: 'center', gap: 12,
    height: 'var(--da-shell-header-height)', boxSizing: 'border-box',
    padding: '0 24px', borderBottom: '1px solid var(--da-border)',
    background: 'var(--da-surface)', flexShrink: 0, minWidth: 0,
  },
  titleBlock: { display: 'flex', flexDirection: 'column', minWidth: 0 },
  title: {
    fontSize: '1rem', fontWeight: 650, color: 'var(--da-text)',
    letterSpacing: '-0.01em', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  },
  subtitle: {
    fontSize: '0.75rem', color: 'var(--da-text-3)',
    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  },
};
