import AttachmentImage from './AttachmentImage';
import React, { useState } from 'react';
import { createPortal } from 'react-dom';
import Icon from './Icon';
import Markdown from './Markdown';
import PythonCodeBlock from './PythonCodeBlock';
import { ArtifactInfo, parseResult, artifactSrc, downloadArtifact, deduplicateArtifacts } from './PythonCodeBlock';
import { formatResult, ToolInspectPayload } from './ToolCallBlock';

export interface ToolInspectorProps {
  tool: ToolInspectPayload;
  onClose: () => void;
}

/* ─── colour tokens ─── */
const C = {
  accent: '#6366f1',
  accentLight: '#eef2ff',
  accentDark: '#4f46e5',
  border: '#e5e7eb',
  borderLight: '#f1f5f9',
  bg: '#ffffff',
  bgSubtle: '#f8fafc',
  bgSunken: '#f1f5f9',
  text: '#1e293b',
  text2: '#475569',
  textMuted: '#94a3b8',
  textLight: '#64748b',
  success: '#10b981',
  successBg: '#ecfdf5',
  warn: '#f59e0b',
  warnBg: '#fffbeb',
  err: '#ef4444',
  errBg: '#fef2f2',
  mono: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
};

/* ─── JSON syntax colours ─── */
const JC = {
  key: '#8b5cf6',
  string: '#059669',
  number: '#d97706',
  bool: '#2563eb',
  null: '#94a3b8',
  bracket: '#64748b',
};

/* ══════════════════════════════════════════════════════════
   Collapsible Section
   ═══════════════════════════════════════════════════════════ */
