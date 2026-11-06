function webUrl(value: string): string | null {
  try {
    const url = new URL(value);
    return (url.protocol === 'http:' || url.protocol === 'https:') && !url.username && !url.password
      ? url.href
      : null;
  } catch {
    return null;
  }
}

/** Strip Markdown delimiters, retaining balanced parentheses inside a URL. */
function citedUrl(value: string): string | null {
  let candidate = value.replace(/[.,;:!?。，；：！？、]+$/, '');
  const extraClosing = (candidate.match(/\)/g)?.length ?? 0) - (candidate.match(/\(/g)?.length ?? 0);
  for (let extra = extraClosing; extra > 0 && candidate.endsWith(')'); extra--) {
    candidate = candidate.slice(0, -1);
  }
  return webUrl(candidate);
}

export function citedWebSources(answer: string, tools: { name: string; result?: string }[]) {
  if (!tools.some(tool => tool.name === 'web_search' && tool.result)) return [];
  const citations = new Set(Array.from(
    answer.matchAll(/https?:\/\/[^\s<>\[\]"`]+/g), match => citedUrl(match[0]),
  ));
  const sources = new Map<string, string>();
  for (const tool of tools) {
    if (tool.name !== 'web_search' || !tool.result) continue;
    for (const match of tool.result.matchAll(/^\d+\. ([^\r\n]*)\r?\n   网址：(\S+)$/gm)) {
      const url = webUrl(match[2]);
      if (url && citations.has(url) && !sources.has(url)) {
        sources.set(url, match[1].trim() || new URL(url).hostname);
      }
    }
  }
  return Array.from(sources, ([url, title]) => ({ url, title }));
}

/** Only explicit requests get the shortcut. The user still has to opt in. */
export function explicitlyRequestsWebSearch(text: string): boolean {
  if (/(?:不要|禁止|无需|不必|别|不用)(?:再)?(?:联网|上网|在线)/.test(text)) return false;
  return /(?:联网|上网|网上|在线)(?:帮我|给我|去)?(?:查|搜|检索)|(?:查|搜|检索)(?:一下)?(?:网上|网络上|互联网上)|\b(?:search|look up|check) (?:the web|online)\b/i.test(text)
    || /(?:搜|搜索|查|查找)(?:一下|下)?(?:今天|今日|最新|实时)的?(?:新闻|时事|公开消息)/.test(text);
}
