/** Bounded parallel work; one rejection never hides successful siblings. */
export async function settleUploads<T, R>(items: T[], concurrency: number, work: (item: T, index: number) => Promise<R>): Promise<PromiseSettledResult<R>[]> {
  const results: PromiseSettledResult<R>[] = new Array(items.length);
  let next = 0;
  const workers = Math.min(items.length, Number.isFinite(concurrency) ? Math.max(1, Math.floor(concurrency)) : 1);
  await Promise.all(Array.from({ length: workers }, async () => {
    while (next < items.length) {
      const index = next++;
      try { results[index] = { status: 'fulfilled', value: await work(items[index], index) }; }
      catch (reason) { results[index] = { status: 'rejected', reason }; }
    }
  }));
  return results;
}
