import { useEffect, useState, type ReactNode } from 'react';
import type { Dataset, GroupDetail } from '../../api/datasets';
import type { KnowledgeReleaseDecision } from '../../components/modeling/releaseDecision';
import Icon, { type IconName } from '../../components/Icon';
import KnowledgeSourceConnection from './KnowledgeSourceConnection';
import type { KnowledgeSection } from './knowledgeWorkspaceRoutes';

export const datasetSource = (dataset: Dataset) =>
  dataset.origin === 'datasource' ? '数据源关联' : dataset.origin === 'derived' ? '派生数据' : '文件上传';

export function WorkspaceHeading({ title, description, children }: { title: string; description?: string; children?: ReactNode }) {
  return <header className="kbw-page-heading">
    <div><h1>{title}</h1>{description && <p>{description}</p>}</div>{children && <div className="kbw-page-actions">{children}</div>}
  </header>;
}

export const describedColumns = (table: Dataset) => table.columns.filter(column => column.description?.trim()).length;

export default function KnowledgeWorkspaceOverview({ detail, decision, releaseStatus, publishedViews, version, publishedAt, ruleCount, queryAvailable, preparing, onGo, onReview, onDataset, onUpload, onAssociate, onAsk }: {
  detail: GroupDetail;
  decision: KnowledgeReleaseDecision;
  releaseStatus: 'loading' | 'failed' | 'ready';
  publishedViews?: number;
  version: number;
  publishedAt?: string | null;
  ruleCount?: number;
  queryAvailable: boolean;
  preparing: string | null;
  onGo: (section: KnowledgeSection) => void;
  onReview?: () => void;
  onDataset: (id: string, tab?: 'fields') => void;
  onUpload: () => void;
  onAssociate: () => void;
  onAsk?: () => void;
}) {
  const [copyNotice, setCopyNotice] = useState('');
  useEffect(() => {
    if (!copyNotice) return;
    const timer = window.setTimeout(() => setCopyNotice(''), 1500);
    return () => window.clearTimeout(timer);
  }, [copyNotice]);
  const copyLink = async () => {
    try { await navigator.clipboard.writeText(window.location.href); setCopyNotice('已复制'); }
    catch { setCopyNotice('请从地址栏复制'); }
  };
  const { group, datasets } = detail;
  const canManage = group.canManage && !group.deletionPending;
  const count = datasets.length;
  const external = datasets.filter(table => table.origin === 'datasource').length;
  const publishedDate = publishedAt && new Date(publishedAt).toLocaleDateString('zh-CN');
  const releaseText = !canManage ? '查看当前模型和版本。' : releaseStatus === 'loading' ? '正在读取发布状态…'
    : releaseStatus === 'failed' ? '发布状态读取失败。'
      : decision.blocker ? '有事项需要处理后才能发布。'
        : decision.objectCount ? `${decision.objectCount} 项草稿变更待审阅`
          : decision.republish ? '需要重新发布以恢复问数。'
            : decision.taskCount ? `${decision.taskCount} 项待办待处理`
              : version ? '暂无待发布变更。' : '语义模型尚未发布。';
  const metric = (title: string, value: string | number, description: string, icon: IconName, section: KnowledgeSection) =>
    <button type="button" className="kbw-card kbw-metric" onClick={() => onGo(section)}>
      <span><Icon name={icon} size="sm" />{title}</span><Icon name="chevron" size="sm" className="kbw-metric-arrow" /><strong>{value}</strong><small>{description}</small>
    </button>;
  const progress = (label: string, description: string, step: number, done: boolean, active: boolean, action: () => void, link: string) =>
    <button type="button" className="kbw-step" data-done={done || undefined} data-active={active || undefined} onClick={action}>
      <span>{done ? <Icon name="check" size="sm" /> : step}</span><div><b>{label}</b>{description && <small>{description}</small>}</div><em>{link}<Icon name="chevron" size="sm" /></em>
    </button>;
  return <div className="kbw-page">
    <WorkspaceHeading title="概览" description={group.description?.trim() || (version ? '业务口径可持续完善，问数使用已发布版本。' : '添加数据集后即可准备语义模型。')}>
      <button type="button" className="da-btn da-btn-sm kbw-overview-copy" onClick={() => void copyLink()}>
        {copyNotice === '已复制' ? copyNotice : '复制链接'}
      </button>
      {copyNotice === '请从地址栏复制' && <span className="kbw-copy-feedback" role="status">{copyNotice}</span>}
      {canManage && <><button type="button" className="da-btn" onClick={() => onGo('settings')}><Icon name="edit" size="sm" />编辑说明</button>
        {onReview && <button type="button" className="da-btn" onClick={onReview}><Icon name="clock" size="sm" />查看审阅单</button>}</>}
    </WorkspaceHeading>
    <div className="kbw-steps" aria-label="知识库准备状态">
      {progress('数据', count ? '已就绪' : '上传文件或关联数据表', 1, count > 0, !count, () => onGo('datasets'), '数据集')}
      {progress('语义模型', decision.objectCount && releaseStatus === 'ready' ? '有草稿待审阅' : version ? '' : count ? '待建模并发布' : '准备数据后开始', 2, version > 0, count > 0 && !version, () => onGo('graph'), '关系图')}
      {progress('问数', preparing ?? (onAsk ? '已启用' : queryAvailable ? '' : '待启用'), 3, !!onAsk, queryAvailable && !preparing, onAsk ?? (() => onGo('coach')), onAsk ? '去问数' : canManage ? '问数优化' : '已确认问答')}
    </div>
    <div className="kbw-metrics">
      {metric('数据集', count, external === count && count ? '全部来自数据源关联' : count ? '知识库已接入的数据' : '上传或关联数据表', 'table', 'datasets')}
      {canManage && metric('业务规则', ruleCount ?? '—', count ? '供问数理解业务口径' : '数据就绪后可添加', 'terms', 'rules')}
      {canManage && metric('公共视图', publishedViews ?? '—', count ? '已发布的公共视图' : '建模后可用', 'graph', 'assets')}
      <button type="button" className="kbw-card kbw-metric kbw-publish-metric" onClick={onReview ?? (() => onGo('graph'))}>
        <span><Icon name="file" size="sm" />发布状态</span>
        <strong data-live={version > 0 || undefined}>{version ? `v${version} 已发布` : '尚未发布'}</strong>
        <small>{version ? [publishedDate, '问数使用线上版本'].filter(Boolean).join(' · ') : '发布后可基于此库提问'}</small>
      </button>
    </div>
    <div className="kbw-overview-columns">
      {count > 0 ? <section className="kbw-card">
        <div className="kbw-card-head"><h2>数据集</h2><small>{count} 个</small><button className="da-btn" type="button" onClick={() => onGo('datasets')}>查看全部<Icon name="chevron" size="sm" /></button></div>
        <div className="kbw-overview-table"><table><thead><tr><th>数据集</th><th>字段</th><th>字段说明</th><th>操作</th></tr></thead>
          <tbody>{datasets.slice(0, 5).map(table => {
            const described = describedColumns(table);
            const missing = table.columns.length - described;
            return <tr key={table.id}><td><button title={table.name} type="button" onClick={() => onDataset(table.id)}><Icon name="table" size="sm" />{table.name}</button></td>
              <td>{table.columns.length}</td><td>{missing ? <button type="button" className="kbw-field-count" data-missing title={`缺 ${missing} 个字段的说明`}
                onClick={() => onDataset(table.id, 'fields')}>{described}/{table.columns.length} · 缺 {missing}</button> : <span className="kbw-field-covered">已覆盖</span>}</td>
              <td><button className="kbw-field-link" type="button" aria-label={`查看 ${table.name}`} onClick={() => onDataset(table.id)}>查看</button></td></tr>;
          })}</tbody></table></div>
        <div className="kbw-card-foot">共 {count} 个数据集</div>
      </section> : <section className="kbw-card kbw-empty-intro kbw-overview-intro">
        <h2><Icon name="database" size="sm" />{canManage ? '先放一份数据进来' : '暂无数据集'}</h2>
        <p>{canManage ? '可以上传 Excel、CSV，也可以关联数据库中的业务表。' : '添加数据后，这里会展示数据集和模型准备情况。'}</p>
        {canManage && <div className="kbw-page-actions">
          <button type="button" className="da-btn da-btn-primary" onClick={onUpload}><Icon name="upload" size="sm" />上传文件</button>
          <button type="button" className="da-btn" onClick={onAssociate}><Icon name="database" size="sm" />关联数据源</button>
        </div>}
        {canManage && <small>支持 Excel、CSV 与多工作表文件。</small>}
      </section>}
      <div className="kbw-side-cards kbw-overview-side">
        {canManage && !count && <section className="kbw-card kbw-card-pad">
          <h2><Icon name="file" size="sm" />没有文件？</h2><p>用内置示例数据体验从建模到问数的流程。</p>
          <button type="button" className="da-btn" onClick={onUpload}>查看示例数据<Icon name="chevron" size="sm" /></button>
        </section>}
        {count > 0 && <section className="kbw-card kbw-card-pad">
          <h2><Icon name="file" size="sm" />发布</h2>
          <p>{releaseText}</p>
          <button type="button" className="da-btn" onClick={onReview ?? (() => onGo('graph'))}>{onReview ? '查看审阅单' : '查看模型'}<Icon name="chevron" size="sm" /></button>
        </section>}
        <section className="kbw-card kbw-card-pad">
          <h2><Icon name="shield" size="sm" />成员与权限</h2>
          <p>{group.ownerUsername && `创建者 ${group.ownerUsername} · `}你的角色为{canManage ? '「管理」' : '「使用」'}。</p>
          <button className="da-btn" type="button" onClick={() => onGo('members')}>{canManage ? '管理成员' : '查看权限'}</button>
        </section>
        {count > 0 && <KnowledgeSourceConnection datasets={datasets} canManage={canManage} compact onAssociate={onAssociate} />}
      </div>
    </div>
  </div>;
}
