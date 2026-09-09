import React from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

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
  border-left: 3px solid #c7d2fe;
  color: #64748b;
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
  border: 1px solid #e2e8f0;
  padding: 0.35em 0.7em;
  text-align: left;
}
.claw-md th { background: #f8fafc; font-weight: 600; }
.claw-md a { color: #4f46e5; }
.claw-md hr { border: none; border-top: 1px solid #e2e8f0; margin: 1em 0; }
.claw-md img { max-width: 100%; }
`;

/** Renders markdown text (GFM tables/strikethrough included) for assistant replies. */
export default function Markdown({ children }: { children: string }) {
  return (
    <>
      <style>{MD_STYLE}</style>
      <div className="claw-md">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{children}</ReactMarkdown>
      </div>
    </>
  );
}
