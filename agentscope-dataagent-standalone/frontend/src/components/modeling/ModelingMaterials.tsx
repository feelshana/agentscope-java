import { useEffect, useState, type ReactNode } from 'react';
import { getScreeningTable, type ScreeningStatus, type ScreeningTable } from '../../api/fieldScreening';
import Icon from '../Icon';
import BusinessDocumentsPanel from './BusinessDocumentsPanel';
import { MaterialsCatalog, WorkspaceEmpty, WorkspaceObjectTools, WorkspaceToolbar } from './ModelingWorkspace';
import './ModelingOptimization.css';
import './ModelingMaterials.css';

type MaterialTab = 'documents' | 'tables';

export default function ModelingMaterials({ groupId, tables, idle, paired, onManageData, onAdjust, onDocumentsChanged, onReference, onTable, onPublish }: {
  groupId: string; tables: ScreeningStatus[] | null; idle: boolean; paired: boolean;
  onManageData: () => void; onAdjust: (id: string) => void; onDocumentsChanged: (summary: string, removedName?: string) => void;
  onReference: (name: string, quote?: string) => void; onTable: (table: ScreeningStatus) => void; onPublish: () => void;
}) {
  const [mode, setMode] = useState<MaterialTab>('documents');
  const [doc, setDoc] = useState<string>();
  const [selected, setSelected] = useState('');
  const [term, setTerm] = useState('');
  const [catalogOpen, setCatalogOpen] = useState(true);
  const [docCount, setDocCount] = useState<number>();
  const matching = (tables ?? []).filter(table => table.name.toLowerCase().includes(term.trim().toLowerCase()));
  const catalog = paired ? tables ?? [] : matching;
  const current = catalog.find(table => table.datasetId === selected) ?? catalog[0];
  const id = mode === 'tables' ? current?.datasetId : undefined;
  const [detail, setDetail] = useState<ScreeningTable>();
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    if (!id) return;
    let active = true;
    setError('');
    void getScreeningTable(groupId, id).then(table => { if (active) setDetail(table); })
      .catch(e => { if (active) setError(e instanceof Error ? e.message : '读取字段失败'); });
    return () => { active = false; };
  }, [groupId, id, tables, retry]);
  const toolbar = (objectActions: ReactNode, actions: ReactNode, context?: string, count = docCount) => !paired && <WorkspaceToolbar label="资料类型" active={mode} onSelect={value => setMode(value)} tabs={[
    { id: 'documents', label: '业务文档', icon: 'file', count },
    { id: 'tables', label: '数据表', icon: 'table', count: tables?.length },
  ]} objectActions={objectActions} context={context}>{actions}</WorkspaceToolbar>;
  const toggleCatalog = () => setCatalogOpen(open => !open);
  const manage = <button type="button" className="da-btn da-btn-primary" onClick={onManageData}><Icon name="plus" size="sm" />上传数据／关联表</button>;
  const fields = detail?.datasetId === id ? detail : undefined;
  const tableActions = current && <>
    {!paired && <button type="button" className="da-btn" onClick={() => { setSelected(current.datasetId); onTable(current); }}><Icon name="chat" size="sm" />边聊边核对</button>}
    <button type="button" className="da-btn" disabled={!idle} onClick={() => onAdjust(current.datasetId)}>
      <Icon name="edit" size="sm" />{(fields?.confirmed ?? current.confirmed) ? '调整字段范围' : '确认字段范围'}
    </button>
  </>;
  return <section className={'sm-pane mo-optimization mo-workspace mo-materials' + (paired ? ' mo-work-paired' : '')} aria-label="资料">
    <div hidden={mode !== 'documents'} className="mo-document-workspace">
      <BusinessDocumentsPanel key={groupId} groupId={groupId} onChanged={onDocumentsChanged}
        onCountChange={setDocCount} workspace={{ selected: doc, onSelect: setDoc, paired, idle, toolbar, onReference, onPublish, catalogOpen, toggleCatalog }} />
    </div>
    {mode === 'tables' && <>{paired
      ? <WorkspaceObjectTools compact context={current?.name}>{tableActions}</WorkspaceObjectTools>
      : toolbar(tableActions, manage, catalogOpen ? undefined : current?.name)}
      {tables === null ? <p className="mo-work-loading" role="status">正在读取数据表…</p>
        : !tables.length ? <WorkspaceEmpty icon="table" title="还没有数据表" description="上传数据或关联业务表，再选择要纳入模型的字段。">{manage}</WorkspaceEmpty>
          : <div className="mo-work-grid mo-material-grid" data-catalog-open={catalogOpen}>
            {!paired && <MaterialsCatalog title="数据表目录" count={`${matching.length} 张`} open={catalogOpen} onToggle={toggleCatalog}
              filters={<input type="search" className="da-input" placeholder="搜索数据表" aria-label="搜索数据表" value={term} onChange={e => setTerm(e.target.value)} />}>
              {matching.map(table => <li key={table.datasetId} className={current?.datasetId === table.datasetId ? 'qc-queue-selected' : ''}>
                <button type="button" aria-current={current?.datasetId === table.datasetId || undefined} onClick={() => setSelected(table.datasetId)}>
                  <Icon name="table" size="sm" /><span className="mo-catalog-name"><strong>{table.name}</strong><small>{table.confirmed ? `已纳入 ${table.included} / ${table.total}` : '待确认字段范围'}</small></span>
                </button></li>)}
            </MaterialsCatalog>}
            <div className="mo-work-detail mo-material-detail mo-table-detail" key={current?.datasetId}>
              {!current ? <WorkspaceEmpty icon="search" title="没有匹配的数据表"><button type="button" className="da-btn" onClick={() => setTerm('')}>清除搜索</button></WorkspaceEmpty>
                : <>
                  {error ? <p className="mo-error" role="alert">{error} <button type="button" className="da-btn" onClick={() => setRetry(n => n + 1)}>重试</button></p>
                    : !fields ? <p className="da-small" role="status">正在读取字段清单…</p>
                      : <MaterialFields fields={fields.fields} />}
                </>}
            </div>
          </div>}
    </>}
  </section>;
}

