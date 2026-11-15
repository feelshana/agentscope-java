export interface DocumentHeading { line: number; level: number; text: string }

/** Match Markdown ATX headings outside code fences; line ids also match the Markdown AST. */
export function documentHeadings(content: string): DocumentHeading[] {
  const headings: DocumentHeading[] = [];
  let fence = '';
  for (const [index, line] of content.split(/\r?\n/).entries()) {
    const marker = /^ {0,3}(`{3,}|~{3,})/.exec(line)?.[1];
    if (marker) {
      if (!fence) fence = marker;
      else if (marker[0] === fence[0] && marker.length >= fence.length) fence = '';
      continue;
    }
    if (fence) continue;
    const match = /^ {0,3}(#{1,6})[ \t]+(.+?)\s*$/.exec(line);
    if (match) headings.push({ line: index + 1, level: match[1].length, text: match[2].replace(/[ \t]+#+[ \t]*$/, '').replace(/\*\*|`/g, '') });
  }
  return headings;
}

/** Literal matching avoids interpreting user search text as a regular expression. */
export function documentTextParts(text: string, query: string): { text: string; matched: boolean }[] {
  if (!query) return [{ text, matched: false }];
  const lower = text.toLowerCase();
  const needle = query.toLowerCase();
  const parts: { text: string; matched: boolean }[] = [];
  let position = 0;
  let index: number;
  while ((index = lower.indexOf(needle, position)) >= 0) {
    if (index > position) parts.push({ text: text.slice(position, index), matched: false });
    parts.push({ text: text.slice(index, index + query.length), matched: true });
    position = index + query.length;
  }
  if (position < text.length || !parts.length) parts.push({ text: text.slice(position), matched: false });
  return parts;
}
