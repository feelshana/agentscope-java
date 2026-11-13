import { Fragment, type ReactNode } from 'react';
import type { DatasetColumn } from '../api/datasets';
import Icon from './Icon';

export const datasetColumnLabel = (c: DatasetColumn) => c.displayName?.trim() || c.originalName || c.name;
export const isNumericColumn = (c: DatasetColumn | undefined) => /^(tinyint|smallint|int|bigint|decimal|float|double|numeric|real)/i.test(c?.sqlType ?? '');
export interface DatasetPreviewRow { values: readonly (string | null)[]; index: number }

/** Shared presentation only; loading, permissions and optional editing stay with the caller. */
export function DatasetPreviewTable({ columns, rows, onFieldInfo, activeFieldName }: {
  columns: DatasetColumn[]; rows: DatasetPreviewRow[];
  activeFieldName?: string;
  onFieldInfo?: (column: DatasetColumn, anchor: HTMLButtonElement) => void;
}) {
  return <table className="kw-table kw-preview"><thead><tr><th className="rownum">#</th>{columns.map(c =>
    <th key={c.name} className={isNumericColumn(c) ? 'numeric' : ''}><div className="kd-field-head"><div><strong>{datasetColumnLabel(c)}</strong><small>{c.name}</small></div>{onFieldInfo &&
      <button type="button" className="kd-info" aria-label={`查看 ${datasetColumnLabel(c)} 的字段说明`} aria-expanded={activeFieldName === c.name} aria-controls={activeFieldName === c.name ? 'kd-field-help' : undefined} onClick={e => onFieldInfo(c, e.currentTarget)}><Icon name="info" size="sm" /></button>}</div></th>)}</tr></thead>
    <tbody>{rows.map(row => <tr key={row.index}><td className="rownum">{row.index + 1}</td>{row.values.map((v, j) =>
      <td key={j} className={isNumericColumn(columns[j]) ? 'numeric' : ''} title={v == null ? 'NULL' : String(v)}>{v == null ? <span className="kd-muted">NULL</span> : String(v)}</td>)}</tr>)}</tbody></table>;
}

export function DatasetFieldsTable({ columns, actions, afterRow, compact = false }: {
  compact?: boolean;
  columns: DatasetColumn[]; actions?: (column: DatasetColumn) => ReactNode; afterRow?: (column: DatasetColumn) => ReactNode;
}) {
  return <table className={`kw-table kd-fields${compact ? ' is-compact' : ''}`}><thead><tr><th>{compact ? '字段' : '字段名称'}</th>{!compact && <th>原字段名称</th>}<th>数据类型</th><th>业务说明</th></tr></thead><tbody>{columns.map(c =>
    <Fragment key={c.name}><tr><td>{compact && <strong>{datasetColumnLabel(c)}</strong>}<code>{c.name}</code></td>{!compact && <td>{c.originalName || <span className="kd-muted">—</span>}</td>}<td><span className="kw-type">{c.sqlType}</span></td><td><div className="kd-field-description"><span>{c.description || <span className="kd-muted">待补充说明</span>}</span>{actions?.(c)}</div></td></tr>{afterRow?.(c)}</Fragment>)}</tbody></table>;
}
