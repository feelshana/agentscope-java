import React from 'react';
import { useNavigate } from 'react-router-dom';

export interface BackToChatHeaderProps {
  title: string;
  subtitle?: string;
}

export default function BackToChatHeader({ title, subtitle }: BackToChatHeaderProps) {
  const navigate = useNavigate();
  return (
    <div style={S.root}>
      <button onClick={() => navigate('/chat')} className="da-btn" title="Return to chat">
        ← 返回 Chat
      </button>
      <div style={{ ...S.titleBlock, paddingLeft: 10 }} className="da-keyline">
        <span className="da-eyebrow">Workspace</span>
        <span style={S.title}>{title}</span>
        {subtitle && <span style={S.subtitle}>{subtitle}</span>}
      </div>
    </div>
  );
}

const S: Record<string, React.CSSProperties> = {
  root: {
    display: 'flex', alignItems: 'center', gap: 16,
    padding: '12px 24px', borderBottom: '1px solid var(--da-border)',
    background: 'var(--da-surface)', flexShrink: 0,
  },
  titleBlock: { display: 'flex', flexDirection: 'column', minWidth: 0 },
  title: { fontSize: '1.15rem', fontWeight: 600, color: 'var(--da-text)', letterSpacing: '-0.01em' },
  subtitle: {
    fontSize: '0.78rem', color: 'var(--da-text-3)',
    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  },
};
