import React, { useEffect, useState } from 'react';
import { ExternalDataSource, listColumns, listSchemas, listTables, SchemaColumn, SchemaTable } from '../api/datasources';
import DataSourceDialog from './DataSourceDialog';
import { dataSourceConnection, dataSourceKind } from './dataSourceConnection';
import Icon from './Icon';

export default function DataSourceDetailModal({ dataSource, onClose }: { dataSource: ExternalDataSource; onClose: () => void }) {
  const connection = dataSourceConnection(dataSource);
  const [browse, setBrowse] = useState(false);
  const [schemas, setSchemas] = useState<string[]>([]);
  const [schema, setSchema] = useState('');
  const [tables, setTables] = useState<SchemaTable[]>([]);
  const [table, setTable] = useState('');
  const [columns, setColumns] = useState<SchemaColumn[]>([]);
  const [schemaLoading, setSchemaLoading] = useState(false);
  const [tableLoading, setTableLoading] = useState(false);
  const [columnLoading, setColumnLoading] = useState(false);
  const [schemaError, setSchemaError] = useState<string | null>(null);
  const [tableError, setTableError] = useState<string | null>(null);
  const [columnError, setColumnError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!browse) return;
    let active = true;
    setSchemaLoading(true); setSchemaError(null); setTableError(null); setColumnError(null); setSchemas([]); setSchema(''); setTables([]); setTable(''); setColumns([]);
    listSchemas(dataSource.id).then(result => {
      if (active) { setSchemas(result); setSchema(result[0] ?? ''); }
    }).catch(e => { if (active) setSchemaError(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (active) setSchemaLoading(false); });
    return () => { active = false; };
  }, [browse, dataSource.id, attempt]);

  useEffect(() => {
    setTableError(null); setTableLoading(false);
    if (!browse || !schema) return;
    let active = true;
    setTableLoading(true); setTableError(null); setTables([]); setTable(''); setColumns([]);
    listTables(dataSource.id, schema).then(result => { if (active) setTables(result); })
      .catch(e => { if (active) setTableError(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (active) setTableLoading(false); });
    return () => { active = false; };
  }, [browse, dataSource.id, schema]);

  useEffect(() => {
    setColumnError(null); setColumnLoading(false);
    if (!browse || !schema || !table) return;
    let active = true;
    setColumnLoading(true); setColumnError(null); setColumns([]);
    listColumns(dataSource.id, schema, table).then(result => { if (active) setColumns(result); })
      .catch(e => { if (active) setColumnError(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (active) setColumnLoading(false); });
    return () => { active = false; };
  }, [browse, dataSource.id, schema, table]);

  const error = schemaError || tableError || columnError;
  return <DataSourceDialog title={dataSource.name} description="外部连接详情" onClose={onClose} wide={browse}>
    <div className="ds-dialog-body">
      <dl className="ds-overview">
        <div><dt>类型</dt><dd>{dataSourceKind(dataSource.kind)}</dd></div>
        <div><dt>主机与端口</dt><dd>{connection ? `${connection.host}:${connection.port}` : '连接格式无法解析'}</dd></div>
        <div><dt>数据库</dt><dd>{connection?.database || '未指定数据库'}</dd></div>
        <div><dt>用户名</dt><dd>{dataSource.username || '未指定'}</dd></div>
        <div><dt>数据采样</dt><dd>{dataSource.sampling ? '已开启，每表最多 1000 行' : '已关闭'}</dd></div>
        <div><dt>凭据</dt><dd>不在详情中展示密码</dd></div>
      </dl>
      <button type="button" className="ds-browse-toggle" aria-expanded={browse} onClick={() => setBrowse(value => !value)}><Icon name="table" size="sm" />{browse ? '收起库表结构' : '浏览库表结构'}<Icon name="chevron" size="sm" /></button>
      {browse && <section className="ds-schema-browser" aria-label="库表结构">
        {error && <div className="ds-feedback ds-failed" role="alert"><span>{error}</span><button className="da-btn da-btn-sm" onClick={() => setAttempt(value => value + 1)}>重试</button></div>}
        <div className="ds-fields">
          <label className="ds-field">数据库<select value={schema} disabled={schemaLoading || !schemas.length} onChange={e => { setSchema(e.target.value); setTable(''); setColumns([]); }}>
            {!schemas.length && <option value="">{schemaLoading ? '加载中…' : schemaError ? '加载失败' : '暂无数据库'}</option>}
            {schemas.map(value => <option key={value} value={value}>{value}</option>)}
          </select></label>
          <label className="ds-field">数据表<select value={table} disabled={!schema || tableLoading || !tables.length} onChange={e => { setTable(e.target.value); setColumns([]); }}>
            <option value="">{schemaLoading || tableLoading ? '加载中…' : tableError ? '加载失败' : !tables.length ? '暂无数据表' : '请选择数据表'}</option>
            {tables.map(value => <option key={value.name} value={value.name}>{value.name}{value.comment ? ` · ${value.comment}` : ''}</option>)}
          </select></label>
        </div>
        <div className="ds-column-table"><table className="kw-table" aria-label="字段信息" aria-busy={columnLoading}>
          <thead><tr><th scope="col">字段名称</th><th scope="col">类型</th><th scope="col">说明</th></tr></thead>
          <tbody>{!table || columnLoading || columnError || !columns.length
            ? <tr><td colSpan={3} className="ds-empty" role="status">{columnLoading ? '正在加载字段…' : columnError ? '字段加载失败，请重试' : table ? '暂无字段' : '请选择数据表查看字段信息'}</td></tr>
            : columns.map(column => <tr key={column.name}><td>{column.name}</td><td><span className="ds-type">{column.type}</span></td><td>{column.description || '—'}</td></tr>)}
          </tbody>
        </table></div>
      </section>}
    </div>
    <footer className="ds-dialog-foot"><button type="button" className="da-btn" onClick={onClose}>关闭</button></footer>
  </DataSourceDialog>;
}
