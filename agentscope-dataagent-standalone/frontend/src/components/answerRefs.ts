/** The assistant cites a rendered chart as ![title](chart://<chartId>) where it belongs in the answer. */
export const CHART_REF = /^chart:\/\/([A-Za-z0-9-]+)$/;

/** Chart ids an answer cites inline, so the page does not show those charts again below it. */
export function citedChartIds(text: string): Set<string> {
  return new Set([...text.matchAll(/\]\(chart:\/\/([A-Za-z0-9-]+)\)/g)].map(m => m[1]));
}

/** spec101: one saved query result, as its tool result announces it. */
export interface QueryDataFile { href: string; rows: string | null }

/**
 * Query results saved as CSV ("[下载查询数据](…)" in a run_sql / query_cube result). The link used
 * to sit in the collapsed trace only, while answers told users to "click the link above".
 */
export function queryDataFiles(results: (string | undefined)[]): QueryDataFile[] {
  const seen = new Set<string>();
  const out: QueryDataFile[] = [];
  for (const result of results) {
    if (!result) continue;
    const link = /\[下载查询数据\]\(([^)\s]+)\)/.exec(result);
    if (!link || seen.has(link[1])) continue;
    seen.add(link[1]);
    out.push({ href: link[1], rows: /（(\d+) 行 ×/.exec(result)?.[1] ?? null });
  }
  return out;
}
