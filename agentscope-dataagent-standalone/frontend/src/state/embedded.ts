/**
 * True when the app runs inside another page's iframe (for example the 红海数据中台 portal), whose
 * own header already takes vertical space; the shell then drops what the host already shows.
 */
export const embedded: boolean = (() => {
  try { return window.self !== window.top; } catch { return true; }
})();

/** The knowledge-base modeling workbench, where the work area needs the width most. */
export const isModelingWorkbench = (pathname: string) => /^\/configure\/modeling\/[^/]+/.test(pathname);
