import React from 'react';
import Icon from './Icon';
import Markdown from './Markdown';
import { formatResult, prettyInput, ToolInspectPayload } from './ToolCallBlock';
import { downloadQueryCsv } from '../api/datasets';

export interface ToolInspectorProps {
  tool: ToolInspectPayload;
  onClose: () => void;
}

type ColKind = 'number' | 'date' | 'boolean' | 'text';

function classifyJdbcType(t: string | undefined): ColKind {
  if (!t) return 'text';
  const u = t.toUpperCase();
  if (u.includes('INT') || u.includes('NUMERIC') || u.includes('DECIMAL')
      || u.includes('FLOAT') || u.includes('DOUBLE') || u.includes('REAL')
      || u === 'NUMBER' || u === 'MONEY' || u === 'SMALLMONEY') return 'number';
  if (u.includes('DATE') || u.includes('TIME') || u === 'TIMESTAMP') return 'date';
  if (u === 'BIT' || u === 'BOOLEAN') return 'boolean';
  return 'text';
}

function cellClassName(kind: ColKind): string {
  switch (kind) {
    case 'number': return ' da-sem-cell-num';
    case 'date': return ' da-sem-cell-date';
    case 'boolean': return ' da-sem-cell-bool';
    default: return '';
  }
}

function ResultTable({ columns, columnTypes, rows }: {
  columns: string[];
  columnTypes?: string[];
  rows: (string | null)[][];
}) {
  const kinds = columns.map((_, i) => classifyJdbcType(columnTypes?.[i]));
  return (
    <div className="da-sem-table-wrap">
      <table className="da-sem-table">
        <thead>
          <tr>
            {columns.map((c, i) => (
              <th key={i} className={cellClassName(kinds[i]).trim() || undefined}>{c}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, ri) => (
            <tr key={ri}>
              {row.map((cell, ci) => (
                <td key={ci} className={cellClassName(kinds[ci]).trim() || undefined}>
                  {cell === null ? <span className="da-sem-null">NULL</span> : cell}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

interface QueryResultItem {
  artifactId: string;
  sql: string;
  purpose: string;
  columns: string[];
  columnTypes?: string[];
  rows: (string | null)[][];
  rowCount: number;
  truncated: boolean;
  truncationReason?: string;
  elapsedMs: number;
}

function QueryDownload({ artifactId, truncated }: { artifactId: string; truncated: boolean }) {
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState('');
  const download = async () => {
    setBusy(true);
    setError('');
    try { await downloadQueryCsv(artifactId); }
    catch (err) { setError(err instanceof Error ? err.message : '下载失败，请重试'); }
    finally { setBusy(false); }
  };
  return <div>
    <button type="button" className="da-btn da-btn-ghost" disabled={busy} onClick={download}>
      {busy ? '下载中…' : truncated ? '下载已保存部分 CSV' : '下载结果 CSV'}
    </button>
    {error && <div role="alert" className="da-sem-warn">{error}</div>}
  </div>;
}

function QueryStructuredDetail({ data }: { data: Record<string, unknown> }) {
  const taskId = data.taskId as string;
  const status = data.status as string;
  const error = data.error as string;
  const results = (data.results as QueryResultItem[]) ?? [];
  const steps = (data.steps as { callId: string; name: string; sql?: string; status: string; summary?: string }[]) ?? [];

  return (
    <div className="da-sem-section">
      <div className="da-sem-meta">
        <span className={`da-chip ${status === 'SUCCEEDED' ? 'da-chip-ok' : status === 'PARTIAL' ? 'da-chip-warn' : 'da-chip-err'}`}>
          {status === 'SUCCEEDED' ? '查询完成' : status === 'PARTIAL' ? '部分完成' : '查询失败'}
        </span>
        {taskId && <span className="da-sem-meta-item">任务 {taskId}</span>}
        <span className="da-sem-meta-item">{results.length} 份已保存结果</span>
      </div>
      {error && <div role="alert" className="da-sem-warn">{error}</div>}
      {steps.length > 0 && <details>
        <summary>执行过程（{steps.length} 步，含纠错记录）</summary>
        {steps.map(step => <div key={step.callId} className="da-sem-section">
          <div className="da-sem-meta">
            <span className="da-chip">{step.name === 'run_sql' ? '执行 SQL' : '准备字段与规则'}</span>
            <span>{step.status === 'SUCCEEDED' ? '完成' : step.status === 'CANCELLED' ? '中断' : '失败'}</span>
          </div>
          {step.summary && <p>{step.summary}</p>}
          {step.sql && <pre className="da-sem-sql-pre">{step.sql}</pre>}
        </div>)}
      </details>}
      {results.map(r => (
        <div key={r.artifactId} className="da-sem-section" style={{ marginTop: 8 }}>
          <div className="da-sem-meta">
            <span className="da-chip">{r.purpose}</span>
            <span className="da-sem-meta-item">已保存 {r.rowCount} 行 × {r.columns.length} 列{r.truncated ? ' · 结果截断' : ''}</span>
            {r.elapsedMs > 0 && <span className="da-sem-meta-item">{r.elapsedMs}ms</span>}
            <span className="da-sem-meta-item" style={{ fontFamily: 'monospace', fontSize: '0.7rem' }}>{r.artifactId}</span>
          </div>
          {r.truncated && <div className="da-sem-truncation">达到查询结果预算，不能当作完整数据。{r.truncationReason}</div>}
          <p className="da-sem-meta-item">显示前 {r.rows.length} 行，长单元格已缩略；下载包含全部已保存内容。</p>
          <QueryDownload artifactId={r.artifactId} truncated={r.truncated} />
          <details style={{ marginTop: 4 }}>
            <summary style={{ fontSize: '0.75rem', color: 'var(--da-text-muted)', cursor: 'pointer' }}>SQL</summary>
            <pre className="da-sem-sql-pre">{r.sql}</pre>
          </details>
          {r.columns.length > 0 && r.rows.length > 0 && (
            <ResultTable columns={r.columns} columnTypes={r.columnTypes} rows={r.rows} />
          )}
          {r.rowCount === 0 && <div className="da-sem-empty">查询结果为空</div>}
        </div>
      ))}
    </div>
  );
}

function RenderChartDetail({ data }: { data: Record<string, unknown> }) {
  const chartType = data.chartType as string;
  const title = data.title as string;
  const chartId = data.chartId as string;
  return (
    <div className="da-sem-section">
      <div className="da-sem-meta">
        {chartType && <span className="da-chip">{chartType}</span>}
        {title && <span className="da-sem-meta-item">{title}</span>}
        {chartId && <span className="da-sem-meta-item" style={{ fontFamily: 'monospace', fontSize: '0.7rem' }}>{chartId}</span>}
      </div>
    </div>
  );
}

function ToolDetail({ name, result }: { name: string; result: string }) {
  let parsed: Record<string, unknown> | null = null;
  try {
    const v = JSON.parse(result);
    if (v && typeof v === 'object' && !Array.isArray(v)) parsed = v as Record<string, unknown>;
  } catch { /* not JSON */ }

  if (name === 'query_structured_data' && parsed) {
    return <QueryStructuredDetail data={parsed} />;
  }
  if (name === 'render_chart' && parsed) {
    return <RenderChartDetail data={parsed} />;
  }
  return <Markdown>{formatResult(result)}</Markdown>;
}

const TC_TOOL_LABELS: Record<string, string> = {
  query_structured_data: '查询数据',
  read_knowledge: '读取业务规则',
  fetch_query_result: '复用查询结果',
  render_chart: '生成图表',
  run_python: '执行 Python',
  prepare_data_context: '准备字段与规则',
  run_sql: '执行 SQL',
};

export default function ToolInspector({ tool, onClose }: ToolInspectorProps) {
  const title = TC_TOOL_LABELS[tool.name] ?? tool.name;
  const isError = tool.result !== undefined
    ? (tool.name === 'query_structured_data'
      ? (() => { try { return (JSON.parse(tool.result) as Record<string, unknown>)?.status === 'FAILED'; } catch { return false; } })()
      : tool.result.startsWith('error:'))
    : false;

  return (
    <div style={S.root}>
      <div style={S.head}>
        <span style={{ color: 'var(--da-text-muted)', display: 'inline-flex' }}>
          <Icon name="code" size="sm" />
        </span>
        <span style={S.name}>{title}</span>
        {isError && <span className="da-sem-err-badge">失败</span>}
        <button type="button" style={S.close} onClick={onClose} title="关闭" aria-label="关闭检查器">
          <Icon name="close" size="sm" />
        </button>
      </div>
      <div style={S.body}>
        {tool.input && (
          <>
            <div style={S.label}>输入</div>
            <div className="da-toolcall-body" style={S.inputBox}>{prettyInput(tool.input)}</div>
          </>
        )}
        {tool.result !== undefined ? (
          <>
            <div style={S.label}>结果</div>
            <div className="da-toolcall-result" style={S.resultBox}>
              <ToolDetail key={tool.id} name={tool.name} result={tool.result} />
            </div>
          </>
        ) : (
          <div style={S.pending}>运行中…</div>
        )}
      </div>
    </div>
  );
}

const S: Record<string, React.CSSProperties> = {
  root: { display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 },
  head: {
    display: 'flex', alignItems: 'center', gap: 8,
    padding: '10px 12px', borderBottom: '1px solid var(--da-border)', flexShrink: 0,
  },
  name: {
    flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
    fontFamily: 'var(--da-font)', fontSize: '0.85rem', fontWeight: 600, color: 'var(--da-text-2)',
  },
  close: {
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
    width: 26, height: 26, border: 'none', background: 'transparent', borderRadius: 6,
    color: 'var(--da-text-muted)', cursor: 'pointer', flexShrink: 0,
  },
  body: { flex: 1, minHeight: 0, overflowY: 'auto', padding: '10px 12px', display: 'flex', flexDirection: 'column', gap: 8 },
  label: {
    fontSize: '0.7rem', fontWeight: 600, letterSpacing: '0.06em', textTransform: 'uppercase',
    color: 'var(--da-text-muted)',
  },
  inputBox: { maxHeight: '40%', background: 'var(--da-surface-sunken)', borderRadius: 6 },
  resultBox: { background: 'var(--da-surface-sunken)', borderRadius: 6, maxHeight: 'none' },
  pending: { color: 'var(--da-text-muted)', fontSize: '0.85rem', fontStyle: 'italic' },
};
