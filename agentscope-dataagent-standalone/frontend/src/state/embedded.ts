/** A single knowledge base supplies its own persistent navigation. */
export const isKnowledgeWorkspace = (pathname: string) => /^\/configure\/(datasets|modeling)\/[^/]+/.test(pathname);
