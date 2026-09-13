import React, { useState } from 'react';
import { ACTIVE_AGENT_ID } from '../api/activeAgent';
import { getToken } from '../api/auth';

interface ArtifactInfo {
  name: string;
  type: 'image' | 'csv' | 'text' | 'svg';
  size: number;
  contentB64?: string;
  content?: string;
  /** Workspace-relative path for images served via the binary API. */
  path?: string;
}

interface Props {
  code: string;
  result?: string;
  defaultOpen?: boolean;
}

const s: Record<string, React.CSSProperties> = {
  wrapper: {
    background: '#ffffff',
    border: '1px solid #e2e8f0',
    borderRadius: 9,
    margin: '0.5rem 0',
    overflow: 'hidden',
    fontSize: '0.9rem',
  },
  header: {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    padding: '0.6rem 0.9rem',
    cursor: 'pointer',
    userSelect: 'none',
    background: '#fefce8',
    borderBottom: '1px solid #e2e8f0',
    color: '#854d0e',
    fontWeight: 600,
    fontSize: '0.85rem',
  },
  arrow: { color: '#94a3b8', fontSize: '0.72rem', fontWeight: 700 },
  codeBlock: {
    padding: '0.85rem 1rem',
    borderTop: '1px solid #e2e8f0',
    background: '#f8fafc',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.82rem',
    lineHeight: 1.55,
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
    color: '#1e293b',
    maxHeight: 400,
    overflowY: 'auto',
  },
  stdoutBlock: {
    padding: '0.85rem 1rem',
    borderTop: '1px solid #e2e8f0',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.82rem',
    lineHeight: 1.55,
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
    color: '#475569',
    maxHeight: 300,
    overflowY: 'auto',
  },
  artifactContainer: {
    padding: '0.85rem 1rem',
    borderTop: '1px solid #e2e8f0',
  },
  artifactImage: {
    maxWidth: '100%',
    borderRadius: 6,
    border: '1px solid #e2e8f0',
    marginTop: 8,
  },
  csvTable: {
    width: '100%',
    borderCollapse: 'collapse' as const,
    fontSize: '0.82rem',
    marginTop: 8,
  },
  csvTh: {
    background: '#f1f5f9',
    padding: '6px 10px',
    textAlign: 'left' as const,
    borderBottom: '2px solid #e2e8f0',
    fontWeight: 600,
    color: '#334155',
  },
  csvTd: {
    padding: '5px 10px',
    borderBottom: '1px solid #f1f5f9',
    color: '#475569',
  },
  label: {
    color: '#94a3b8',
    fontSize: '0.72rem',
    fontWeight: 600,
    letterSpacing: '0.06em',
    textTransform: 'uppercase' as const,
    marginBottom: 6,
    fontFamily: 'ui-sans-serif, system-ui, sans-serif',
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
  },
  errorBlock: {
    padding: '0.85rem 1rem',
    borderTop: '1px solid #e2e8f0',
    color: '#b91c1c',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.82rem',
    whiteSpace: 'pre-wrap' as const,
  },
};

/**
 * Parses the structured markdown result from RunPythonTool to extract
 * stdout, exit code, and artifact metadata (including base64 images).
 *
 * Handles both real newlines (from live SSE stream) and literal `\n`
 * sequences (which may appear when the result is double-escaped during
 * session-history serialization or SSE transport).
 */
