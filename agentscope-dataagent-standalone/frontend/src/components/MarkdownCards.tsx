import React, { useState } from 'react';
import { createPortal } from 'react-dom';
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
 * Parse markdown text into sections split by headings.
 * Returns array of { heading, content } where heading can be null for intro text.
 */
function parseSections(text: string): { heading: string | null; content: string }[] {
  const lines = text.split('\n');
  const sections: { heading: string | null; content: string }[] = [];
  let currentHeading: string | null = null;
  let currentLines: string[] = [];

  for (const line of lines) {
    // Match headings like "一、xxx" or "## xxx" or "# xxx"
    const headingMatch = line.match(/^(#{1,3}\s+|^[一二三四五六七八九十]+[、.]\s*)/);
    if (headingMatch) {
      if (currentLines.length > 0 || currentHeading !== null) {
        sections.push({ heading: currentHeading, content: currentLines.join('\n') });
      }
      currentHeading = line.replace(/^(#{1,3}\s+|[一二三四五六七八九十]+[、.]\s*)/, '').trim();
      currentLines = [];
    } else {
      currentLines.push(line);
    }
  }
  if (currentLines.length > 0 || currentHeading !== null) {
    sections.push({ heading: currentHeading, content: currentLines.join('\n') });
  }
  return sections;
}

const CARD_STYLE = `
.md-cards { display: flex; flex-direction: column; gap: 16px; }
.md-card {
  background: #fff;
  border-radius: 12px;
  padding: 20px 24px;
  border: 1px solid #f3f4f6;
  box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}
.md-card-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f3f4f6;
}
.md-card-number {
  width: 26px;
  height: 26px;
  border-radius: 7px;
  background: #6366f1;
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.md-card-title {
  font-size: 15px;
  font-weight: 700;
  color: #111827;
  line-height: 1.4;
}
.md-card-content {
  font-size: 14px;
  line-height: 1.7;
  color: #4b5563;
}
.md-card-content p { margin: 0.6em 0; }
.md-card-content ul, .md-card-content ol { margin: 0.5em 0; padding-left: 1.5em; }
.md-card-content li { margin: 0.25em 0; }
.md-card-content li strong { color: #111827; }
.md-card-content table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid #e5e7eb;
  margin: 10px 0;
  font-size: 13px;
}
.md-card-content th {
  background: #f8fafc;
  padding: 9px 12px;
  text-align: left;
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
  text-transform: uppercase;
  letter-spacing: 0.03em;
  border-bottom: 1px solid #e2e8f0;
}
.md-card-content td {
  padding: 9px 12px;
  border-bottom: 1px solid #f1f5f9;
  color: #334155;
}
.md-card-content tr:last-child td { border-bottom: none; }
.md-card-content strong { color: #111827; font-weight: 600; }
.md-card-content code {
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 4px;
  padding: 0.1em 0.35em;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.88em;
}
.md-card-content pre {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 0.75rem 1rem;
  overflow-x: auto;
  margin: 0.8em 0;
}
.md-card-content pre code { background: transparent; border: none; padding: 0; font-size: 0.85em; }
.md-card-content blockquote {
  margin: 0.8em 0;
  padding: 0.6em 1em;
  border-left: 4px solid #6366f1;
  background: #f5f3ff;
  border-radius: 0 8px 8px 0;
  color: #4c1d95;
}
.md-card-content a { color: #6366f1; }
.md-card-content img { max-width: 100%; border-radius: 6px; }

/* Insight card for blockquotes */
.md-insight-card {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 12px;
  padding: 18px 22px;
  margin: 14px 0;
  color: #fff;
}
.md-insight-card .insight-title {
  font-size: 13px;
  font-weight: 600;
  opacity: 0.9;
  margin-bottom: 8px;
}
.md-insight-card .insight-text {
  font-size: 14px;
  line-height: 1.6;
}
.md-insight-card strong { color: #fff; font-weight: 700; }
`;

interface MarkdownCardsProps {
  children: string;
}

export default function MarkdownCards({ children }: MarkdownCardsProps) {
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
            img.style.opacity = '0.3';
            img.style.border = '1px dashed #cbd5e1';
            img.title = `图片未找到：${alt || src}`;
          }}
        />
      );
    },
  };

  const sections = parseSections(children);

  // Extract number from heading like "一、总体格局" → "一"
  const extractNumber = (heading: string): string => {
    const match = heading.match(/^([一二三四五六七八九十]+)[、.]/);
    if (match) return match[1];
    const numMatch = heading.match(/^(\d+)[、.]/);
    if (numMatch) return numMatch[1];
    return '';
  };

  return (
    <>
      <style>{CARD_STYLE}</style>
      <div className="md-cards">
        {sections.map((section, idx) => {
          const isInsight = section.content.includes('关键事实') || section.content.includes('关键洞察');
          const number = section.heading ? extractNumber(section.heading) : '';

          return (
            <div key={idx} className={isInsight ? 'md-insight-card' : 'md-card'}>
              {section.heading && !isInsight && (
                <div className="md-card-header">
                  {number && <div className="md-card-number">{number}</div>}
                  <div className="md-card-title">{section.heading}</div>
                </div>
              )}
              {isInsight ? (
                <div className="insight-text">
                  {section.heading && <div className="insight-title">💡 {section.heading}</div>}
                  <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
                    {section.content}
                  </ReactMarkdown>
                </div>
              ) : (
                <div className="md-card-content">
                  <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
                    {section.content}
                  </ReactMarkdown>
                </div>
              )}
            </div>
          );
        })}
      </div>
      {lightbox && createPortal(
        <div
          style={{
            position: 'fixed', inset: 0, zIndex: 9999,
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
        </div>,
        document.body,
      )}
    </>
  );
}
