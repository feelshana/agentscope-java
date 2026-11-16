import { Children, cloneElement, createContext, createElement, isValidElement, memo, useContext, useDeferredValue, useEffect, useId, useMemo, useRef, useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import ReactMarkdown from 'react-markdown';
import type { Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { getDocumentContent } from '../../api/draftReview';
import type { BusinessDocument } from '../../api/draftReview';
import Icon from '../Icon';
import ErrorNotice from '../ErrorNotice';
import { documentHeadings, documentTextParts } from './businessDocumentPresentation';
import './ModelingOptimization.css';

const STATUS = { ADDED: '新增 · 未发布', MODIFIED: '已替换 · 未发布', UNCHANGED: '已发布', DELETED: '已删除 · 未发布' };
const Display = createContext({ query: '', prefix: '' });
const PLUGINS = [remarkGfm];

function highlighted(children: ReactNode, query: string): ReactNode {
  if (!query) return children;
  return Children.map(children, child => {
    if (typeof child === 'string') return documentTextParts(child, query).map((part, index) => part.matched ? <mark key={index}>{part.text}</mark> : part.text);
    if (!isValidElement<{ children?: ReactNode }>(child) || child.type === 'mark') return child;
    return cloneElement(child, undefined, highlighted(child.props.children, query));
  });
}
function Highlight({ children }: { children?: ReactNode }) {
  return <>{highlighted(children, useContext(Display).query)}</>;
}
function Heading({ level, line, children }: { level: number; line?: number; children?: ReactNode }) {
  const { prefix } = useContext(Display);
  return createElement(`h${level}`, { id: line ? `${prefix}-${line}` : undefined }, <Highlight>{children}</Highlight>);
}
const RENDERERS: Components = {
  h1: ({ node, children }) => <Heading level={1} line={node?.position?.start.line}>{children}</Heading>,
  h2: ({ node, children }) => <Heading level={2} line={node?.position?.start.line}>{children}</Heading>,
  h3: ({ node, children }) => <Heading level={3} line={node?.position?.start.line}>{children}</Heading>,
  h4: ({ node, children }) => <Heading level={4} line={node?.position?.start.line}>{children}</Heading>,
  h5: ({ node, children }) => <Heading level={5} line={node?.position?.start.line}>{children}</Heading>,
  h6: ({ node, children }) => <Heading level={6} line={node?.position?.start.line}>{children}</Heading>,
  p: ({ children }) => <p><Highlight>{children}</Highlight></p>,
  li: ({ children }) => <li><Highlight>{children}</Highlight></li>,
  code: ({ children, className }) => <code className={className}><Highlight>{children}</Highlight></code>,
  th: ({ children }) => <th scope="col"><Highlight>{children}</Highlight></th>,
  td: ({ children }) => <td><Highlight>{children}</Highlight></td>,
  table: ({ children }) => <div className="mo-doc-table" tabIndex={0} role="region" aria-label="文档表格，可横向滚动"><table>{children}</table></div>,
  // Reading a document must not fetch external images or use the chat attachment downloader.
  img: ({ alt }) => <span className="mo-doc-image">[图片{alt ? `：${alt}` : ''}]</span>,
  a: ({ href, children }) => /^https?:\/\//i.test(href ?? '')
    ? <a href={href} target="_blank" rel="noopener noreferrer"><Highlight>{children}</Highlight></a>
    : <span><Highlight>{children}</Highlight></span>,
};
export const BusinessDocumentArticle = memo(function BusinessDocumentArticle({ content, query, prefix }: { content: string; query: string; prefix: string }) {
  const display = useMemo(() => ({ query, prefix }), [query, prefix]);
  return <Display.Provider value={display}><ReactMarkdown remarkPlugins={PLUGINS} components={RENDERERS}>{content}</ReactMarkdown></Display.Provider>;
});

/** Read the selected document on demand; no prefetch or second document store. */
export default function BusinessDocumentReader({ groupId, document, onClose }: {
  groupId: string; document: BusinessDocument; onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const body = useRef<HTMLDivElement>(null);
  const [content, setContent] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const [query, setQuery] = useState('');
  const deferredQuery = useDeferredValue(query.trim());
  const [raw, setRaw] = useState(false);
  const [fontSize, setFontSize] = useState(14);
  const prefix = useId();
  const headings = useMemo(() => documentHeadings(content ?? ''), [content]);
  const matches = useMemo(() => deferredQuery ? documentTextParts(content ?? '', deferredQuery).filter(part => part.matched).length : 0, [content, deferredQuery]);

  useEffect(() => { if (dialog.current && !dialog.current.open) dialog.current.showModal(); }, []);
  useEffect(() => {
    let active = true;
    setContent(null); setError(null); setQuery(''); setRaw(false);
    if (document.status === 'DELETED') { setError('文档已删除，无法读取当前正文。'); return; }
    void getDocumentContent(groupId, document.name).then(value => {
      if (active) setContent(value.content);
    }).catch(e => { if (active) setError(e instanceof Error ? e.message : '读取业务文档失败'); });
    return () => { active = false; };
  }, [groupId, document.name, document.status, retry]);

  const close = () => { dialog.current?.close(); onClose(); };
  return <dialog ref={dialog} className="mo-document-reader" aria-labelledby={`${prefix}-title`} onClose={onClose}
    onCancel={event => { event.preventDefault(); close(); }} onClick={event => {
      if (event.target !== event.currentTarget) return;
      const bounds = event.currentTarget.getBoundingClientRect();
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) close();
    }}>
    <div className="mo-reader-shell">
      <header className="mo-reader-head"><span className="mo-document-icon"><Icon name="file" /></span><div className="mo-grow">
        <h2 id={`${prefix}-title`}>{document.name}</h2><div className="mo-reader-meta"><span className={`mo-pill ${document.status === 'UNCHANGED' ? 'mo-green' : 'mo-amber'}`}>{STATUS[document.status]}</span><span>当前草稿正文 · 只读</span>{/\.docx(?:\.md)?$/i.test(document.name) && <span>Word 文档正文</span>}</div>
      </div><button type="button" className="da-btn mo-icon-button" onClick={close} aria-label="关闭文档阅读"><Icon name="close" /></button></header>
      <div className="mo-reader-toolbar"><label className="mo-reader-search"><Icon name="search" size="sm" /><input type="search" aria-label="在文档中查找" placeholder="在文档中查找" value={query} disabled={content === null} onChange={event => setQuery(event.target.value)} /><span aria-live="polite">{deferredQuery && (matches ? `${matches} 处匹配` : '无匹配')}</span></label>
        <div className="mo-reader-mode" role="group" aria-label="文档显示方式"><button type="button" aria-pressed={!raw} onClick={() => setRaw(false)}>阅读</button><button type="button" aria-pressed={raw} onClick={() => setRaw(true)}>原始文本</button></div>
        <div className="mo-reader-font" role="group" aria-label="阅读字号"><button type="button" disabled={fontSize <= 12} onClick={() => setFontSize(n => n - 1)} aria-label="减小字号">A−</button><button type="button" disabled={fontSize >= 18} onClick={() => setFontSize(n => n + 1)} aria-label="增大字号">A＋</button></div>
      </div>
      <div ref={body} className="mo-reader-body" tabIndex={0} aria-label="文档正文" style={{ '--mo-reading-size': `${fontSize}px` } as CSSProperties}>
        {error ? <><ErrorNotice error={error} context="file" /><button className="da-btn" onClick={() => setRetry(n => n + 1)}>重试</button></> : content === null ? <p className="da-small" role="status">正在读取文档正文…</p> : !content.trim() ? <div className="mo-empty"><h3>这份文档没有正文</h3><p>可以在资料页替换为包含业务说明的文档。</p></div> : <>
          {!raw && headings.length > 2 && <nav className="mo-doc-outline" aria-label="文档目录"><strong>文档目录</strong>{headings.filter(h => h.level > 1).map(h => <button key={h.line} type="button" onClick={() => globalThis.document.getElementById(`${prefix}-${h.line}`)?.scrollIntoView({ block: 'start', behavior: 'smooth' })}>{h.text}</button>)}</nav>}
          <article className="mo-doc-prose">{raw ? <pre className="mo-doc-raw">{highlighted(content, deferredQuery)}</pre> : <BusinessDocumentArticle content={content} query={deferredQuery} prefix={prefix} />}</article>
        </>}
      </div>
      <footer className="mo-reader-footer"><Icon name="info" size="sm" /><span>只读查看当前草稿正文。文档替换和删除在资料页操作，发布后才对问数生效。</span></footer>
    </div>
  </dialog>;
}
