export function updateWorkspaceSelection(previous: Set<string>, id: string, checked: boolean) {
  const next = new Set(previous);
  if (checked) next.add(id);
  else next.delete(id);
  return next;
}

export async function removeWorkspaceItems<T>(targets: readonly T[], remove: (target: T) => Promise<void>) {
  const removed: T[] = [];
  const failures: { target: T; message: string }[] = [];
  // Mutations in one knowledge base may update shared draft files or impact revisions.
  for (const target of targets) {
    try {
      await remove(target);
      removed.push(target);
    } catch (error) {
      failures.push({ target, message: error instanceof Error ? error.message : String(error) });
    }
  }
  return { removed, failures };
}
