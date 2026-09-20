import React, { useState } from 'react';
import { createPortal } from 'react-dom';
import Prism from 'prismjs';
import 'prismjs/components/prism-python';
import { ACTIVE_AGENT_ID } from '../api/activeAgent';
import { getToken } from '../api/auth';
import Markdown from './Markdown';

const PY_STYLE = `
.py-block {
  background: #fff;
  border-radius: 0 0 8px 8px;
  overflow: hidden;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 0.82rem;
  line-height: 1.6;
  color: #1e293b;
}
.py-block-inner {
  display: flex;
  max-height: 420px;
  overflow: hidden;
}
.py-lineno-col {
  flex-shrink: 0;
  width: 52px;
  padding: 0.7rem 0.6rem 0.7rem 1rem;
  text-align: right;
  color: #b0b8c4;
  user-select: none;
  border-right: 1px solid #eef1f5;
  background: #fafbfc;
  font-size: 0.78rem;
  line-height: 1.6;
  white-space: nowrap;
  overflow-y: auto;
}
.py-code-col {
  flex: 1;
  min-width: 0;
  padding: 0.7rem 1rem;
  white-space: pre;
  overflow: auto;
}
.py-block .token.keyword   { color: #a626a4; font-weight: 500; }
.py-block .token.string    { color: #2a8d3b; }
.py-block .token.comment   { color: #8e99a4; font-style: italic; }
.py-block .token.function  { color: #4078f2; }
.py-block .token.number    { color: #986801; }
.py-block .token.operator  { color: #383a42; }
.py-block .token.builtin   { color: #c18401; }
.py-block .token.boolean   { color: #986801; }
.py-block .token.class-name{ color: #c18401; font-weight: 500; }
.py-block .token.decorator { color: #a626a4; }
.py-block .token.punctuation { color: #383a42; }
`;

export interface ArtifactInfo {
  name: string;
  type: 'image' | 'csv' | 'text' | 'svg';
  size: number;
  contentB64?: string;
  content?: string;
  path?: string;
}

