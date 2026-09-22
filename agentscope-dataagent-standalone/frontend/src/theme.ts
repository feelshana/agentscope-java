/**
 * Design tokens for the DataAgent web UI — Modern Minimalist / Clean Enterprise
 * (Linear / Stripe / Vercel register). Single source of truth for colour, radius,
 * shadow, spacing and type so every surface shares one visual language.
 *
 * Most component chrome lives in CSS classes in `global.css` (which mirrors these
 * tokens as CSS variables); this module exports the same values for the few places
 * that still need inline styles (chart/graph containers, dynamic layouts).
 */
export const T = {
  color: {
    appBg: '#f6f7f9',
    surface: '#ffffff',
    surfaceSunken: '#f1f3f6',
    border: '#dfe3e8',
    borderStrong: '#c4c9d2',
    text: '#0f1b2d',
    textSecondary: '#3d4e63',
    textTertiary: '#6b7689',
    textMuted: '#9aa4b2',
    primary: '#4f46e5',
    primaryHover: '#4338ca',
    primarySubtle: '#eef2ff',
    success: '#16a34a',
    warn: '#d97706',
    danger: '#e11d48',
  },
  radius: { sm: 4, md: 6, lg: 10, xl: 14, pill: 999 },
  shadow: {
    card: '0 1px 2px rgba(0, 0, 0, 0.03)',
    pop: '0 6px 20px rgba(0, 0, 0, 0.08)',
  },
  space: { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 },
  type: {
    base: 14,
    baseLh: 1.6,
    title: 20,
    titleLh: 28,
    section: 13,
    sectionLh: 20,
    small: 12,
    smallLh: 18,
  },
  transition:
    'background-color .15s ease, border-color .15s ease, color .15s ease, box-shadow .15s ease, transform .15s ease',
} as const;

export const FONT_STACK =
  '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", ' +
  '"Microsoft YaHei", "Noto Sans SC", sans-serif';