function MaterialFields({ fields }: Pick<ScreeningTable, 'fields'>) {
  const [query, setQuery] = useState('');
  const [scope, setScope] = useState('all');
  const term = query.trim().toLowerCase();
  const included = fields.filter(field => field.included).length;
  const filtered = fields.filter(field => (scope === 'all' || field.included === (scope === 'included'))
    && (!term || [field.name, field.originalName, field.description].some(value => value?.toLowerCase().includes(term))));
  const filtering = !!term || scope !== 'all';
  return <section className="mo-fields-frame" aria-label="建模字段">
    <div className="mo-fields-toolbar">
      <div className="mo-fields-heading"><h4>字段清单</h4><span className="da-small">已纳入 <strong>{included}</strong> / {fields.length}</span></div>
      <div className="mo-fields-filters">
        <label className="mo-reader-search"><Icon name="search" size="sm" /><input type="search" placeholder="搜索字段名或业务说明"
          aria-label="搜索字段名或业务说明" value={query} onChange={event => setQuery(event.target.value)} /></label>
        <select className="da-input" aria-label="筛选建模范围" value={scope} onChange={event => setScope(event.target.value)}>
          <option value="all">全部字段</option><option value="included">已纳入</option><option value="excluded">未纳入</option>
        </select>
      </div>
    </div>
    {filtered.length ? <div className="mo-fields-scroll" tabIndex={0} role="region" aria-label="字段清单，可滚动">
      <table className="mo-field-table">
        <thead><tr><th scope="col">物理字段</th><th scope="col" className="mo-field-type-column">数据类型</th><th scope="col">业务说明</th><th scope="col">建模范围</th></tr></thead>
        <tbody>{filtered.map(field => <tr key={field.name}>
          <th scope="row"><code>{field.name}</code>
            {field.originalName && field.originalName !== field.name && <small>原始表头 {field.originalName}</small>}
            {field.sqlType && <small className="mo-field-type-inline">{field.sqlType}</small>}</th>
          <td className="mo-field-type-column"><code>{field.sqlType || '—'}</code></td>
          <td>{field.description || '—'}</td>
          <td><span className={`mo-field-state${field.included ? ' mo-field-included' : ''}`}>{field.included ? '已纳入' : '未纳入'}</span>
            {field.locked && <small title="事实表时间字段必须纳入"><Icon name="shield" size="sm" />已锁定</small>}</td>
        </tr>)}</tbody>
      </table>
    </div> : <WorkspaceEmpty icon={filtering ? 'search' : 'table'} title={filtering ? '没有匹配的字段' : '暂无字段'}>
      {filtering && <button type="button" className="da-btn" onClick={() => { setQuery(''); setScope('all'); }}>清除筛选</button>}
    </WorkspaceEmpty>}
  </section>;
}
