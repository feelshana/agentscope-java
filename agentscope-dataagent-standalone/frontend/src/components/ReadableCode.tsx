import { useEffect, useId, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import Prism from 'prismjs';
import 'prismjs/components/prism-yaml';
import 'prismjs/components/prism-sql';
import 'prismjs/components/prism-json';
import Icon from './Icon';
import { formatReadableSql } from './formatReadableSql';
import './ReadableCode.css';

/** Coloured spans for Prism tokens; shared with the SQL editor so both read the same. */
export function highlightTokens(value: string | Prism.Token | (string | Prism.Token)[]): ReactNode {
  return tokens(value);
}

function tokens(value: string | Prism.Token | (string | Prism.Token)[]): ReactNode {
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) return value.map((item, index) => <span key={index}>{tokens(item)}</span>);
  const color = ['keyword', 'atrule'].includes(value.type) ? 'var(--da-primary)' : ['string', 'scalar'].includes(value.type) ? '#047857' : value.type === 'comment' ? '#64748b' : value.type === 'number' ? '#b45309' : undefined;
  return <span style={{ color }}>{tokens(value.content)}</span>;
}

export default function ReadableCode({ text, language = 'yaml', title = '完整内容', fill = false, fitHeight = false, header, actions }: {
  text: string; language?: 'yaml' | 'sql' | 'json' | 'text'; title?: string; fill?: boolean; fitHeight?: boolean; header?: ReactNode; actions?: ReactNode;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const id = useId();
  const [raw, setRaw] = useState(false);
  const [formatted, setFormatted] = useState({ source: '', content: '' });
  const [copyStatus, setCopyStatus] = useState('');
  useEffect(() => {
    let active = true;
    if (language === 'sql') void formatReadableSql(text).then(content => { if (active) setFormatted({ source: text, content }); });
    return () => { active = false; };
  }, [text, language]);
  const display = !raw && language === 'sql' && formatted.source === text ? formatted.content : text;
  const rendered = useMemo(() => language === 'text' ? display : tokens(Prism.tokenize(display, Prism.languages[language])), [display, language]);
  const lines = useMemo(() => display.split('\n').map((_, index) => index + 1).join('\n'), [display]);
  const code = <div className="rc-scroll" tabIndex={0} role="region" aria-label={`${title}代码，可滚动`}><div className="rc-code"><pre className="rc-gutter" aria-hidden="true">{lines}</pre><pre className="rc-pre"><code>{rendered}</code></pre></div></div>;
  const modes = language === 'sql' && <><button type="button" aria-pressed={!raw} onClick={() => setRaw(false)}>排版</button><button type="button" aria-pressed={raw} onClick={() => setRaw(true)}>原文</button></>;
  return <section className={fill ? 'rc rc-fill' : fitHeight ? 'rc rc-fit-height' : 'rc'} aria-label={title}>
    <div className="rc-head">
      {header ?? <strong title={title}>{title}</strong>}
      <span className="rc-copy-status" aria-live="polite">{copyStatus}</span>
      <div className="rc-actions">
        {actions}{modes}
        <button type="button" title={language === 'sql' ? '复制原始内容，保留原始 SQL' : '复制原始内容，保留原文格式'}
          onClick={() => { void navigator.clipboard.writeText(text).then(() => setCopyStatus('已复制')).catch(() => setCopyStatus('复制失败，请选中文本复制')); }}
          aria-label="复制原始内容"><Icon name="copy" size="sm" /></button>
        {!fill && <button type="button" onClick={() => dialog.current?.showModal()}><Icon name="fit" size="sm" />放大阅读</button>}
      </div>
    </div>
    {code}
    {!fill && <dialog ref={dialog} className="rc-dialog" aria-labelledby={`${id}-title`}><div className="rc-head"><strong id={`${id}-title`}>{title}</strong><div className="rc-actions">{modes}<button type="button" onClick={() => dialog.current?.close()}><Icon name="close" size="sm" />关闭</button></div></div>{code}</dialog>}
  </section>;
}
