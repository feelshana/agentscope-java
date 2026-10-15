import { useState } from 'react';
import Markdown from './Markdown';
import { parseWrenResult, record } from '../utils/wrenToolPresentation';
import './WrenToolResult.css';

const LABELS: Record<string, string> = {
  name: '名称', base_object: '基础模型 / 视图', measures: '指标', dimensions: '维度',
  time_dimensions: '时间维度', expression: '表达式', type: '类型', description: '说明',
  properties: '属性', hierarchies: '层级', columns: '字段', primaryKey: '主键',
  primary_key: '主键', relationships: '关系', models: '模型', cubes: '指标模型',
  views: '视图', instructions: '业务规则', used_legacy: '使用旧版规则',
  row_count: '返回行数', truncated: '结果已截断', sql: 'SQL', nl: '问题', question: '问题',
  datasource: '数据源', source: '来源标记', tags: '标签', path: '来源文件',
  score: '匹配分值', distance: '向量距离', _distance: '向量距离',
  inputSchema: '参数说明', annotations: '工具说明', files: '文件',
};

function valueText(value: unknown): string {
  if (value === null) return 'NULL';
  if (value === undefined) return '—';
  if (typeof value === 'boolean') return value ? '是' : '否';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

function SourceCode({ text }: { text: string }) {
  return <pre className="da-wren-code"><code>{text}</code></pre>;
}

function Rows({ rows, columns }: { rows: unknown[]; columns?: unknown[] }) {
  const [expanded, setExpanded] = useState(false);
  const objectRows = rows.every(record);
  const keys = objectRows ? Array.from(new Set(rows.flatMap(row => Object.keys(row as Record<string, unknown>)))) : [];
  const headers = columns?.map(col => record(col) ? valueText(col.name) : valueText(col)) ?? keys;
  if (!headers.length && !rows.length) return <p className="da-wren-empty">查询成功，未返回数据行。</p>;
  const shown = expanded ? rows : rows.slice(0, 50);
  return <>
    <div className="da-wren-table-wrap"><table className="da-wren-table">
      {headers.length > 0 && <thead><tr>{headers.map((h, i) => <th key={i}>{h}</th>)}</tr></thead>}
      <tbody>{shown.map((row, i) => <tr key={i}>
        {(record(row) ? headers.map(h => row[h]) : Array.isArray(row) ? row : [row])
          .map((cell, j) => <td key={j}>{valueText(cell)}</td>)}
      </tr>)}</tbody>
    </table></div>
    {!rows.length && <p className="da-wren-empty">查询成功，未返回数据行。</p>}
    {!expanded && rows.length > 50 && <button type="button" className="da-wren-more" onClick={() => setExpanded(true)}>显示全部 {rows.length} 行</button>}
  </>;
}

function StructuredValue({ value, field = '', depth = 0 }: { value: unknown; field?: string; depth?: number }) {
  if (depth > 5) return <SourceCode text={JSON.stringify(value, null, 2)} />;
  if (typeof value === 'string') {
    if (['sql', 'expression', 'refSql', 'ref_sql'].includes(field)) return <SourceCode text={value} />;
    if (field === 'instructions' || field === 'description') return <Markdown>{value}</Markdown>;
    return <span className="da-wren-text">{value || '（空）'}</span>;
  }
  if (Array.isArray(value)) {
    if (!value.length) return <span className="da-wren-muted">无</span>;
    if (value.every(item => !record(item) && !Array.isArray(item)))
      return <div className="da-wren-tags">{value.map((item, i) => <span key={i}>{valueText(item)}</span>)}</div>;
    return <div className="da-wren-items">{value.map((item, i) => <div className="da-wren-item" key={i}>
      <StructuredValue value={item} depth={depth + 1} />
    </div>)}</div>;
  }
  if (record(value)) return <dl className="da-wren-fields">{Object.entries(value).map(([key, item]) => <div key={key}>
    <dt>{LABELS[key] ?? key}</dt><dd><StructuredValue value={item} field={key} depth={depth + 1} /></dd>
  </div>)}</dl>;
  return <span>{valueText(value)}</span>;
}

function Matches({ items }: { items: unknown[] }) {
  return <div className="da-wren-matches">
    <p className="da-wren-summary">{items.length ? `找到 ${items.length} 条相关问答` : '未找到匹配的问答'}</p>
    {!items.length && <p className="da-wren-empty">可继续依据已发布模型查询。已保存的问答会在后续相关问题中参与召回。</p>}
    {items.map((item, i) => {
      if (!record(item)) return <StructuredValue key={i} value={item} />;
      const question = item.nl_query ?? item.nl ?? item.question;
      const sql = item.sql_query ?? item.sql;
      const { nl_query: _nlQuery, sql_query: _sqlQuery, nl: _nl, question: _question, sql: _sql, ...details } = item;
      return <article className="da-wren-match" key={i}>
        <h4><span>{i + 1}</span>{valueText(question)}</h4>
        {typeof sql === 'string' && <SourceCode text={sql} />}
        {Object.keys(details).length > 0 && <details><summary>来源与匹配信息</summary><StructuredValue value={details} /></details>}
      </article>;
    })}
  </div>;
}

export default function WrenToolResult({ result }: { result: string }) {
  const display = parseWrenResult(result);
  const data = display.data;
  const [copied, setCopied] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);
  return <div className="da-wren-result">
    {display.error ? <div role="alert" className="da-wren-error">{display.note}</div> : <>
      {display.receipt && <div className="da-wren-query">
        <strong>{valueText(display.receipt.question)}</strong>
        {typeof display.receipt.sql === 'string' && <details><summary>本次执行 SQL</summary><SourceCode text={display.receipt.sql} /></details>}
      </div>}
      {record(data) && Array.isArray(data.matches) ? <Matches items={data.matches} />
        : record(data) && Array.isArray(data.queries) ? <Matches items={data.queries} />
        : record(data) && Array.isArray(data.rows) ? <>
          <p className="da-wren-summary">返回 {valueText(data.row_count ?? data.rows.length)} 行{data.truncated === true ? ' · 已达到返回上限，结果未完整展示' : ''}</p>
          <Rows rows={data.rows} columns={Array.isArray(data.columns) ? data.columns : undefined} />
          {typeof data.sql === 'string' && <details><summary>查询 SQL</summary><SourceCode text={data.sql} /></details>}
        </> : data !== undefined ? <StructuredValue value={data} /> : null}
      {display.note && <div className="da-wren-note"><Markdown>{display.note}</Markdown></div>}
      {!display.note && data === undefined && !display.receipt && <p className="da-wren-empty">工具未返回内容。</p>}
    </>}
    <details className="da-wren-raw"><summary>查看原始结果</summary>
      <button type="button" className="da-wren-more" onClick={async () => {
        try { await navigator.clipboard.writeText(result); setCopied(true); setCopyFailed(false); }
        catch { setCopyFailed(true); }
      }}>{copied ? '已复制' : '复制原文'}</button>
      {copyFailed && <span role="status">复制失败，请选择下方原文复制。</span>}
      <SourceCode text={result} />
    </details>
  </div>;
}
