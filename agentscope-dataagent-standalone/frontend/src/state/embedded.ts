/** The knowledge-base modeling workbench, where the work area needs the width most. */
export const isModelingWorkbench = (pathname: string) => /^\/configure\/modeling\/[^/]+/.test(pathname);
