import React from 'react';
import { PRODUCT_ICONS } from './ProductIconPaths';

export type IconName =
  | 'chat'
  | 'book'
  | 'settings'
  | 'plus'
  | 'database'
  | 'file'
  | 'copy'
  | 'arrowDown'
  | 'arrowUp'
  | 'table'
  | 'graph'
  | 'list'
  | 'search'
  | 'zoomIn'
  | 'zoomOut'
  | 'fit'
  | 'refresh'
  | 'close'
  | 'trash'
  | 'edit'
  | 'compose'
  | 'upload'
  | 'link'
  | 'chevron'
  | 'user'
  | 'send'
  | 'folder'
  | 'code'
  | 'chart'
  | 'warn'
  | 'check'
  | 'back'
  | 'model'
  | 'moreHorizontal'
  | 'stop'
  | 'terms'
  | 'calendar'
  | 'clock'
  | 'type'
  | 'menu'
  | 'info'
  | 'thumbsUp'
  | 'thumbsDown'
  | 'sliders'
  | 'shield'
  | 'circleAlert';

/** Shared 24px glyphs; product icons add matching outline and solid variants. */
const PATHS: Record<IconName, React.ReactNode> = {
  circleAlert: <><circle cx="12" cy="12" r="9" /><path d="M12 6v7M12 17h.01" /></>,
  sliders: <><path d="M3 6h11m4 0h3M3 12h3m4 0h11M3 18h10m4 0h4" /><circle cx="16" cy="6" r="2" /><circle cx="8" cy="12" r="2" /><circle cx="15" cy="18" r="2" /></>,
  shield: <><path d="m12 3 8 4v6c0 4-8 8-8 8s-8-4-8-8V7z" /><rect x="9" y="10" width="6" height="5" rx="1" /><path d="M10 10V8a2 2 0 0 1 4 0v2" /></>,
  terms: PRODUCT_ICONS.terms.outline,
  calendar: PRODUCT_ICONS.calendar.outline,
  arrowUp: <path d="M12 20V4m-6 6 6-6 6 6" />,
  arrowDown: <path d="M12 5v14m-6-6 6 6 6-6" />,
  copy: <><rect x="8" y="8" width="12" height="13" rx="2" /><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3" /></>,
  chat: <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />,
  book: PRODUCT_ICONS.book.outline,
  settings: (
    <>
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h0a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h0a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v0a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </>
  ),
  plus: (
    <>
      <path d="M12 5v14" />
      <path d="M5 12h14" />
    </>
  ),
  database: PRODUCT_ICONS.database.outline,
  file: (
    <>
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
      <path d="M14 2v6h6" />
      <path d="M9 13h6M9 17h6" />
    </>
  ),
  table: PRODUCT_ICONS.table.outline,
  graph: (
    <>
      <circle cx="18" cy="5" r="3" />
      <circle cx="6" cy="12" r="3" />
      <circle cx="18" cy="19" r="3" />
      <path d="M8.6 13.5l6.8 4M15.4 6.5l-6.8 4" />
    </>
  ),
  list: (
    <>
      <path d="M8 6h13M8 12h13M8 18h13" />
      <path d="M3 6h.01M3 12h.01M3 18h.01" />
    </>
  ),
  search: (
    <>
      <circle cx="11" cy="11" r="7" />
      <path d="M21 21l-4.35-4.35" />
    </>
  ),
  zoomIn: (
    <>
      <circle cx="11" cy="11" r="7" />
      <path d="M21 21l-4.35-4.35M11 8v6M8 11h6" />
    </>
  ),
  zoomOut: (
    <>
      <circle cx="11" cy="11" r="7" />
      <path d="M21 21l-4.35-4.35M8 11h6" />
    </>
  ),
  fit: (
    <>
      <path d="M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M3 16v3a2 2 0 0 0 2 2h3M16 21h3a2 2 0 0 0 2-2v-3" />
    </>
  ),
  refresh: (
    <>
      <path d="M21 12a9 9 0 1 1-2.64-6.36" />
      <path d="M21 3v6h-6" />
    </>
  ),
  close: <path d="M18 6L6 18M6 6l12 12" />,
  trash: (
    <>
      <path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
      <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
    </>
  ),
  edit: (
    <>
      <path d="M17 3a2.83 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5z" />
    </>
  ),
  compose: PRODUCT_ICONS.compose.outline,
  upload: (
    <>
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
      <path d="M17 8l-5-5-5 5M12 3v12" />
    </>
  ),
  link: (
    <>
      <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
      <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
    </>
  ),
  chevron: <path d="M9 18l6-6-6-6" />,
  user: (
    <>
      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
      <circle cx="12" cy="7" r="4" />
    </>
  ),
  send: <path d="M22 2L11 13M22 2l-7 20-4-9-9-4z" />,
  folder: <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />,
  code: <path d="M16 18l6-6-6-6M8 6l-6 6 6 6" />,
  chart: (
    <>
      <path d="M3 3v18h18" />
      <path d="M7 15v3M12 10v8M17 6v12" />
    </>
  ),
  warn: (
    <>
      <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
      <path d="M12 9v4M12 17h.01" />
    </>
  ),
  check: <path d="M20 6L9 17l-5-5" />,
  back: (
    <>
      <path d="M19 12H5" />
      <path d="M12 19l-7-7 7-7" />
    </>
  ),
  model: PRODUCT_ICONS.model.outline,
  moreHorizontal: PRODUCT_ICONS.moreHorizontal.outline,
  stop: <rect x="6" y="6" width="12" height="12" rx="2" />,
  type: <path d="M5 4h14M12 4v16M8 20h8" />,
  menu: <path d="M4 6h16M4 12h16M4 18h16" />,
  info: <><circle cx="12" cy="12" r="9" /><path d="M12 11v6M12 7h.01" /></>,
  thumbsUp: <><path d="M7 10v11H3V10h4ZM7 10l5-7a2 2 0 0 1 2 2v5h5a2 2 0 0 1 2 2l-1 7a2 2 0 0 1-2 2H7" /></>,
  thumbsDown: <><path d="M7 14V3H3v11h4ZM7 14l5 7a2 2 0 0 0 2-2v-5h5a2 2 0 0 0 2-2l-1-7a2 2 0 0 0-2-2H7" /></>,
  clock: (
    <>
      <circle cx="12" cy="12" r="10" />
      <path d="M12 6v6l4 2" />
    </>
  ),
};

export default function Icon({
  name,
  size = 'md',
  className = '',
  variant = 'outline',
}: {
  name: IconName;
  size?: 'sm' | 'md' | 'lg';
  className?: string;
  variant?: 'outline' | 'solid';
}) {
  const product = PRODUCT_ICONS[name as keyof typeof PRODUCT_ICONS];
  const glyphClass = product ? ` da-icon-product${variant === 'solid' ? ' da-icon-solid' : ''}` : '';
  const sz = size === 'sm' ? ' da-icon-sm' : size === 'lg' ? ' da-icon-lg' : '';
  return (
    <svg viewBox="0 0 24 24" className={`da-icon${sz}${glyphClass} ${className}`} aria-hidden="true" focusable="false">
      {product?.[variant] ?? PATHS[name]}
    </svg>
  );
}
