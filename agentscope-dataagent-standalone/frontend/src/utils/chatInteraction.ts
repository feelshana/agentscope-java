/** Composition events and the legacy 229 code cover IME confirmation across browsers. */
export function isSendKey(event: { key: string; shiftKey: boolean; isComposing?: boolean; keyCode?: number }, composing = false): boolean {
  return event.key === 'Enter' && !event.shiftKey && !composing && !event.isComposing && event.keyCode !== 229;
}

export function isNearBottom(node: { scrollHeight: number; scrollTop: number; clientHeight: number }): boolean {
  return node.scrollHeight - node.scrollTop - node.clientHeight <= 64;
}

/** Keep partial answers readable while presenting a failed turn separately. */
export function splitFailedAnswer(text: string): { answer: string; error: string } {
  const markers = [...text.matchAll(/(?:\*\*)?\[(?:错误|error)\](?:\*\*)?\s*/g)];
  const marker = markers[markers.length - 1];
  if (!marker || marker.index === undefined) return { answer: '', error: text || '本次回答未能完成' };
  return { answer: text.slice(0, marker.index).trimEnd(), error: text.slice(marker.index + marker[0].length).trim() };
}