function Section({
  title, icon, defaultOpen = false, badge, children,
  noBorder = false,
}: {
  title: string; icon?: React.ReactNode; defaultOpen?: boolean;
  badge?: React.ReactNode; children: React.ReactNode; noBorder?: boolean;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div style={{
      border: noBorder ? 'none' : `1px solid ${C.border}`,
      borderRadius: 8, overflow: 'hidden', background: C.bg,
      boxShadow: noBorder ? 'none' : '0 1px 2px rgba(0,0,0,0.03)',
    }}>
      <div
        onClick={() => setOpen(o => !o)}
        style={{
          display: 'flex', alignItems: 'center', gap: 8,
          padding: '9px 14px', cursor: 'pointer', userSelect: 'none',
          background: C.bgSubtle, borderBottom: open ? `1px solid ${C.border}` : 'none',
          fontSize: '0.8rem', fontWeight: 600, color: C.text2,
          transition: 'background 0.15s, border-color 0.15s',
          letterSpacing: '0.01em',
        }}
        onMouseEnter={e => (e.currentTarget.style.background = '#eef2f6')}
        onMouseLeave={e => (e.currentTarget.style.background = C.bgSubtle)}
      >
        <span style={{
          width: 14, height: 14, display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
          color: C.textMuted, flexShrink: 0,
          transition: 'transform 0.2s ease', transform: open ? 'rotate(90deg)' : 'rotate(0)',
        }}>
          <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
        </span>
        {icon && <span style={{
          width: 16, height: 16, display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
          flexShrink: 0, color: C.textLight,
        }}>{icon}</span>}
        <span style={{ flex: 1 }}>{title}</span>
        {badge}
      </div>
      {open && <div>{children}</div>}
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════
   JSON Viewer
   ═══════════════════════════════════════════════════════════ */
function JsonViewer({ text }: { text: string }) {
  let parsed: unknown;
  let valid = true;
  try { parsed = JSON.parse(text); } catch { valid = false; }

  if (!valid) {
    return (
      <pre style={{
        margin: 0, padding: '12px 14px', fontFamily: C.mono,
        fontSize: '0.78rem', lineHeight: 1.6, color: C.text,
        whiteSpace: 'pre-wrap', wordBreak: 'break-word',
      }}>{text}</pre>
    );
  }

  const render = (val: unknown, depth = 0): React.ReactNode => {
    const indent = '  '.repeat(depth);
    if (val === null) return <span style={{ color: JC.null }}>null</span>;
    if (typeof val === 'boolean') return <span style={{ color: JC.bool }}>{val ? 'true' : 'false'}</span>;
    if (typeof val === 'number') return <span style={{ color: JC.number }}>{val}</span>;
    if (typeof val === 'string') return <span style={{ color: JC.string }}>"{val}"</span>;
    if (Array.isArray(val)) {
      if (val.length === 0) return <><span style={{ color: JC.bracket }}>[]</span></>;
      return (
        <>
          <span style={{ color: JC.bracket }}>[</span>
          {val.map((item, i) => (
            <div key={i} style={{ paddingLeft: 16 }}>
              {render(item, depth + 1)}
              {i < val.length - 1 && <span style={{ color: JC.bracket }}>,</span>}
            </div>
          ))}
          <div style={{ paddingLeft: depth * 16 }}><span style={{ color: JC.bracket }}>]</span></div>
        </>
      );
    }
    if (typeof val === 'object') {
      const entries = Object.entries(val as Record<string, unknown>);
      if (entries.length === 0) return <><span style={{ color: JC.bracket }}>{'{ }'}</span></>;
      return (
        <>
          <span style={{ color: JC.bracket }}>{'{'}</span>
          {entries.map(([k, v], i) => (
            <div key={k} style={{ paddingLeft: 16 }}>
              <span style={{ color: JC.key }}>"{k}"</span>
              <span style={{ color: JC.bracket }}>: </span>
              {render(v, depth + 1)}
              {i < entries.length - 1 && <span style={{ color: JC.bracket }}>,</span>}
            </div>
          ))}
          <div style={{ paddingLeft: depth * 16 }}><span style={{ color: JC.bracket }}>{'}'}</span></div>
        </>
      );
    }
    return <span>{String(val)}</span>;
  };

  return (
    <pre style={{
      margin: 0, padding: '12px 14px', fontFamily: C.mono,
      fontSize: '0.78rem', lineHeight: 1.7, color: C.text,
      whiteSpace: 'pre', overflow: 'auto', maxHeight: '50vh',
      background: '#fafbfc',
    }}>{render(parsed)}</pre>
  );
}

/* ═══════════════════════════════════════════════════════════
   ASCII table → markdown table (for Python stdout)
   ═══════════════════════════════════════════════════════════ */
function isAsciiSep(line: string): boolean {
  const t = line.trim();
  return t.length >= 3 && /^[\s\-+:=]+$/.test(t) && t.includes('-');
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
    if (isAsciiSep(lines[i]) && i > 0) {
      const start = i - 1;
      let end = start + 2;
      while (end < lines.length && lines[end].trim() !== '') end++;
      const tableLines = lines.slice(start, end);
      const md = toMarkdownTable(tableLines);
      if (md) { out.push('', md, ''); i = end; continue; }
    }
    out.push(lines[i]);
    i++;
  }
  return out.join('\n').replace(/\n{3,}/g, '\n\n').trim();
}

/* ═══════════════════════════════════════════════════════════
   Tool-specific detail components
   ═══════════════════════════════════════════════════════════ */

function RenderChartDetail({ data }: { data: Record<string, unknown> }) {
  const chartType = data.chartType as string;
  const title = data.title as string;
  const chartId = data.chartId as string;
  return (
    <Section title="图表配置" icon={
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M18 20V10"/><path d="M12 20V4"/><path d="M6 20v-6"/></svg>
    } defaultOpen>
      <div style={{ padding: '10px 14px', display: 'flex', flexDirection: 'column', gap: 8 }}>
        {title && <div style={{ fontSize: '0.88rem', fontWeight: 700, color: C.text }}>{title}</div>}
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {chartType && (
            <span style={{ fontSize: '0.72rem', fontWeight: 600, padding: '2px 8px', borderRadius: 4, color: C.text2, background: C.bgSunken }}>{chartType}</span>
          )}
          {chartId && (
            <span style={{ fontSize: '0.68rem', fontFamily: C.mono, padding: '2px 8px', borderRadius: 4, color: C.textMuted, background: C.bgSubtle }}>{chartId}</span>
          )}
        </div>
      </div>
    </Section>
  );
}

function unescapeText(s: string): string {
  const slash = String.fromCharCode(92);
  const lineFeed = String.fromCharCode(10);
  return s
    .split(`${slash}r${slash}n`).join(lineFeed)
    .split(`${slash}n`).join(lineFeed)
    .split(`${slash}t`).join('  ')
    .split(`${slash}"`).join('"');
}

function RetrieveEvidenceDetail({ result }: { result: string }) {
  let content = unescapeText(result);
  const headerMatch = content.match(/^##\s+检索结果\s*\n?/m);
  if (headerMatch) content = content.slice(headerMatch[0].length).trim();
  return (
    <Section title="知识内容" icon={
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
    } defaultOpen>
      <div style={{ padding: '10px 14px', maxHeight: 400, overflowY: 'auto' }}>
        {content ? <Markdown>{content}</Markdown> : (
          <div style={{ color: C.textMuted, fontStyle: 'italic' }}>无相关内容</div>
        )}
      </div>
    </Section>
  );
}

function extractPythonCode(input?: string): string | null {
  if (!input) return null;
  try { const p = JSON.parse(input); return typeof p.code === 'string' ? p.code : null; } catch { return null; }
}



function ToolDetail({ name, result }: { name: string; result: string }) {
  let parsed: Record<string, unknown> | null = null;
  let truncatedJson = false;
  try {
    const v = JSON.parse(result);
    if (v && typeof v === 'object' && !Array.isArray(v)) parsed = v as Record<string, unknown>;
  } catch {
    const trimmed = result.trimStart();
    if (trimmed.startsWith('{') || trimmed.startsWith('[')) truncatedJson = true;
  }

  if (name === 'wren_run_sql' || name === 'wren_query_cube' || name === 'wren_describe_model') {
    return (
      <div style={{ padding: '10px 14px', maxHeight: 500, overflowY: 'auto' }}>
        <Markdown>{unescapeText(result)}</Markdown>
      </div>
    );
  }
  if (name === 'render_chart' && parsed) return <RenderChartDetail data={parsed} />;
  if (truncatedJson) {
    return <div style={{ padding: '12px', fontSize: '0.8rem', color: '#92400e', background: C.warnBg, borderRadius: 8 }}>结果数据过大，历史记录中已截断。重新执行该工具可查看完整结果。</div>;
  }
  if (name === 'retrieve_evidence' || name === 'read_knowledge') return <RetrieveEvidenceDetail result={result} />;
  return (
    <div style={{ padding: '10px 14px', maxHeight: 400, overflowY: 'auto' }}>
      <Markdown>{formatResult(result)}</Markdown>
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════
   Tool name → icon + color mapping
   ═══════════════════════════════════════════════════════════ */
const TOOL_META: Record<string, { label: string; icon: React.ReactNode; color: string; badgeBg: string }> = {
  wren_run_sql:           { label: '查询语义模型', color: '#4f46e5', badgeBg: '#eef2ff', icon: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <ellipse cx="11" cy="5" rx="8" ry="2.5"/><path d="M3 5v14c0 1.38 3.58 2.5 8 2.5s8-1.12 8-2.5V5"/>
      <path d="M19 10v5c0 1.38-3.58 2.5-8 2.5"/>
    </svg>
  )},
  wren_query_cube:        { label: '查询业务指标', color: '#4f46e5', badgeBg: '#eef2ff', icon: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>
    </svg>
  )},
  wren_describe_model:    { label: '查看语义模型', color: '#9333ea', badgeBg: '#faf5ff', icon: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>
    </svg>
  )},
  read_knowledge:         { label: '读取业务规则', color: '#7c3aed', badgeBg: '#f5f3ff', icon: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/>
    </svg>
  )},
  fetch_query_result:     { label: '复用查询结果', color: '#0d9488', badgeBg: '#f0fdfa', icon: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 2v6h-6"/><path d="M3 12a9 9 0 0 1 15-6.7L21 8"/>
      <path d="M3 22v-6h6"/><path d="M21 12a9 9 0 0 1-15 6.7L3 16"/>
    </svg>
  )},
  render_chart:           { label: '生成图表', color: '#d97706', badgeBg: '#fffbeb', icon: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 3v18h18"/><rect x="7" y="13" width="3" height="5" rx="0.5"/><rect x="12" y="9" width="3" height="9" rx="0.5"/><rect x="17" y="6" width="3" height="12" rx="0.5"/>
    </svg>
  )},
  run_python:             { label: '执行 Python', color: '#2563eb', badgeBg: '#eff6ff', icon: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/>
    </svg>
  )},
  run_sql:                { label: '执行 SQL', color: '#dc2626', badgeBg: '#fef2f2', icon: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M4 7h16M4 12h16M4 17h10"/>
    </svg>
  )},
  retrieve_evidence:      { label: '检索证据', color: '#0891b2', badgeBg: '#ecfeff', icon: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>
    </svg>
  )},
};

