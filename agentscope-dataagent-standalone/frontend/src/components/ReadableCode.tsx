import { useMemo, useRef } from 'react';
import type { ReactNode } from 'react';
import Prism from 'prismjs';
import 'prismjs/components/prism-yaml';
import 'prismjs/components/prism-sql';
import 'prismjs/components/prism-json';

function tokens(value: string | Prism.Token | (string | Prism.Token)[]): ReactNode {
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) return value.map((item, index) => <span key={index}>{tokens(item)}</span>);
  const color = ['keyword', 'atrule'].includes(value.type) ? '#7c3aed' : ['string', 'scalar'].includes(value.type) ? '#047857' : value.type === 'comment' ? '#64748b' : value.type === 'number' ? '#b45309' : undefined;
  return <span style={{ color }}>{tokens(value.content)}</span>;
}

export default function ReadableCode({ text, language = 'yaml', title = '完整内容' }: { text: string; language?: 'yaml' | 'sql' | 'json'; title?: string }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const rendered = useMemo(() => tokens(Prism.tokenize(text, Prism.languages[language])), [text, language]);
  const code = <pre style={{ margin: 0, padding: 16, fontSize: 15, lineHeight: 1.75, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', background: '#f8fafc', color: '#0f172a', borderRadius: 8 }}><code>{rendered}</code></pre>;
  return <div><div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', margin: '8px 0' }}><strong>{title}</strong><button type="button" className="da-btn" onClick={() => dialog.current?.showModal()}>放大阅读</button></div><div style={{ maxHeight: 420, overflow: 'auto' }}>{code}</div><dialog ref={dialog} style={{ width: 'min(1000px, 90vw)', maxHeight: '85vh', border: '1px solid var(--da-border)', borderRadius: 12, padding: 20 }}><div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}><strong>{title}</strong><button type="button" className="da-btn" onClick={() => dialog.current?.close()}>关闭</button></div>{code}</dialog></div>;
}
