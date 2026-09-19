import React, { useState } from 'react';
import ReactMarkdown, { Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { ACTIVE_AGENT_ID } from '../api/activeAgent';
import { getToken } from '../api/auth';

/** Rewrite /workspace/… image src to the workspace binary API URL (with auth token). */
function rewriteImgSrc(src: string): string {
  if (src.startsWith('/workspace/')) {
    const base = `/api/agents/${ACTIVE_AGENT_ID}/workspace/file/binary?path=${encodeURIComponent(src)}`;
    const token = getToken();
    return token ? `${base}&token=${encodeURIComponent(token)}` : base;
  }
  return src;
}

/**
 * Styles for rendered markdown inside chat bubbles. Kept light-themed on
 * purpose (the app is a light UI): code blocks get a pale grey background
 * instead of the usual dark terminal look.
 */
const MD_STYLE = `
.claw-md { font-size: 0.95rem; line-height: 1.65; color: inherit; }
.claw-md > :first-child { margin-top: 0; }
.claw-md > :last-child { margin-bottom: 0; }
.claw-md p { margin: 0.5em 0; }
.claw-md h1, .claw-md h2, .claw-md h3, .claw-md h4, .claw-md h5, .claw-md h6 {
  margin: 0.9em 0 0.4em; line-height: 1.3; font-weight: 600;
}
.claw-md h1 { font-size: 1.25em; }
.claw-md h2 { font-size: 1.15em; }
.claw-md h3 { font-size: 1.05em; }
.claw-md h4, .claw-md h5, .claw-md h6 { font-size: 1em; }
.claw-md ul, .claw-md ol { margin: 0.4em 0; padding-left: 1.4em; }
.claw-md li { margin: 0.2em 0; }
.claw-md code {
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 4px;
  padding: 0.1em 0.35em;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.88em;
}
.claw-md pre {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 0.75rem 1rem;
  overflow-x: auto;
  margin: 0.6em 0;
}
.claw-md pre code { background: transparent; border: none; padding: 0; font-size: 0.85em; }
.claw-md blockquote {
  margin: 0.6em 0;
  padding: 0.2em 0.9em;
  border-left: 3px solid var(--da-border-strong);
  color: var(--da-text-3);
}
.claw-md table {
  border-collapse: collapse;
  margin: 0.7em 0;
  font-size: 0.9em;
  display: block;
  max-width: 100%;
  overflow-x: auto;
}
.claw-md th, .claw-md td {
  border: 1px solid var(--da-border);
  padding: 0.35em 0.7em;
  text-align: left;
}
.claw-md th { background: var(--da-surface-sunken); font-weight: 600; }
.claw-md a { color: var(--da-primary); }
.claw-md hr { border: none; border-top: 1px solid var(--da-border); margin: 1em 0; }
.claw-md img { max-width: 100%; }
`;

/** Renders markdown text (GFM tables/strikethrough included) for assistant replies.
 *  Rewrites /workspace/ image paths to the binary API and supports click-to-zoom. */
export default function Markdown({ children }: { children: string }) {
  const [lightbox, setLightbox] = useState<string | null>(null);

  const components: Components = {
    img: ({ src, alt }) => {
      const url = rewriteImgSrc(src ?? '');
      return (
        <img
          src={url}
          alt={alt ?? ''}
          style={{ maxWidth: '100%', cursor: 'zoom-in', borderRadius: 6 }}
          onClick={() => setLightbox(url)}
          onError={(e) => {
            const img = e.currentTarget;
            // Hide broken images gracefully
            img.style.opacity = '0.3';
            img.style.border = '1px dashed #cbd5e1';
            img.title = `Image not found: ${alt || src}`;
          }}
        />
      );
    },
  };

  return (
    <>
      <style>{MD_STYLE}</style>
      <div className="claw-md">
        <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>{children}</ReactMarkdown>
      </div>
      {lightbox && (
        <div
          style={{
            position: 'fixed', inset: 0, zIndex: 200,
            background: 'rgba(0,0,0,0.75)', display: 'flex',
            alignItems: 'center', justifyContent: 'center',
            cursor: 'zoom-out', padding: 24,
          }}
          onClick={() => setLightbox(null)}
        >
          <img
            src={lightbox}
            alt=""
            style={{ maxWidth: '92vw', maxHeight: '92vh', borderRadius: 8, boxShadow: '0 8px 32px rgba(0,0,0,0.4)' }}
          />
        </div>
      )}
    </>
  );
}