function typeLabel(type: string): string {
  switch (type) { case 'image': return '图片'; case 'svg': return '矢量图'; case 'csv': return '数据'; case 'text': return '文档'; default: return type; }
}

function PythonArtifactsView({ tools }: { tools: { input?: string; result?: string }[] }) {
  const [lightbox, setLightbox] = useState<string | null>(null);
  const [failedImgs, setFailedImgs] = useState<Set<number>>(() => new Set());
  const allArtifacts = React.useMemo(() => {
    const result: ArtifactInfo[] = [];
    for (const t of tools) {
      if (!t.result) continue;
      try { result.push(...parseResult(t.result).artifacts); } catch { /* skip */ }
    }
    return deduplicateArtifacts(result);
  }, [tools]);

  return (
    <>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {allArtifacts.map((a, i) => {
          const src = (a.type === 'image' || a.type === 'svg') ? artifactSrc(a) : null;
          const failed = failedImgs.has(i);
          return (
            <div key={i} style={{
              display: 'flex', alignItems: 'center', gap: 10,
              padding: '8px 12px', borderBottom: `1px solid ${C.borderLight}`,
            }}>
              {src && !failed ? (
                <AttachmentImage src={src} alt={a.name} style={{
                  width: 44, height: 44, objectFit: 'cover', borderRadius: 6,
                  border: `1px solid ${C.border}`, cursor: 'zoom-in', flexShrink: 0,
                }} onClick={() => setLightbox(src)}
                  onError={() => setFailedImgs(prev => new Set(prev).add(i))} />
              ) : src ? (
                <span title="图片加载失败，请重新登录后重试" style={{
                  width: 44, height: 44, display: 'flex', alignItems: 'center', justifyContent: 'center',
                  flexShrink: 0, borderRadius: 6, border: `1px dashed ${C.border}`,
                  color: C.textMuted, fontSize: '0.6rem', textAlign: 'center', lineHeight: 1.2,
                }}>
                  加载<br/>失败
                </span>
              ) : (
                <span style={{ width: 44, height: 44, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, color: C.textMuted }}>
                  {a.type === 'csv' ? (
                    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18"/><path d="M9 21V9"/></svg>
                  ) : (
                    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>
                  )}
                </span>
              )}
              <span style={{ flex: 1, minWidth: 0, wordBreak: 'break-all', fontSize: '0.82rem', color: C.text }}>
                {a.name}
                <span style={{ color: C.textMuted, fontSize: '0.72rem', marginLeft: 4 }}>
                  ({typeLabel(a.type)}, {(a.size / 1024).toFixed(1)} KB)
                </span>
              </span>
              <button type="button" onClick={() => downloadArtifact(a)} title={`下载 ${a.name}`} style={{
                border: `1px solid ${C.border}`, background: C.bg, color: C.text2,
                borderRadius: 6, padding: '3px 10px', fontSize: '0.74rem', cursor: 'pointer', flexShrink: 0,
                display: 'inline-flex', alignItems: 'center', gap: 4,
              }}>
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                下载
              </button>
            </div>
          );
        })}
      </div>
      {lightbox && createPortal(
        <div style={{
          position: 'fixed', inset: 0, zIndex: 9999, background: 'rgba(0,0,0,0.75)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'zoom-out', padding: 24,
        }} onClick={() => setLightbox(null)}>
          <AttachmentImage src={lightbox} alt="" style={{ maxWidth: '92vw', maxHeight: '92vh', borderRadius: 8, boxShadow: '0 8px 32px rgba(0,0,0,0.4)' }} />
        </div>, document.body,
      )}
    </>
  );
}

