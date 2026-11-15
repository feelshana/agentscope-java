import type { ScreeningStatus } from '../../api/fieldScreening';
import Icon from '../Icon';
import BusinessDocumentsPanel from './BusinessDocumentsPanel';
import './ModelingOptimization.css';

export default function ModelingMaterials({ groupId, tables, idle, showDocuments, onManageData, onAdjust, onDocumentsChanged, onCoach }: {
  groupId: string; tables: ScreeningStatus[] | null; idle: boolean; showDocuments: boolean;
  onManageData: () => void; onAdjust: (id: string) => void; onDocumentsChanged: (summary: string) => void; onCoach: () => void;
}) {
  return <section className="mo-materials mo-optimization" aria-label="资料">
    <header className="mo-page-head"><div><h2>资料</h2><p>管理模型使用的数据表与业务文档，让字段范围和业务口径有据可查。</p></div></header>
    <div className={`mo-material-layout${showDocuments ? '' : ' mo-material-layout-data-only'}`}>
      <section className="da-card mo-data-section" aria-label="数据表资料"><header className="mo-section-head"><span className="mo-section-icon"><Icon name="table" /></span><div className="mo-section-heading"><h3>数据表 <span className="mo-resource-count">{tables ? `${tables.length} 张` : '读取中…'}</span></h3><p>管理纳入模型的字段范围</p></div><button type="button" className="da-btn da-btn-primary" onClick={onManageData}><Icon name="plus" size="sm" />上传数据 / 关联表</button></header>
        {tables === null ? <p className="mo-section-description" role="status">字段范围尚未就绪，请稍后刷新。</p> : !tables.length ? <div className="mo-empty"><p>还没有可用于建模的数据表。</p><button className="da-btn" onClick={onManageData}>上传数据 / 关联表</button></div> : <>
          <div className="mo-table-scroll"><table className="mo-data-table"><thead><tr><th scope="col">数据表</th><th scope="col">纳入字段</th><th scope="col">操作</th></tr></thead><tbody>{tables.map(table => <tr key={table.datasetId}>
            <td><span className="mo-table-name"><span className="mo-table-icon"><Icon name="table" size="sm" /></span><span title={table.name}>{table.name}</span></span></td>
            <td><span className="mo-field-count"><strong>{table.included}</strong><span> / {table.total}</span></span><span className={`mo-field-note${table.total > table.included ? ' mo-field-note-partial' : ''}`}>{table.total > table.included ? `${table.total - table.included} 个未纳入` : table.total ? '全部纳入' : '暂无字段'}</span></td>
            <td><button type="button" className="da-btn mo-field-action" disabled={!idle} aria-label={`调整${table.name}的字段`} title={idle ? '调整纳入模型的字段' : '助手运行中，完成后可调整'} onClick={() => onAdjust(table.datasetId)}>字段<Icon name="chevron" size="sm" /></button></td>
          </tr>)}</tbody></table></div><footer className="mo-table-footer"><Icon name="info" size="sm" /><span>调整字段后进入草稿，仅重新建模这张表</span></footer>
        </>}
      </section>
      {showDocuments && <BusinessDocumentsPanel key={groupId} groupId={groupId} onChanged={onDocumentsChanged} />}
    </div><aside className="mo-material-guidance"><Icon name="shield" size="sm" /><div><strong>资料修改先进入草稿</strong><p>核对并发布后，新的业务口径才用于问数。</p></div><button type="button" className="da-btn" onClick={onCoach}>检查业务口径<Icon name="chevron" size="sm" /></button></aside>
  </section>;
}