function parseResult(result: string): {
  exitCode: number;
  stdout: string;
  artifacts: ArtifactInfo[];
} {
  // Normalize literal \n (backslash + n) to real newlines when the result
  // has no real newlines but does contain escaped ones.  This can happen
  // when the tool-result text is double-JSON-encoded somewhere in the
  // storage or transport pipeline.
  const hasRealNewlines = result.includes('\n');
  const hasLiteralEscapes = /\\n/.test(result);
  if (!hasRealNewlines && hasLiteralEscapes) {
    result = result.replace(/\\n/g, '\n');
  }

  let exitCode = 0;
  let stdout = '';
  const artifacts: ArtifactInfo[] = [];

  // Extract exit code
  const exitMatch = result.match(/### exit_code: (\d+)/);
  if (exitMatch) exitCode = parseInt(exitMatch[1], 10);

  // Extract stdout
  const stdoutMatch = result.match(/### stdout\n```\n([\s\S]*?)```/);
  if (stdoutMatch) stdout = stdoutMatch[1];

  // Extract artifacts
  const artifactSections = result.split(/### artifact:/);
  for (let i = 1; i < artifactSections.length; i++) {
    const section = artifactSections[i];
    // Capture the filename: everything up to the first newline.
    // Using [^\n]+ instead of \S+ to avoid matching across lines when
    // the section text still contains literal \n sequences.
    const nameMatch = section.match(/^([^\n]+)/);
    const typeMatch = section.match(/- type: (\S+)/);
    const sizeMatch = section.match(/- size: (\d+)/);
    const b64Match = section.match(/- content_b64: (\S+)/);
    const pathMatch = section.match(/- path: ([^\n]+)/);
    const textMatch = section.match(/- content:\n```\n([\s\S]*?)```/);

    if (nameMatch) {
      const rawName = nameMatch[1].trim();
      // Skip if the name looks corrupted (contains metadata markers)
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

/** Renders a CSV string as a simple HTML table. */
function CsvTable({ content }: { content: string }) {
  const lines = content.split('\n').filter(l => l.trim());
  if (lines.length === 0) return null;
  const headers = lines[0].split(',').map(h => h.trim().replace(/^"|"$/g, ''));
  const rows = lines.slice(1).map(line =>
    line.split(',').map(cell => cell.trim().replace(/^"|"$/g, ''))
  );

  return (
    <table style={s.csvTable}>
      <thead>
        <tr>
          {headers.map((h, i) => (
            <th key={i} style={s.csvTh}>{h}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row, ri) => (
          <tr key={ri}>
            {row.map((cell, ci) => (
              <td key={ci} style={s.csvTd}>{cell}</td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** Returns the MIME type for a data URI based on the file extension. */
function imageMimeType(name: string): string {
  const lower = name.toLowerCase();
  if (lower.endsWith('.svg')) return 'image/svg+xml';
  if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return 'image/jpeg';
  return 'image/png';
}

/** Build the src URL for an artifact image (base64 inline or binary API). */
function artifactSrc(a: ArtifactInfo): string | null {
  if (a.contentB64) return `data:${imageMimeType(a.name)};base64,${a.contentB64}`;
  if (a.path) {
    const token = getToken();
    const base = `/api/agents/${ACTIVE_AGENT_ID}/workspace/file/binary?path=${encodeURIComponent(a.path)}`;
    return token ? `${base}&token=${encodeURIComponent(token)}` : base;
  }
  return null;
}

/**
 * Triggers a browser download for an artifact. Prefers the sandbox file
 * (full, non-truncated content) via the binary API with
 * `Content-Disposition: attachment`; falls back to the inline content
 * captured in the tool result for legacy sessions without a path.
 */
function downloadArtifact(a: ArtifactInfo) {
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
  // Legacy fallback: download the inline content as a Blob.
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

/**
 * Renders Python code, execution output, and generated artifacts
 * (PNG charts as inline images, CSV as tables) in the chat stream.
 */
export default function PythonCodeBlock({ code, result, defaultOpen = true }: Props) {
  const [open, setOpen] = useState(defaultOpen);
  const [lightbox, setLightbox] = useState<string | null>(null);
  const parsed = result ? parseResult(result) : null;
  const hasError = parsed && parsed.exitCode !== 0;

  // Debug logging
  if (result) {
    const hasReal = result.includes('\n');
    const hasLiteral = /\\n/.test(result);
    console.log(`[PythonCodeBlock] raw result: len=${result.length}, hasRealNewlines=${hasReal}, hasLiteralEscapes=${hasLiteral}`);
    console.log('[PythonCodeBlock] raw result preview:', result.substring(0, 500));
    if (parsed) {
      console.log('[PythonCodeBlock] parsed artifacts:', parsed.artifacts.length, parsed.artifacts);
      parsed.artifacts.forEach((a, i) => {
        console.log(`[PythonCodeBlock] artifact[${i}]:`, JSON.stringify(a));
      });
    }
  }

  return (
    <div style={s.wrapper}>
      <div style={s.header} onClick={() => setOpen(o => !o)}>
        <span>🐍</span>
        <span>Python 代码执行</span>
        {parsed && (
          <span style={{ color: hasError ? '#b91c1c' : '#16a34a', fontWeight: 500, fontSize: '0.8rem' }}>
            {hasError ? `exit: ${parsed.exitCode}` : '✓ 执行成功'}
          </span>
        )}
        {parsed && parsed.artifacts.length > 0 && (
          <span style={{ color: '#854d0e', fontSize: '0.78rem' }}>
            {parsed.artifacts.length} 个产物
          </span>
        )}
        <span style={{ flex: 1 }} />
        <span style={s.arrow}>{open ? '▼' : '▶'}</span>
      </div>
      {open && (
        <>
          {/* Python source code */}
          <div style={s.codeBlock}>
            <div style={s.label}>Python</div>
            {code}
          </div>

          {/* stdout */}
          {parsed && parsed.stdout && (
            <div style={s.stdoutBlock}>
              <div style={s.label}>输出</div>
              {parsed.stdout}
            </div>
          )}

          {/* Error output */}
          {hasError && parsed && parsed.stdout && (
            <div style={s.errorBlock}>
              <div style={s.label}>错误输出</div>
              {parsed.stdout}
            </div>
          )}

          {/* Artifacts */}
          {parsed && parsed.artifacts.length > 0 && (
            <div style={s.artifactContainer}>
              <div style={s.label}>生成产物</div>
              {parsed.artifacts.map((a, i) => (
                <div key={i} style={{ marginBottom: 12 }}>
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 8,
                      fontSize: '0.78rem',
                      color: '#64748b',
                      marginBottom: 4,
                    }}
                  >
                    <span style={{ flex: 1, minWidth: 0, wordBreak: 'break-all' }}>
                      📎 {a.name} ({a.type}, {(a.size / 1024).toFixed(1)} KB)
                    </span>
                    <button
                      onClick={e => {
                        e.stopPropagation();
                        downloadArtifact(a);
                      }}
                      style={s.downloadBtn}
                      title={`下载 ${a.name}`}
                    >
                      ⬇ 下载
                    </button>
                  </div>
                  {(a.type === 'image' || a.type === 'svg') && (() => {
                    const src = artifactSrc(a);
                    return src ? (
                      <img
                        src={src}
                        alt={a.name}
                        style={{ ...s.artifactImage, cursor: 'zoom-in' }}
                        onClick={() => setLightbox(src)}
                        onError={(e) => {
                          const img = e.currentTarget;
                          img.style.opacity = '0.3';
                          img.style.border = '1px dashed #cbd5e1';
                          img.title = `Image load failed: ${a.name}`;
                          console.error('[PythonCodeBlock] image load failed:', src);
                        }}
                      />
                    ) : null;
                  })()}
                  {a.type === 'csv' && a.content && <CsvTable content={a.content} />}
                  {a.type === 'text' && a.content && (
                    <div style={{ ...s.stdoutBlock, border: '1px solid #e2e8f0', borderRadius: 6, marginTop: 8 }}>
                      {a.content}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </>
      )}
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
    </div>
  );
}