export function deduplicateArtifacts(artifacts: ArtifactInfo[]): ArtifactInfo[] {
  const seen = new Set<string>();
  return artifacts.filter(a => {
    const key = `${a.name}\t${a.size}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

interface Props {
  code: string;
  result?: string;
  defaultOpen?: boolean;
}

const s: Record<string, React.CSSProperties> = {
  root: {
    border: '1px solid #e2e8f0',
    borderRadius: 9,
    margin: '0.5rem 0',
    overflow: 'hidden',
    fontSize: '0.9rem',
    background: '#fff',
  },
  section: {
    borderTop: '1px solid #e2e8f0',
  },
  trigger: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '8px 12px',
    cursor: 'pointer',
    userSelect: 'none',
    background: '#f8fafc',
    fontSize: '0.82rem',
    fontWeight: 500,
    color: '#475569',
  },
  triggerError: {
    background: '#fef2f2',
    color: '#b91c1c',
  },
  chevron: {
    fontSize: '0.65rem',
    color: '#94a3b8',
    transition: 'transform 0.15s ease',
    display: 'inline-block',
    width: 12,
    textAlign: 'center',
  },
  badge: {
    fontSize: '0.72rem',
    padding: '1px 8px',
    borderRadius: 4,
    fontWeight: 600,
  },
  body: {
    padding: '0.85rem 1rem',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.82rem',
    lineHeight: 1.55,
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
    color: '#475569',
    maxHeight: 300,
    overflowY: 'auto',
  },
  artifactList: {
    padding: '8px 12px',
    display: 'flex',
    flexDirection: 'column',
    gap: 6,
  },
  artifactRow: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '4px 0',
    fontSize: '0.8rem',
    color: '#475569',
  },
  downloadBtn: {
    border: '1px solid #cbd5e1',
    background: '#f8fafc',
    color: '#475569',
    borderRadius: 5,
    padding: '2px 10px',
    fontSize: '0.74rem',
    cursor: 'pointer',
    fontFamily: 'ui-sans-serif, system-ui, sans-serif',
    whiteSpace: 'nowrap' as const,
    flexShrink: 0,
  },
};

function Section({ title, icon, defaultOpen = false, error, badge, children }: {
  title: string;
  icon: React.ReactNode;
  defaultOpen?: boolean;
  error?: boolean;
  badge?: React.ReactNode;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div style={s.section}>
      <div
        style={{ ...s.trigger, ...(error ? s.triggerError : {}) }}
        onClick={() => setOpen(o => !o)}
      >
        <span style={{
          width: 14, height: 14, display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
          color: '#94a3b8', flexShrink: 0,
          transition: 'transform 0.2s ease', transform: open ? 'rotate(90deg)' : 'rotate(0deg)',
        }}>
          <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
        </span>
        <span style={{ width: 16, height: 16, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, color: '#64748b' }}>{icon}</span>
        <span style={{ flex: 1 }}>{title}</span>
        {badge}
      </div>
      {open && children}
    </div>
  );
}

function splitSpaceCols(line: string): string[] {
  return line.trim().split(/\s{2,}/).map(s => s.trim()).filter(Boolean);
}

function isValidSpaceTable(lines: string[]): boolean {
  if (lines.length < 2) return false;
  const headerCols = splitSpaceCols(lines[0]).length;
  if (headerCols < 2) return false;
  for (let i = 1; i < lines.length; i++) {
    if (splitSpaceCols(lines[i]).length !== headerCols) return false;
  }
  return true;
}

function spaceTableToMarkdown(lines: string[]): string {
  const headers = splitSpaceCols(lines[0]);
  const rows = lines.slice(1).map(l => splitSpaceCols(l));
  const hLine = '| ' + headers.map(h => h.replace(/\|/g, '\\|')).join(' | ') + ' |';
  const sepLine = '| ' + headers.map(() => '---').join(' | ') + ' |';
  const dataLines = rows.map(r =>
    '| ' + headers.map((_, i) => (r[i] || '').replace(/\|/g, '\\|')).join(' | ') + ' |'
  );
  return [hLine, sepLine, ...dataLines].join('\n');
}

function isAsciiSep(line: string): boolean {
  const t = line.trim();
  return t.length >= 3 && /^[\s\-+:=]+$/.test(t) && t.includes('-');
}

function detectTableEnd(lines: string[], start: number): number {
  let end = start;
  while (end < lines.length && lines[end].trim() !== '') end++;
  return end;
}

function toMarkdownTable(lines: string[]): string | null {
  if (lines.length < 2) return null;
  let sepIdx = -1;
  for (let i = 0; i < Math.min(lines.length, 4); i++) {
    if (isAsciiSep(lines[i])) { sepIdx = i; break; }
  }
  if (sepIdx < 0) return null;

  const headers = lines[0].split('|').map(s => s.trim()).filter(Boolean);
  if (headers.length === 0) {
    const h = lines[0].trim().split(/\s{2,}/);
    if (h.length < 2) return null;
    headers.push(...h);
  }

  const rows: string[][] = [];
  for (let i = sepIdx + 1; i < lines.length; i++) {
    const t = lines[i].trim();
    if (!t || isAsciiSep(t)) continue;
    const cells = t.startsWith('|')
      ? t.split('|').slice(1, -1).map(s => s.trim())
      : t.split(/\s{2,}/).map(s => s.trim());
    if (cells.length >= headers.length) rows.push(cells);
  }
  if (rows.length === 0) return null;

  const hLine = '| ' + headers.map(h => h.replace(/\|/g, '\\|')).join(' | ') + ' |';
  const sepLine = '| ' + headers.map(() => '---').join(' | ') + ' |';
  const dataLines = rows.map(r =>
    '| ' + headers.map((_, i) => (r[i] || '').replace(/\|/g, '\\|')).join(' | ') + ' |'
  );
  return [hLine, sepLine, ...dataLines].join('\n');
}

function formatStdoutContent(stdout: string): string {
  const lines = stdout.split('\n');
  const out: string[] = [];
  let i = 0;

  while (i < lines.length) {
    const trimmed = lines[i].trim();

    if (trimmed.length > 0) {
      const cols = splitSpaceCols(lines[i]);
      if (cols.length >= 2) {
        const end = detectTableEnd(lines, i + 1);
        const block = lines.slice(i, end);
        if (isValidSpaceTable(block)) {
          out.push('', spaceTableToMarkdown(block), '');
          i = end;
          continue;
        }
      }
    }

    if (isAsciiSep(lines[i]) && i > 0) {
      const start = i - 1;
      const end = detectTableEnd(lines, i + 1);
      const tableLines = lines.slice(start, end);
      const md = toMarkdownTable(tableLines);
      if (md) {
        out.push('', md, '');
        i = end;
        continue;
      }
    }

    out.push(lines[i]);
    i++;
  }

  return out.join('\n').replace(/\n{3,}/g, '\n\n').trim();
}

export function parseResult(result: string): {
  exitCode: number;
  stdout: string;
  artifacts: ArtifactInfo[];
} {
  const hasRealNewlines = result.includes('\n');
  const hasLiteralEscapes = /\\n/.test(result);
  if (!hasRealNewlines && hasLiteralEscapes) {
    result = result.replace(/\\n/g, '\n');
  }

  let exitCode = 0;
  let stdout = '';
  const artifacts: ArtifactInfo[] = [];

  const exitMatch = result.match(/### exit_code: (\d+)/);
  if (exitMatch) exitCode = parseInt(exitMatch[1], 10);

  const stdoutMatch = result.match(/### stdout\n```\n([\s\S]*?)```/);
  if (stdoutMatch) stdout = stdoutMatch[1];

  const artifactSections = result.split(/### artifact:/);
  for (let i = 1; i < artifactSections.length; i++) {
    const section = artifactSections[i];
    const nameMatch = section.match(/^([^\n]+)/);
    const typeMatch = section.match(/- type: (\S+)/);
    const sizeMatch = section.match(/- size: (\d+)/);
    const b64Match = section.match(/- content_b64: (\S+)/);
    const pathMatch = section.match(/- path: ([^\n]+)/);
    const textMatch = section.match(/- content:\n```\n([\s\S]*?)```/);

    if (nameMatch) {
      const rawName = nameMatch[1].trim();
      if (rawName.includes('\\n-') || rawName.includes('- type:')) continue;
      const artifact: ArtifactInfo = {
        name: rawName,
        type: (typeMatch?.[1] as ArtifactInfo['type']) ?? 'text',
        size: sizeMatch ? parseInt(sizeMatch[1], 10) : 0,
      };
      if (b64Match) artifact.contentB64 = b64Match[1];
      if (pathMatch) artifact.path = pathMatch[1].trim();
      if (textMatch) artifact.content = textMatch[1];
      artifacts.push(artifact);
    }
  }

  return { exitCode, stdout, artifacts };
}

function imageMimeType(name: string): string {
  const lower = name.toLowerCase();
  if (lower.endsWith('.svg')) return 'image/svg+xml';
  if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return 'image/jpeg';
  return 'image/png';
}

export function artifactSrc(a: ArtifactInfo): string | null {
  if (a.contentB64) return `data:${imageMimeType(a.name)};base64,${a.contentB64}`;
  if (a.path) {
    const token = getToken();
    const base = `/api/agents/${ACTIVE_AGENT_ID}/workspace/file/binary?path=${encodeURIComponent(a.path)}`;
    return token ? `${base}&token=${encodeURIComponent(token)}` : base;
  }
  return null;
}

export function downloadArtifact(a: ArtifactInfo) {
  if (a.path) {
    const token = getToken();
    const base =
      `/api/agents/${ACTIVE_AGENT_ID}/workspace/file/binary` +
      `?path=${encodeURIComponent(a.path)}&download=true`;
    const url = token ? `${base}&token=${encodeURIComponent(token)}` : base;
    const link = document.createElement('a');
    link.href = url;
    link.download = a.name;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    return;
  }
  let blob: Blob | null = null;
  if (a.contentB64) {
    try {
      const bytes = Uint8Array.from(atob(a.contentB64), c => c.charCodeAt(0));
      blob = new Blob([bytes], { type: imageMimeType(a.name) });
    } catch {
      blob = null;
    }
  } else if (a.content !== undefined) {
    blob = new Blob([a.content], { type: 'text/plain;charset=utf-8' });
  }
  if (blob) {
    const objUrl = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = objUrl;
    link.download = a.name;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(objUrl);
  }
}

function highlightCode(code: string): string {
  try {
    return Prism.highlight(code, Prism.languages.python, 'python');
  } catch {
    return code.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
}

function PythonCodeView({ code }: { code: string }) {
  const highlighted = highlightCode(code);
  const lineCount = code.split('\n').length;
  const lineNumbers = Array.from({ length: lineCount }, (_, i) => i + 1).join('\n');
  return (
    <>
      <style>{PY_STYLE}</style>
      <div className="py-block">
        <div className="py-block-inner">
          <div className="py-lineno-col"><pre>{lineNumbers}</pre></div>
          <div className="py-code-col" dangerouslySetInnerHTML={{ __html: highlighted }} />
        </div>
      </div>
    </>
  );
}

export default function PythonCodeBlock({ code, result, defaultOpen = false }: Props) {
  const [lightbox, setLightbox] = useState<string | null>(null);
  const parsed = result ? parseResult(result) : null;
  const hasError = parsed ? parsed.exitCode !== 0 : false;

  return (
    <div style={s.root}>
      <Section
        title="Python 代码"
        icon={<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>}
        defaultOpen={defaultOpen}
        badge={parsed ? (
          <span style={{
            ...s.badge,
            color: hasError ? '#b91c1c' : '#16a34a',
            background: hasError ? '#fef2f2' : '#f0fdf4',
          }}>
            {hasError ? `退出码 ${parsed.exitCode}` : '执行成功'}
          </span>
        ) : undefined}
      >
        <PythonCodeView code={code} />
      </Section>

      {parsed && parsed.stdout && (
        <Section title="输出" icon={<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>} error={hasError}>
          {hasError ? (
            <div style={{ ...s.body, color: '#b91c1c' }}>{parsed.stdout}</div>
          ) : (
            <div style={{ ...s.body, overflowY: 'auto' }}>
              <Markdown>{formatStdoutContent(parsed.stdout)}</Markdown>
            </div>
          )}
        </Section>
      )}

      {parsed && parsed.artifacts.length > 0 && (
        <Section
          title="生成产物"
          icon={<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>}
          defaultOpen
          badge={
            <span style={{ ...s.badge, color: '#854d0e', background: '#fefce8' }}>
              {parsed.artifacts.length} 个文件
            </span>
          }
        >
          <div style={s.artifactList}>
            {parsed.artifacts.map((a, i) => (
              <div key={i} style={s.artifactRow}>
                <span style={{ flex: 1, minWidth: 0, wordBreak: 'break-all' }}>
                  {a.name}
                  <span style={{ color: '#94a3b8', fontSize: '0.72rem', marginLeft: 4 }}>
                    ({a.type}, {(a.size / 1024).toFixed(1)} KB)
                  </span>
                </span>
                <button
                  type="button"
                  style={{ ...s.downloadBtn, display: 'inline-flex', alignItems: 'center', gap: 4 }}
                  onClick={() => downloadArtifact(a)}
                  title={`下载 ${a.name}`}
                >
                  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                  下载
                </button>
              </div>
            ))}
          </div>
        </Section>
      )}

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
    </div>
  );
}
