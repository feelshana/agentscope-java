import React, { useState } from 'react';
import Icon from './Icon';
import Markdown from './Markdown';
import { formatResult, prettyInput, ToolInspectPayload } from './ToolCallBlock';
import {
  parseSemantic,
  isSemanticTool,
  stageSummary,
  type SemanticResult,
} from '../utils/semanticTools';

export interface ToolInspectorProps {
  tool: ToolInspectPayload;
  onClose: () => void;
}

function SqlTabs({ semantic, compiled }: { semantic: string; compiled?: string }) {
  const [tab, setTab] = useState<'semantic' | 'compiled'>('semantic');
  return (
    <div className="da-sem-sql-tabs">
      <div className="da-sem-tab-bar">
        <button
          type="button"
          className={`da-sem-tab${tab === 'semantic' ? ' active' : ''}`}
          onClick={() => setTab('semantic')}
        >
          语义 SQL
        </button>
        {compiled && (
          <button
            type="button"
            className={`da-sem-tab${tab === 'compiled' ? ' active' : ''}`}
            onClick={() => setTab('compiled')}
          >
            编译 SQL
          </button>
        )}
      </div>
      <pre className="da-sem-sql-pre">
        {tab === 'semantic' ? semantic : compiled}
      </pre>
    </div>
  );
}

