/**
 * Line-level diff for the modeling HITL file-change card (specs/019 §5). The project forbids new
 * runtime dependencies, so this is a plain LCS over lines — workspace YAML/MD files are small
 * (tens to a few hundred lines), where the O(n·m) table is fine.
 */

export interface DiffLine {
  type: 'add' | 'del' | 'context';
  text: string;
}

function toLines(text: string): string[] {
  if (text.length === 0) return [];
  const lines = text.split('\n');
  // A trailing newline yields a trailing empty element; dropping it keeps diffs clean while
  // interior blank lines are preserved.
  if (lines[lines.length - 1] === '') lines.pop();
  return lines;
}

/** LCS diff between two texts, returned in document order. */
export function diffLines(oldText: string, newText: string): DiffLine[] {
  const oldLines = toLines(oldText);
  const newLines = toLines(newText);
  const n = oldLines.length;
  const m = newLines.length;
  if (n === 0) return newLines.map(text => ({ type: 'add', text }));
  if (m === 0) return oldLines.map(text => ({ type: 'del', text }));

  // dp[i][j] = LCS length of oldLines[i..] and newLines[j..]
  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] =
        oldLines[i] === newLines[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }

  const out: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (oldLines[i] === newLines[j]) {
      out.push({ type: 'context', text: oldLines[i] });
      i += 1;
      j += 1;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      out.push({ type: 'del', text: oldLines[i] });
      i += 1;
    } else {
      out.push({ type: 'add', text: newLines[j] });
      j += 1;
    }
  }
  while (i < n) {
    out.push({ type: 'del', text: oldLines[i] });
    i += 1;
  }
  while (j < m) {
    out.push({ type: 'add', text: newLines[j] });
    j += 1;
  }
  return out;
}

/** Number of changed (non-context) lines, for the collapsed summary. */
export function changedLineCount(lines: DiffLine[]): number {
  return lines.filter(line => line.type !== 'context').length;
}