/* ═══════════════════════════════════════════════════════════
   Main ToolInspector
   ═══════════════════════════════════════════════════════════ */
export default function ToolInspector({ tool, onClose }: ToolInspectorProps) {
  const isPythonArtifacts = tool.id === '__python_artifacts__';
  const meta = TOOL_META[tool.name] ?? { label: tool.name, color: '#64748b', badgeBg: '#f1f5f9', icon: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>
    </svg>
  )};
  const title = isPythonArtifacts
    ? `生成产物（${(() => { let c = 0; for (const t of tool.pythonTools ?? []) { if (!t.result) continue; try { c += parseResult(t.result).artifacts.length; } catch {/* */} } return c; })()} 个文件）`
    : meta.label;
  const isError = !isPythonArtifacts && tool.result?.startsWith('error:') === true;

  const [inputOpen, setInputOpen] = useState(false);
  const [resultOpen, setResultOpen] = useState(true);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      {/* ── Header ── */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 10,
        padding: '12px 16px', flexShrink: 0,
        background: '#fff',
        borderBottom: `1px solid ${C.border}`,
      }}>
        <span style={{
          width: 30, height: 30, borderRadius: 8,
          background: meta.badgeBg,
          display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
          flexShrink: 0, color: meta.color,
        }}>{meta.icon}</span>
        <span style={{
          flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          fontSize: '0.85rem',
          fontWeight: 600, color: C.text,
        }}>{title}</span>
        {isError && (
          <span style={{
            fontSize: '0.7rem', fontWeight: 600, padding: '2px 8px', borderRadius: 4,
            background: C.errBg, color: C.err,
          }}>失败</span>
        )}
        <button type="button" onClick={onClose} title="关闭" aria-label="关闭检查器" style={{
          width: 26, height: 26, border: 'none', background: 'transparent',
          borderRadius: 6, color: C.textMuted, cursor: 'pointer', display: 'inline-flex',
          alignItems: 'center', justifyContent: 'center', flexShrink: 0,
          transition: 'background 0.15s, color 0.15s',
        }}
          onMouseEnter={e => { e.currentTarget.style.background = C.bgSunken; e.currentTarget.style.color = C.text2; }}
          onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = C.textMuted; }}
        >
          <Icon name="close" size="sm" />
        </button>
      </div>

      {/* ── Body ── */}
      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '12px 14px', display: 'flex', flexDirection: 'column', gap: 10 }}>
        {isPythonArtifacts ? (
          <PythonArtifactsView tools={tool.pythonTools ?? []} />
        ) : (
          <>
            {/* Input */}
            {tool.input && (
              <Section
                title="输入"
                icon={
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
                }
                defaultOpen={inputOpen}
                badge={
                  <span style={{ fontSize: '0.68rem', color: C.textMuted, fontWeight: 400 }}>
                    {(() => { try { return Object.keys(JSON.parse(tool.input!)).length; } catch { return 0; } })()} 个字段
                  </span>
                }
              >
                <JsonViewer text={tool.input} />
              </Section>
            )}

            {/* Result - expand to fill available space */}
            {tool.result !== undefined ? (
              <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
                <Section title="结果" icon={
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                } defaultOpen={resultOpen}>
                  <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
                    {tool.name === 'run_python'
                      ? (() => {
                          const code = extractPythonCode(tool.input);
                          return code
                            ? <PythonCodeBlock code={code} result={tool.result} defaultOpen />
                            : <ToolDetail key={tool.id} name={tool.name} result={tool.result} />;
                        })()
                      : <ToolDetail key={tool.id} name={tool.name} result={tool.result} />}
                  </div>
                </Section>
              </div>
            ) : (
              <div style={{ color: C.textMuted, fontSize: '0.85rem', fontStyle: 'italic', padding: '8px 0' }}>运行中…</div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
