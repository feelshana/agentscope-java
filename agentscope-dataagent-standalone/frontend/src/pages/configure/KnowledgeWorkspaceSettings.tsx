import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { deleteGroup, updateGroup, type GroupDetail } from '../../api/datasets';
import { getFactQueryWindowCandidates, type FactQueryWindowCandidate, type ModelingWorkflow } from '../../api/modelingWorkflow';
import { FactQueryWindowSettings } from '../../components/FactQueryWindowSettings';
import Icon from '../../components/Icon';
import { WorkspaceHeading } from './KnowledgeWorkspaceOverview';
import type { KnowledgeModelingHost } from './knowledgeWorkspaceRoutes';
import { datasetPath } from './knowledgeWorkspaceRoutes';

export default function KnowledgeWorkspaceSettings({ detail, version, workflow, leave, onChanged, onPublish }: {
  detail: GroupDetail;
  version: number;
  workflow: ModelingWorkflow | null;
  leave: KnowledgeModelingHost['leave'];
  onChanged: () => Promise<void>;
  onPublish: () => void;
}) {
  const { group, datasets } = detail;
  const navigate = useNavigate();
  const factAnchor = useLocation().hash === '#fact-window';
  const factCard = useRef<HTMLElement>(null);
  useLayoutEffect(() => {
    if (factAnchor) factCard.current?.scrollIntoView({ block: 'start' });
  }, [factAnchor]);
  const canManage = group.canManage && !group.deletionPending;
  const [name, setName] = useState(group.name);
  const [description, setDescription] = useState(group.description ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [copyNotice, setCopyNotice] = useState('');
  const [notice, setNotice] = useState('');
  const [policies, setPolicies] = useState<FactQueryWindowCandidate[]>();
  const [policyError, setPolicyError] = useState('');
  const [policyRevision, setPolicyRevision] = useState(0);
  const [batchSignal, setBatchSignal] = useState(0);
  const [scopeRequest, setScopeRequest] = useState<{ id: string; signal: number }>();
  const [unsupportedId, setUnsupportedId] = useState<string>();
  const unavailableDialog = useRef<HTMLDialogElement>(null);
  const dirty = name !== group.name || description !== (group.description ?? '');
  const external = datasets.filter(table => table.origin === 'datasource').length;
  useEffect(() => {
    if (!canManage || !external) return;
    let active = true;
    setPolicyError('');
    void getFactQueryWindowCandidates(group.id).then(rows => { if (active) setPolicies(rows); }).catch(e => {
      if (active) setPolicyError(e instanceof Error ? e.message : '无法读取时间范围');
    });
    return () => { active = false; };
  }, [group.id, canManage, external, policyRevision]);
  useLayoutEffect(() => {
    const guard = () => !busy && (!dirty || window.confirm('有尚未保存的设置，放弃这些修改吗？'));
    leave.current = guard;
    return () => { if (leave.current === guard) leave.current = null; };
  }, [leave, busy, dirty]);
  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);
  async function save() {
    if (!canManage || !dirty || busy) return;
    const nextName = name.trim();
    if (!/^[一-龥A-Za-z0-9_-]{1,100}$/.test(nextName)) {
      setError('名称需为 1–100 个中文、字母、数字、- 或 _。');
      return;
    }
    setError(''); setNotice(''); setBusy(true);
    try {
      const text = description.trim();
      await updateGroup(group.id, nextName, text);
      setName(nextName); setDescription(text);
      await onChanged();
      setNotice('知识库信息已保存。');
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }
  async function remove() {
    if (!group.canManage || busy) return;
    if (!window.confirm('删除该知识库？将停止相关执行，清理导入数据表、建模文件和库内配置。外部数据库原表、历史回答及附件保留。')) return;
    setError(''); setBusy(true);
    try { await deleteGroup(group.id); navigate('/configure/datasets', { replace: true }); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); await onChanged(); }
    finally { setBusy(false); }
  }
  const windowByDataset = new Map(policies?.map(item => [item.datasetId, item]));
  const unsupported = datasets.find(table => table.id === unsupportedId);
  useEffect(() => {
    if (unsupported && !unavailableDialog.current?.open) unavailableDialog.current?.showModal();
  }, [unsupported]);
  const renderWindowRow = (table: GroupDetail['datasets'][number]) => {
    const policy = windowByDataset.get(table.id);
    const status = table.origin !== 'datasource' ? '文件导入 · 不适用'
      : policy?.tableRole === 'UNKNOWN' ? '待确认表角色'
      : policy?.mode === 'LIMITED' ? `已设 ${policy.windowDays} 天`
      : policy?.mode === 'UNLIMITED' ? '不限制' : policy ? '未设置' : policies ? '暂无适用的时间范围' : policyError ? '暂未读到配置' : '读取中';
    return <div className="kbw-policy" key={table.id}>
      <div className="kbw-policy-name"><strong title={table.name}>{table.name}</strong>{policy?.timeColumn && <small title={policy.timeColumn}>时间字段 {policy.timeColumn}</small>}</div>
      <span className="kbw-policy-status" data-configured={!!policy?.mode || undefined}>{status}</span>
      <button type="button" className="kbw-field-link" aria-label={`设置范围 ${table.name}`}
        disabled={table.origin === 'datasource' && !policies && !policyError}
        onClick={() => {
          if (policy) setScopeRequest(previous => ({ id: table.id, signal: (previous?.signal ?? 0) + 1 }));
          else setUnsupportedId(table.id);
        }}>设置范围</button>
    </div>;
  };
  return <div className="kbw-page kbw-settings-page" data-fact-anchor={factAnchor || undefined}><div className="kbw-settings-column">
      <WorkspaceHeading title="设置" description="管理知识库基本信息、查询范围和共享配置。" />
      {error && <p className="kw-alert" role="alert">{error}</p>}
      {notice && <p className="kbw-save-notice" role="status">{notice}</p>}
      <div className="kbw-settings-layout">
      <div className="kbw-settings-grid">
        <form id="kbw-settings-info" className="kbw-card kbw-card-pad" onSubmit={event => { event.preventDefault(); void save(); }}>
          <h2><Icon name="settings" size="sm" />基本信息</h2>
          <label>知识库名称<input className="da-input" required maxLength={100} value={name} readOnly={!canManage} disabled={busy} onChange={e => setName(e.target.value)} /></label>
          <label>业务说明<textarea className="da-input" rows={3} maxLength={1000} value={description} readOnly={!canManage} disabled={busy} onChange={e => setDescription(e.target.value)} /></label>
          <dl className="kbw-meta-list"><div><dt>知识库 ID</dt><dd className="kbw-copy-id"><code>{group.id}</code><button type="button" className="kbw-field-link" onClick={async () => {
            try { await navigator.clipboard.writeText(group.id); setCopyNotice('已复制'); } catch { setCopyNotice('复制失败'); }
          }}>{copyNotice || '复制'}</button></dd></div><div><dt>创建信息</dt>
            <dd>{[group.createdAt ? new Date(group.createdAt).toLocaleDateString('zh-CN') : '', group.ownerUsername, `${datasets.length} 个数据集`].filter(Boolean).join(' · ')}</dd></div></dl>
        </form>
        {canManage && <section className="kbw-card kbw-card-pad" id="fact-window" ref={factCard}>
          <div className="kbw-settings-card-head"><h2><Icon name="clock" size="sm" />事实表时间范围</h2>
            <button type="button" className="da-btn da-btn-sm" disabled={!datasets.length} onClick={() => {
              setScopeRequest(undefined);
              setBatchSignal(value => value + 1);
            }}>批量配置</button></div>
          <FactQueryWindowSettings groupId={group.id} hideButton openSignal={batchSignal}
            inspectTables={datasets.map(table => ({ id: table.id, name: table.name,
              reason: table.origin !== 'datasource' ? '文件导入 · 不适用' : '没有可配置的事实表时间字段' }))}
            onSaved={() => setPolicyRevision(value => value + 1)} />
          <p>配置外部事实表可覆盖的查询时间。保存后生效。</p>
          {policyError && <p role="alert">{policyError}</p>}
          {datasets.map(renderWindowRow)}
          {!datasets.length && <p>当前没有数据集。</p>}
          {scopeRequest && <FactQueryWindowSettings key={scopeRequest.id} groupId={group.id} datasetId={scopeRequest.id}
            openSignal={scopeRequest.signal} hideButton onSaved={() => setPolicyRevision(revision => revision + 1)} />}
          {unsupported && <dialog ref={unavailableDialog} className="mo-form-dialog" aria-label="设置查询时间范围" onClose={() => setUnsupportedId(undefined)}>
            <header className="mo-dialog-head"><strong>设置查询时间范围</strong>
              <button type="button" className="da-btn" onClick={() => unavailableDialog.current?.close()}>关闭</button></header>
            <div className="mo-dialog-body"><strong>{unsupported.name}</strong><p>{unsupported.origin !== 'datasource'
              ? '文件导入表无需配置事实表查询时间范围。'
              : policyError ? '当前无法读取此表的范围配置，请刷新页面后重试。'
                : '此表暂无可保存的时间范围。仅外部事实表在采样识别出时间字段后可配置。'}</p></div>
            <footer className="mo-dialog-footer"><button type="button" className="da-btn" onClick={async () => {
              if (await leave.current?.()) navigate(datasetPath(group.id, unsupported.id) + '?tab=fields');
            }}>查看字段信息</button></footer>
          </dialog>}
        </section>}
        {group.canManage && <section className="kbw-card kbw-card-pad kbw-danger kbw-settings-danger"><h2><Icon name="warn" size="sm" />危险区</h2>
          <p>删除会清理导入数据、模型和库内配置。外部数据库原表、历史回答与附件保留。</p>
          <button type="button" className="da-btn" disabled={busy} onClick={() => void remove()}>{group.deletionPending ? '重试删除' : '删除知识库'}</button>
        </section>}
      </div>
    <div className="kbw-side-cards">
      <section className="kbw-card kbw-card-pad"><h2><Icon name="file" size="sm" />版本与发布</h2>
        <p>{version ? `已发布 v${version}` : '尚未发布'}{workflow?.changedAssets.length ? ` · ${workflow.changedAssets.length} 处草稿` : ''}</p>
        {canManage && <button type="button" className="da-btn" onClick={onPublish}>查看变更</button>}
      </section>
      <section className="kbw-card kbw-card-pad"><h2><Icon name="database" size="sm" />数据与共享</h2><p>{external ? `已关联 ${external} 张数据库表。` : '尚未关联外部数据库表。'}</p>
        {canManage && <button type="button" className="da-btn" onClick={() => { if (leave.current?.()) navigate('/configure/datasources'); }}>管理数据源</button>}
      </section>
    </div>
    </div>
    {canManage && <footer className="kbw-settings-save">
      {dirty && <span>有未保存的修改</span>}
      <button type="button" className="da-btn" disabled={busy || !dirty} onClick={() => { setName(group.name); setDescription(group.description ?? ''); setNotice(''); setError(''); }}>取消</button>
      <button className="da-btn da-btn-primary" form="kbw-settings-info" disabled={busy || !dirty}>{busy ? '保存中…' : '保存修改'}</button>
    </footer>}
    </div></div>;
}
