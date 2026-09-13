import React from 'react';

export type EmptyVariant = 'table' | 'doc' | 'graph';

const CAPTION: Record<EmptyVariant, string> = {
  table: '表格/文本随意传，数据知识一起用',
  doc: '上传关系说明文档，让 agent 理解你的业务口径',
  graph: '暂无图谱数据，可点击「重建图谱」构建',
};

/**
 * TC-style illustrated empty state: a soft gradient table/document/graph motif drawn as inline SVG
 * (no external assets) plus a one-line caption, so empty surfaces read designed rather than blank.
 */
export default function EmptyIllustration({
  variant = 'table',
  caption,
}: {
  variant?: EmptyVariant;
  caption?: string;
}) {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 12,
        padding: '48px 24px',
        color: 'var(--da-text-3)',
      }}
    >
      <svg width="220" height="140" viewBox="0 0 220 140" fill="none" aria-hidden="true">
        <defs>
          <linearGradient id="da-empty-g" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="#8CA6FF" />
            <stop offset="1" stopColor="#5B8CFF" />
          </linearGradient>
          <linearGradient id="da-empty-g2" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="#C7D6FF" />
            <stop offset="1" stopColor="#9DB9FF" />
          </linearGradient>
        </defs>
        {variant !== 'graph' && (
          <g>
            <rect x="30" y="26" width="104" height="86" rx="8" fill="url(#da-empty-g2)" opacity="0.5" />
            <rect x="38" y="34" width="88" height="14" rx="4" fill="url(#da-empty-g)" opacity="0.85" />
            <rect x="38" y="54" width="88" height="8" rx="3" fill="#ffffff" opacity="0.9" />
            <rect x="38" y="68" width="70" height="8" rx="3" fill="#ffffff" opacity="0.75" />
            <rect x="38" y="82" width="80" height="8" rx="3" fill="#ffffff" opacity="0.6" />
            <rect x="38" y="96" width="56" height="8" rx="3" fill="#ffffff" opacity="0.5" />
          </g>
        )}
        {variant !== 'table' && (
          <g transform={variant === 'graph' ? 'translate(58 8)' : 'translate(96 18)'}>
            <path d="M8 0h44l16 16v72a8 8 0 0 1-8 8H8a8 8 0 0 1-8-8V8a8 8 0 0 1 8-8Z" fill="#ffffff" stroke="url(#da-empty-g)" strokeWidth="2" />
            <path d="M52 0l16 16H52V0Z" fill="url(#da-empty-g2)" />
            <rect x="10" y="30" width="44" height="6" rx="3" fill="url(#da-empty-g2)" />
            <rect x="10" y="44" width="36" height="6" rx="3" fill="url(#da-empty-g2)" opacity="0.8" />
            <rect x="10" y="58" width="42" height="6" rx="3" fill="url(#da-empty-g2)" opacity="0.6" />
          </g>
        )}
        {variant === 'graph' && (
          <g stroke="url(#da-empty-g)" strokeWidth="1.5" opacity="0.8">
            <line x1="70" y1="60" x2="110" y2="40" />
            <line x1="110" y1="40" x2="150" y2="66" />
            <line x1="70" y1="60" x2="104" y2="96" />
            <line x1="150" y1="66" x2="120" y2="102" />
          </g>
        )}
        {variant === 'graph' && (
          <g fill="#ffffff" stroke="url(#da-empty-g)" strokeWidth="2">
            <circle cx="70" cy="60" r="13" />
            <circle cx="110" cy="40" r="10" />
            <circle cx="150" cy="66" r="12" />
            <circle cx="104" cy="96" r="9" />
            <circle cx="120" cy="102" r="7" />
          </g>
        )}
        <path d="M176 34l4 9 9 4-9 4-4 9-4-9-9-4 9-4 4-9Z" fill="url(#da-empty-g)" opacity="0.9" />
        <path d="M40 118l3 6 6 3-6 3-3 6-3-6-6-3 6-3 3-6Z" fill="url(#da-empty-g2)" />
      </svg>
      <div style={{ fontSize: 13, lineHeight: '20px' }}>{caption ?? CAPTION[variant]}</div>
    </div>
  );
}
