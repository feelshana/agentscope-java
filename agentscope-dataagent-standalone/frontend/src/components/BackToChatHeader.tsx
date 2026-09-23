import React from 'react';

export interface BackToChatHeaderProps {
  title: string;
  subtitle?: string;
  /** When set, the title becomes a clickable back-link. */
  onTitleClick?: () => void;
}

export default function BackToChatHeader({ title, subtitle, onTitleClick }: BackToChatHeaderProps) {
  return (
    <div style={S.root}>
      <div style={S.titleBlock} className="da-keyline">
        {onTitleClick ? (
          <button
            onClick={onTitleClick}
            style={S.backTitle}
            title="返回列表"
            onMouseEnter={e => {
              e.currentTarget.style.color = 'var(--da-primary)';
              const arrow = e.currentTarget.querySelector('span');
              if (arrow) (arrow as HTMLElement).style.color = 'var(--da-primary)';
            }}
            onMouseLeave={e => {
              e.currentTarget.style.color = 'var(--da-text)';
              const arrow = e.currentTarget.querySelector('span');
              if (arrow) (arrow as HTMLElement).style.color = 'var(--da-text-3)';
            }}
          >
            <span style={S.arrow}>‹</span>
            {title}
          </button>
        ) : (
          <span style={S.title}>{title}</span>
        )}
        {subtitle && <span style={S.subtitle}>{subtitle}</span>}
      </div>
    </div>
  );
}

const S: Record<string, React.CSSProperties> = {
  root: {
    display: 'flex', alignItems: 'center',
    padding: '14px 24px', borderBottom: '1px solid var(--da-border)',
    background: 'var(--da-surface)', flexShrink: 0,
  },
  titleBlock: { display: 'flex', flexDirection: 'column', minWidth: 0, paddingLeft: 14 },
  title: { fontSize: '1.2rem', fontWeight: 700, color: 'var(--da-text)', letterSpacing: '-0.01em' },
  backTitle: {
    display: 'inline-flex', alignItems: 'center', gap: 6,
    fontSize: '1.1rem', fontWeight: 600, color: 'var(--da-text)',
    letterSpacing: '-0.01em', background: 'none', border: 'none',
    cursor: 'pointer', padding: '4px 0', textAlign: 'left',
    transition: 'color 0.15s',
  },
  arrow: {
    fontSize: '1.4rem', fontWeight: 400, lineHeight: 1,
    color: 'var(--da-text-3)', transition: 'color 0.15s',
  },
  subtitle: {
    fontSize: '0.78rem', color: 'var(--da-text-3)',
    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  },
};