function ResultTable({ columns, rows }: { columns: string[]; rows: (string | null)[][] }) {
  return (
    <div className="da-sem-table-wrap">
      <table className="da-sem-table">
        <thead>
          <tr>
            {columns.map((c, i) => (
              <th key={i}>{c}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, ri) => (
            <tr key={ri}>
              {row.map((cell, ci) => (
                <td key={ci}>{cell === null ? <span className="da-sem-null">NULL</span> : cell}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function SemanticDetail({ sem }: { sem: SemanticResult }) {
  const d = sem.data as Record<string, unknown>;
  const stage = sem.stage;

  if (stage === 'plan') {
    const semanticSql = (d.semanticSql as string) ?? '';
    const compiledSql = (d.compiledSql as string) ?? '';
    const models = (d.models as string[]) ?? [];
    const relationships = (d.relationships as string[]) ?? [];
    const warnings = (d.warnings as string[]) ?? [];
    return (
      <div className="da-sem-section">
        <SqlTabs semantic={semanticSql} compiled={compiledSql} />
        {models.length > 0 && (
          <div className="da-sem-chips">
            {models.map(m => <span key={m} className="da-chip da-chip-model">{m}</span>)}
            {relationships.map(r => (
              <span key={r} className="da-chip da-chip-rel">{r}</span>
            ))}
          </div>
        )}
        {warnings.map((w, i) => (
          <div key={i} className="da-sem-warn">{w}</div>
        ))}
        <div className="da-sem-note">
          语义编译通过，尚未执行数据库；使用原始语义 SQL 调用 query_semantic。
        </div>
      </div>
    );
  }

  if (stage === 'query') {
    const semanticSql = (d.semanticSql as string) ?? '';
    const compiledSql = (d.compiledSql as string) ?? '';
    const columns = (d.columns as string[]) ?? [];
    const rows = (d.rows as (string | null)[][]) ?? [];
    const returnedRowCount = (d.returnedRowCount as number) ?? rows.length;
    const truncated = d.truncated as boolean;
    const truncationReason = d.truncationReason as string;
    const elapsedMs = d.elapsedMs as number;
    const groupName = d.groupName as string;
    const memoryStored = d.memoryStored as boolean;
    const memoryWarning = d.memoryWarning as string;
    return (
      <div className="da-sem-section">
        <SqlTabs semantic={semanticSql} compiled={compiledSql} />
        <div className="da-sem-meta">
          {groupName && <span className="da-chip">{groupName}</span>}
          <span className="da-sem-meta-item">
            {returnedRowCount} 行{truncated ? ' · 已截断' : ''}
          </span>
          {typeof elapsedMs === 'number' && (
            <span className="da-sem-meta-item">{elapsedMs}ms</span>
          )}
          {truncated && truncationReason && (
            <span className="da-sem-truncation">{truncationReason}</span>
          )}
          {rows.length === 0 && !truncated && (
            <span className="da-sem-empty">查询结果为空，无匹配数据</span>
          )}
        </div>
        {columns.length > 0 && <ResultTable columns={columns} rows={rows} />}
        {memoryStored === false && memoryWarning && (
          <div className="da-sem-warn">{memoryWarning}</div>
        )}
      </div>
    );
  }

  if (stage === 'models') {
    const models = (d.models as Record<string, unknown>[]) ?? [];
    return (
      <div className="da-sem-section">
        {models.length === 0 ? (
          <div className="da-sem-empty">未找到语义模型，请先在知识库中生成模型</div>
        ) : (
          <ul className="da-sem-list">
            {models.map((m, i) => (
              <li key={i} className="da-sem-list-item">
                <span className="da-sem-list-name">{(m.name as string) ?? ''}</span>
                {m.label != null && <span className="da-sem-list-label">{String(m.label)}</span>}
                {m.bound !== undefined && (
                  <span className={`da-sem-bound${m.bound ? ' ok' : ' err'}`}>
                    {m.bound ? '已绑定' : '未绑定'}
                  </span>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    );
  }

  if (stage === 'model') {
    const name = d.name as string;
    const columns = (d.columns as Record<string, unknown>[]) ?? [];
    return (
      <div className="da-sem-section">
        {name && <div className="da-sem-model-name">{name}</div>}
        <ul className="da-sem-list">
          {columns.map((c, i) => (
            <li key={i} className="da-sem-list-item">
              <span className="da-sem-list-name">{(c.name as string) ?? ''}</span>
              <span className="da-sem-list-label">{(c.type as string) ?? ''}</span>
              {Boolean(c.isCalculated) && <span className="da-chip da-chip-calc">计算列</span>}
              {Boolean(c.handle) && <span className="da-chip da-chip-rel">关系 handle</span>}
              {c.queryable === false && (
                <span className="da-sem-err-text" title={c.unavailableReason as string}>
                  不可查询
                </span>
              )}
            </li>
          ))}
        </ul>
      </div>
    );
  }

  if (stage === 'recall') {
    const queries = (d.queries as Record<string, string>[]) ?? [];
    return (
      <div className="da-sem-section">
        {queries.length === 0 ? (
          <div className="da-sem-empty">暂无历史查询记录</div>
        ) : (
          queries.map((q, i) => (
            <div key={i} className="da-sem-recall-item">
              <div className="da-sem-recall-q">{q.question}</div>
              <pre className="da-sem-sql-pre">{q.semanticSql}</pre>
              <span className="da-chip">{q.source}</span>
            </div>
          ))
        )}
      </div>
    );
  }

  // context / fallback
  return <Markdown>{formatResult(JSON.stringify(d))}</Markdown>;
}

export default function ToolInspector({ tool, onClose }: ToolInspectorProps) {
  const sem = isSemanticTool(tool.name) ? parseSemantic(tool.result) : null;
  const title = sem ? stageSummary(tool.name, sem) : tool.name;
  const isError = sem?.status === 'error';

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
          sem ? (
            <>
              {isError && sem.diagnostics?.length > 0 && (
                <div className="da-sem-diagnostics">
                  {sem.diagnostics.map((dg, i) => (
                    <div key={i} className="da-sem-diag">
                      <span className="da-sem-diag-code">{dg.code}</span>
                      <div className="da-sem-diag-msg">{dg.message}</div>
                      <div className="da-sem-diag-hint">{dg.hint}</div>
                    </div>
                  ))}
                </div>
              )}
              {!isError && <SemanticDetail sem={sem} />}
              {isError && <details style={S.rawDetails}><summary style={S.rawSummary}>原始 JSON</summary>
                <pre style={S.rawPre}>{JSON.stringify(sem, null, 2)}</pre>
              </details>}
            </>
          ) : (
            <>
              <div style={S.label}>结果</div>
              <div className="da-toolcall-result" style={S.resultBox}>
                <Markdown>{formatResult(tool.result)}</Markdown>
              </div>
            </>
          )
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
  rawDetails: { marginTop: 8 },
  rawSummary: { fontSize: '0.75rem', color: 'var(--da-text-muted)', cursor: 'pointer' },
  rawPre: { fontSize: '0.7rem', whiteSpace: 'pre-wrap', wordBreak: 'break-all', color: 'var(--da-text-3)' },
};
