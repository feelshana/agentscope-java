/** Vite and the reverse proxy must use the same deployment prefix. */
export const APP_BASE = import.meta.env.BASE_URL.replace(/\/$/, '');
export function appPath(path: string): string { return `${APP_BASE}${path}`; }
