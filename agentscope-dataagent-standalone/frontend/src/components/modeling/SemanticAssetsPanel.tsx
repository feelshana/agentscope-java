import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { assetExamples, assetImpact, deleteAsset, getAsset, getAssetCatalog, reviewAsset, saveAsset } from '../../api/semanticAssets';
import type { AssetCatalog, AssetDetail, AssetExample, AssetImpact, SemanticAsset } from '../../api/semanticAssets';
import AssetActionDialog from './AssetActionDialog';
import AssetVerificationPanel from './AssetVerificationPanel';
import { assetDescription, assetKey, assetRows, assetTask } from './assetCatalog';
import type { ViewRow } from './assetCatalog';
import IncompleteViewDialog from './IncompleteViewDialog';
import ReadableCode from '../ReadableCode';
import Icon from '../Icon';
import { MaterialsCatalog, WorkspaceBatchInput, WorkspaceBatchToggle, WorkspaceBatchTools, WorkspaceEmpty, WorkspaceObjectTools, WorkspaceToolbar } from './ModelingWorkspace';
import { WorkspaceMenu } from '../KnowledgeWorkspace';
import { removeWorkspaceItems, updateWorkspaceSelection } from './workspaceRemoval';
import EngineeringDialog from './EngineeringDialog';
import SourceFileEditor from './SourceFileEditor';
import { fileLanguage } from './ModelSnapshotPanel';
import './ModelingOptimization.css';
import './SemanticAssetsPanel.css';

type Group = 'pending' | 'library';
type Version = 'draft' | 'published';
type Stage = 'overview' | 'verify' | 'status';
type RemovalEntry = { row: ViewRow; impact?: AssetImpact; error?: string };
type RemovalRequest = { entries: RemovalEntry[]; batch: boolean; finished?: { total: number; staged: number } };
const KIND_LABEL = { view: '查询模板', ref_sql: '加工表' } as const;
const STATUS = {
  PUBLISHED: '已发布', REVIEW_PENDING: '待核查', VERIFY_PENDING: '待验证', VERIFIED: '待发布',
  INCOMPLETE: '草稿未完成', REMOVAL_PENDING: '待下架',
};
const isTimeColumn = (c: { name: string; type: string }) => /date|time|月份|日期|时间/i.test(c.name + ' ' + c.type);
const errorText = (error: unknown) => error instanceof Error ? error.message : String(error);
const fileName = (path: string) => path.slice(path.lastIndexOf('/') + 1);
const canRemove = (row: ViewRow) => !!row.draft && !row.incomplete;

export default function SemanticAssetsPanel({ groupId, assistantIdle, paired, publishedVersion, onDraft, onAsk, onPublish }: {
  groupId: string;
  assistantIdle: boolean;
  paired: boolean;
  publishedVersion: number;
  onAsk: (asset: SemanticAsset, version: Version, published?: SemanticAsset) => void;
  onPublish: () => void;
  onDraft: (text: string, label: string) => boolean;
}) {
  const [params, setParams] = useSearchParams();
  const [catalog, setCatalog] = useState<AssetCatalog>();
  const loadSequence = useRef(0);
  const [detail, setDetail] = useState<{ version: Version; value: AssetDetail }>();
  const [loadError, setLoadError] = useState('');
  const [detailError, setDetailError] = useState('');
  const [detailAttempt, setDetailAttempt] = useState(0);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('');
  const [directoryOpen, setDirectoryOpen] = useState(true);
  const [batchGroup, setBatchGroup] = useState<Group>();
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [helpOpen, setHelpOpen] = useState(false);
  const [draftOpen, setDraftOpen] = useState(false);
  const [need, setNeed] = useState('');
  const [examples, setExamples] = useState<AssetExample[]>();
  const [timeColumn, setTimeColumn] = useState<{ context: string; value: string }>();
  const [file, setFile] = useState('');
  const [filesOpen, setFilesOpen] = useState(false);
  const [sourceEdit, setSourceEdit] = useState<string>();
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const [incompleteOpen, setIncompleteOpen] = useState(false);
  const [removal, setRemoval] = useState<RemovalRequest>();
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const detailScroller = useRef<HTMLDivElement>(null);

  const rows = useMemo(() => catalog ? assetRows(catalog) : [], [catalog]);
  const pending = rows.filter(row => assetTask(row) !== 'PUBLISHED');
  const library = rows.filter(row => row.published);
  const selected = params.get('viewItem');
  const group: Group = params.get('viewGroup') === 'library' ? 'library'
    : params.get('viewGroup') === 'pending' || pending.length > 0 || library.length === 0 ? 'pending' : 'library';
  const requestedVersion = params.get('viewVersion');
  const stage: Stage = params.get('viewStage') === 'verify' ? 'verify' : params.get('viewStage') === 'status' ? 'status' : 'overview';
  const candidates = group === 'library' ? library : pending;
  const search = query.trim().toLowerCase();
  const matching = candidates.filter(row => {
    const description = group === 'library' ? row.published?.description : row.draft?.description ?? row.published?.description;
    return (!filter || assetTask(row) === filter)
      && (row.name.toLowerCase().includes(search) || !!assetDescription(description).title?.toLowerCase().includes(search));
  });
  const batchActive = !paired && candidates.length > 0 && batchGroup === group;
  const pickedRows = matching.filter(row => canRemove(row) && picked.has(assetKey(row)));
  const clearPicked = () => setPicked(new Set());
  const current = (paired ? rows : matching).find(row => assetKey(row) === selected) ?? matching[0];
  const version: Version = current?.published && (requestedVersion === 'published' || (!current.draft && !current.incomplete)
    || (group === 'library' && requestedVersion !== 'draft')) ? 'published' : 'draft';
  const selectedAsset = version === 'published' ? current?.published : current?.draft;
  const currentKey = current ? assetKey(current) : '';
  const currentName = current?.name;
  const currentKind = current?.kind;
  const assetHash = selectedAsset?.hash;
  const task = current ? assetTask(current) : undefined;
  const readOnly = version === 'published';
  const value = detail?.version === version && detail.value.asset.hash === assetHash
    && assetKey(detail.value.asset) === currentKey ? detail.value : undefined;
  const asset = value?.asset;
  const currentStage = stage === 'verify' && asset?.status === 'REVIEW_PENDING' ? 'overview' : stage;
  const source = value?.files.find(item => item.path === file) ?? value?.files[0];
  const edited = sourceEdit !== undefined && !!source && sourceEdit !== source.content;
  const selectedTimeContext = currentKey + ':' + assetHash;
  const selectedTime = timeColumn?.context === selectedTimeContext ? timeColumn.value
    : asset?.review?.timeColumns[0] ?? value?.outputColumns.find(isTimeColumn)?.name ?? '';
  const waiting = !assistantIdle || !!busy;

  const reload = useCallback(async () => {
    const sequence = ++loadSequence.current;
    try {
      const next = await getAssetCatalog(groupId);
      if (sequence === loadSequence.current) { setCatalog(next); setLoadError(''); }
    } catch (error) {
      if (sequence === loadSequence.current) setLoadError(errorText(error));
    }
  }, [groupId]);
  useEffect(() => {
    if (!filesOpen) void reload();
    const changed = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId !== groupId || filesOpen) return;
      void reload();
      setDetailAttempt(value => value + 1);
    };
    window.addEventListener('modeling:updated', changed);
    return () => window.removeEventListener('modeling:updated', changed);
  }, [groupId, reload, filesOpen]);
  useEffect(() => {
    if (!currentName || !currentKind || !assetHash) return;
    let active = true;
    setDetailError('');
    void getAsset(groupId, { kind: currentKind, name: currentName }, version === 'published')
      .then(next => { if (active) setDetail({ version, value: next }); })
      .catch(error => { if (active) setDetailError(errorText(error)); });
    return () => { active = false; };
  }, [groupId, currentKind, currentName, assetHash, version, detailAttempt]);
  useEffect(() => {
    if (!draftOpen || examples) return;
    let active = true;
    void assetExamples(groupId).then(value => { if (active) setExamples(value); })
      .catch(() => { if (active) setExamples([]); });
    return () => { active = false; };
  }, [draftOpen, examples, groupId]);

  const locate = (nextGroup: Group, item?: ViewRow | SemanticAsset, nextVersion?: Version, nextStage: Stage = 'overview') => {
    detailScroller.current?.scrollTo({ top: 0 });
    setParams(previous => {
      const next = new URLSearchParams(previous);
      next.set('viewGroup', nextGroup);
      if (item) next.set('viewItem', assetKey(item)); else next.delete('viewItem');
      if (nextVersion) next.set('viewVersion', nextVersion); else next.delete('viewVersion');
      if (nextStage !== 'overview') next.set('viewStage', nextStage); else next.delete('viewStage');
      return next;
    }, { replace: true });
  };
  const switchGroup = (next: Group) => {
    if (busy || group === next) return;
    setQuery(''); setFilter(''); setError(''); setNotice(''); setBatchGroup(undefined); clearPicked(); locate(next);
  };
  const run = async (name: string, action: () => Promise<void>) => {
    setBusy(name); setError(''); setNotice('');
    try { await action(); return true; } catch (reason) { setError(errorText(reason)); return false; }
    finally { setBusy(''); }
  };
  const apply = (next: AssetDetail) => {
    loadSequence.current++;
    setDetail({ version: 'draft', value: next });
    setCatalog(previous => previous && { ...previous, draft: previous.draft.map(row => assetKey(row) === assetKey(next.asset) ? next.asset : row) });
  };
  const updated = () => window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
  const closeFiles = () => { setFilesOpen(false); setSourceEdit(undefined); setConfirmDiscard(false); setDetailAttempt(n => n + 1); };
  const requestCloseFiles = () => { if (edited) setConfirmDiscard(true); else closeFiles(); };
  const draftAction = <button type="button" className="da-btn da-btn-primary" onClick={() => setDraftOpen(true)}><Icon name="plus" size="sm" />起草公共视图</button>;
  const openRemoval = (targets: ViewRow[], batch = false, finished?: RemovalRequest['finished']) => {
    const request: RemovalRequest = { entries: targets.map(row => ({ row })), batch, finished };
    setRemoval(request);
    const inspect = async () => {
      const entries: RemovalEntry[] = await Promise.all(targets.map(async row => {
        try { return { row, impact: await assetImpact(groupId, row) }; }
        catch (error) { return { row, error: errorText(error) }; }
      }));
      if (entries.every(entry => entry.error)) return entries;
      // Resolve the displayed publication status against the same or a later catalog read.
      const latest = new Map(assetRows(await getAssetCatalog(groupId)).map(row => [assetKey(row), row]));
      return entries.map(entry => {
        const row = latest.get(assetKey(entry.row));
        if (entry.error) return entry;
        return row && canRemove(row) ? { ...entry, row } : { row: entry.row, error: '视图状态已变化，请重新选择。' };
      });
    };
    void inspect().then(entries => setRemoval(state => state === request ? { ...request, entries } : state))
      .catch(error => setRemoval(state => state === request
        ? { ...request, entries: targets.map(row => ({ row, error: errorText(error) })) } : state));
  };
  const confirmRemoval = (request: RemovalRequest) => run('delete', async () => {
    const { removed, failures } = await removeWorkspaceItems(request.entries, async ({ row, impact }) => {
      if (!impact?.revision) throw new Error('请先检查移除影响。');
      await deleteAsset(groupId, row, impact.revision);
    });
    const finished = { total: (request.finished?.total ?? 0) + removed.length,
      staged: (request.finished?.staged ?? 0) + removed.filter(({ row }) => row.published).length };
    if (removed.length) {
      const keys = new Set(removed.map(({ row }) => assetKey(row)));
      setCatalog(previous => previous && { ...previous, draft: previous.draft.filter(item => !keys.has(assetKey(item))) });
      setPicked(previous => new Set([...previous].filter(key => !keys.has(key))));
      if (!request.batch) {
        const { row } = removed[0];
        setFilter(''); setQuery('');
        if (row.published) locate(group, row, 'published'); else locate(group);
      } else if (keys.has(currentKey)) {
        locate(group);
      }
    }
    if (failures.length) {
      setRemoval({ batch: request.batch, finished,
        entries: failures.map(({ target, message }) => ({ row: target.row, error: message })) });
    } else {
      setRemoval(undefined);
      setNotice(request.batch ? `已处理 ${finished.total} 项视图${finished.staged ? `，其中 ${finished.staged} 项待发布下架` : ''}。`
        : finished.staged ? '已标记为待下架，发布后生效。' : '已移除视图草稿。');
    }
    updated();
  });
  const objectName = current && (assetDescription(selectedAsset?.description ?? current.published?.description).title || current.name);
  const objectTools = current && <>
    {current.published && (current.draft || current.incomplete) && task !== 'PUBLISHED' &&
      <div className="mo-work-segment" role="group" aria-label="查看视图版本">
        <button type="button" className="da-btn" disabled={!!busy} aria-pressed={readOnly}
          onClick={() => locate(group, current, 'published')}>线上 v{publishedVersion}</button>
        <button type="button" className="da-btn" disabled={!!busy} aria-pressed={!readOnly}
          onClick={() => locate(group, current, 'draft')}>当前草稿</button>
      </div>}
    {asset && value && <>
      {!paired && task !== 'REMOVAL_PENDING' && <button type="button" className="da-btn" disabled={!!busy}
        onClick={() => onAsk(asset, version, current.published)}><Icon name="chat" size="sm" />边聊边核对</button>}
      {value.files.length > 0 && <button type="button" className="da-btn" disabled={!!busy}
        onClick={() => { setFile(value.files[0].path); setFilesOpen(true); }}><Icon name="code" size="sm" />工程文件</button>}
      {canRemove(current) && <WorkspaceMenu label="视图更多操作" items={[
        { label: current.published ? '下架视图' : '移除草稿', icon: 'trash', danger: true, disabled: waiting || !!loadError,
          onClick: () => openRemoval([current]) },
      ]} />}
    </>}
  </>;

  return <section className={'sm-pane sa-pane mo-optimization mo-workspace va-pane' + (paired ? ' mo-work-paired' : '')} aria-label="公共视图">
    {paired ? <WorkspaceObjectTools compact context={objectName}>{objectTools}</WorkspaceObjectTools>
      : <WorkspaceToolbar label="公共视图范围" active={group} onSelect={switchGroup} tabs={[
      { id: 'pending', label: '待处理', icon: 'list', count: pending.length },
      { id: 'library', label: '视图库', icon: 'model', count: library.length },
    ]} objectActions={batchActive ? <WorkspaceBatchTools count={pickedRows.length}
      label={pickedRows.every(row => row.published) ? '下架选中的视图' : '移除选中的视图'}
      disabled={waiting || !!loadError} onClear={clearPicked} onRemove={() => openRemoval(pickedRows, true)} />
      : objectTools} context={!batchActive && !directoryOpen ? objectName : undefined}>
      <button type="button" className="da-btn mo-work-quiet" onClick={() => setHelpOpen(true)}><Icon name="info" size="sm" />使用指南</button>
      {rows.length > 0 && draftAction}
    </WorkspaceToolbar>}
    {helpOpen && <AssetActionDialog title="公共视图使用指南" onClose={() => setHelpOpen(false)}>
      <p>当多个业务问题需要复用固定的表关联、窗口计算或多步 SQL，可以起草公共视图。字段含义、简单分类或默认过滤可在建模对话中处理。</p>
      <ol className="va-guide">
        <li><strong>起草</strong><span>描述需求或粘贴已有 SQL，助手写入知识库草稿。</span></li>
        <li><strong>核查用途与口径</strong><span>检查粒度、来源、关联和过滤，依赖受限事实表时确认输出时间列。</span></li>
        <li><strong>验证业务问题</strong><span>展开 SQL 后逐题判定。通过的题可另存为问答。</span></li>
        <li><strong>检查并发布</strong><span>确认本次知识库草稿范围后发布，线上问数使用新版本。</span></li>
      </ol>
    </AssetActionDialog>}
    {draftOpen && <AssetActionDialog title="起草公共视图" onClose={() => setDraftOpen(false)}
      actions={<><button type="button" className="da-btn" onClick={() => setDraftOpen(false)}>取消</button>
        <button type="button" className="da-btn da-btn-primary" disabled={!need.trim() || waiting}
          onClick={() => { if (onDraft('请建设语义资产。需求或已有 SQL 如下：\n' + need.trim(), '起草公共视图：' + need.trim())) { setNeed(''); setDraftOpen(false); } }}>交给助手起草</button></>}>
      <p>说明视图要回答哪些问题、一行代表什么。也可以粘贴已有 SQL。</p>
      <textarea className="da-input" rows={5} aria-label="视图需求或已有 SQL" value={need}
        onChange={e => setNeed(e.target.value)} spellCheck={false} placeholder="例如，按统计月份和会员类型汇总在订会员数…" />
      {!!examples?.length && <div className="va-suggestions">{examples.map(item => <button key={item.kind + item.text} type="button"
        className="da-btn da-btn-sm" title={item.text} onClick={() => setNeed(item.text)}>{item.kind}</button>)}</div>}
    </AssetActionDialog>}
    {!catalog ? <p className="mo-work-loading" role="status">{loadError || '正在读取视图…'}
      {loadError && <button type="button" className="da-btn" onClick={() => void reload()}>重试</button>}</p>
      : !paired && !candidates.length ? <div className="va-empty"><WorkspaceEmpty icon={library.length ? 'check' : 'model'}
        title={group === 'pending' ? '暂无待处理视图' : '暂无已发布视图'}
        description={group === 'pending' && library.length ? '线上问数可继续使用已发布视图。'
          : rows.length ? '通过用途核查和问题验证后，视图可随知识库草稿发布。'
            : '将多个问题反复使用的关联或计算整理成一个公共视图。'}>
        {group === 'pending' && library.length > 0 ? <button type="button" className="da-btn da-btn-primary"
          onClick={() => switchGroup('library')}>查看 {library.length} 个已发布视图</button>
          : group === 'library' && pending.length > 0 ? <button type="button" className="da-btn da-btn-primary"
            onClick={() => switchGroup('pending')}>查看待处理视图</button> : draftAction}
      </WorkspaceEmpty></div> : <div className="sa-layout mo-work-grid va-grid" data-catalog-open={directoryOpen}>
      {!paired && <MaterialsCatalog title="视图目录" count={`${matching.length} 项`} open={directoryOpen}
        actions={(batchActive || matching.some(canRemove)) && <WorkspaceBatchToggle active={batchActive}
          onClick={() => { setBatchGroup(batchActive ? undefined : group); clearPicked(); setNotice(''); }} />}
        onToggle={() => setDirectoryOpen(value => !value)} filters={<>
          <input type="search" className="da-input" aria-label="搜索视图" placeholder="搜索视图…" value={query}
            disabled={!!busy} onChange={e => { setQuery(e.target.value); clearPicked(); }} />
          {group === 'pending' && <select className="da-input" aria-label="筛选视图状态" value={filter} disabled={!!busy}
            onChange={e => { setFilter(e.target.value); clearPicked(); }}>
            <option value="">全部状态</option>{[...new Set(candidates.map(assetTask))].map(key => <option key={key} value={key}>{STATUS[key]}</option>)}
          </select>}
        </>}>
        {matching.map(row => {
          const key = assetKey(row);
          const label = assetDescription((group === 'library' ? row.published : row.draft ?? row.published)?.description).title;
          const status = assetTask(row);
          return <li key={key} className={key === currentKey ? 'qc-queue-selected' : ''}>
            <button type="button" aria-current={key === currentKey || undefined} disabled={!!busy} onClick={() => { setError(''); setNotice(''); locate(group, row); }}>
              <strong>{label || row.name}</strong>{label && <small translate="no">{row.name}</small>}
              <span className="mo-item-meta"><span className={'qc-status-pill qc-status-' + (group === 'library' ? 'done' : status === 'REMOVAL_PENDING' ? 'removal' : 'review')}>
                {group === 'library' ? '已发布' : STATUS[status]}</span>
                {group === 'library' && status !== 'PUBLISHED' ? STATUS[status] : KIND_LABEL[row.kind]}</span>
            </button>
            {batchActive && canRemove(row) && <WorkspaceBatchInput label={label || row.name} checked={picked.has(key)}
              disabled={!!busy} onChange={checked => setPicked(previous => updateWorkspaceSelection(previous, key, checked))} />}
          </li>;
        })}
      </MaterialsCatalog>}
      <div ref={detailScroller} className="mo-work-detail sa-detail va-detail" aria-label={objectName}>
        {loadError && <p role="alert">{loadError} <button type="button" className="da-btn da-btn-sm" onClick={() => void reload()}>重新读取</button></p>}
        {error && <p role="alert" className="da-alert da-alert-error">{error}</p>}
        {notice && <p role="status" className="da-small">{notice}</p>}
        {!current ? <WorkspaceEmpty icon="search" title="没有匹配的视图">
          <button type="button" className="da-btn" onClick={() => { setQuery(''); setFilter(''); clearPicked(); }}>清除筛选</button>
        </WorkspaceEmpty> : current.incomplete && !readOnly ? <WorkspaceEmpty icon="circleAlert" title={current.name}
          description="草稿缺少视图定义。可先查看剩余文件，再决定是否移除草稿。">
          <button type="button" className="da-btn da-btn-primary" onClick={() => setIncompleteOpen(true)}>查看草稿文件</button>
          {current.published && <button type="button" className="da-btn" onClick={() => locate(group, current, 'published')}>查看线上版本</button>}
        </WorkspaceEmpty> : !asset || !value ? <p role={detailError ? 'alert' : 'status'} className="mo-work-loading">{detailError || '正在读取视图详情…'}
          {detailError && <button type="button" className="da-btn" onClick={() => setDetailAttempt(n => n + 1)}>重试</button>}</p>
          : <>
          {(task === 'REMOVAL_PENDING' || (readOnly ? task !== 'PUBLISHED' : asset.status !== 'PUBLISHED')) && <div className="va-next">
            <div><strong>{task === 'REMOVAL_PENDING' ? '待下架' : readOnly ? `有草稿 · ${STATUS[task ?? 'REVIEW_PENDING']}`
              : { REVIEW_PENDING: '核查视图用途与口径', VERIFY_PENDING: '用业务问题核对 SQL', VERIFIED: '验证通过，待发布', PUBLISHED: '' }[asset.status]}</strong>
              <p>{task === 'REMOVAL_PENDING' ? '下架将在下一次发布后生效，线上当前仍可使用。'
                : readOnly ? '线上使用此版本，可以切换到当前草稿继续处理。'
                  : asset.status === 'REVIEW_PENDING' ? '核对粒度、来源、关联和过滤条件。'
                    : asset.status === 'VERIFY_PENDING' ? (asset.review?.verifiedAt ? '上次核对发现问题，修改后可重新验证。' : '展开 SQL，逐题判定取数逻辑是否符合预期。')
                      : '检查本次知识库草稿包含的改动，再确认发布。'}</p></div>
            <div className="mo-work-actions">
              {task === 'REMOVAL_PENDING' || !readOnly && asset.status === 'VERIFIED'
                ? <button type="button" className="da-btn da-btn-primary" disabled={waiting} onClick={onPublish}>检查并发布</button>
                : readOnly && task !== 'PUBLISHED' ? <button type="button" className="da-btn da-btn-primary" disabled={!!busy}
                  onClick={() => locate(group, current, 'draft', task === 'VERIFY_PENDING' ? 'verify' : 'overview')}>
                  {task === 'INCOMPLETE' ? '处理草稿' : task === 'REVIEW_PENDING' ? '去核查' : task === 'VERIFY_PENDING' ? '继续验证' : '查看当前草稿'}
                </button>
                : !readOnly && asset.status === 'REVIEW_PENDING' ? <>
                  {value.needsTimeColumn && <label className="va-time-column"><span>输出时间列</span>
                    {value.outputColumns.length ? <select className="da-input" value={selectedTime} disabled={waiting}
                      onChange={e => setTimeColumn({ context: selectedTimeContext, value: e.target.value })}>
                      <option value="">请选择</option>{value.outputColumns.map(c => <option key={c.name} value={c.name}>{c.name}{c.type ? ' · ' + c.type : ''}</option>)}
                    </select> : <input className="da-input" value={selectedTime} disabled={waiting}
                      onChange={e => setTimeColumn({ context: selectedTimeContext, value: e.target.value })} />}</label>}
                  <button type="button" className="da-btn da-btn-primary" disabled={waiting || !asset.sql?.trim() || value.needsTimeColumn && !selectedTime.trim()}
                    onClick={() => void run('review', async () => {
                      const next = await reviewAsset(groupId, asset, value.needsTimeColumn ? [selectedTime.trim()] : []);
                      setFilter(''); apply(next); locate('pending', current, 'draft', 'verify'); updated();
                    })}>{busy === 'review' ? '提交中…' : '核查通过'}</button>
                </> : !readOnly && asset.status === 'VERIFY_PENDING' && currentStage !== 'verify'
                  ? <button type="button" className="da-btn da-btn-primary" onClick={() => locate(group, current, version, 'verify')}>继续验证</button> : null}
            </div>
          </div>}
          <nav className="va-tabs" aria-label="视图详情">
            {([['overview', '用途与口径'], ['verify', '问题验证'], ['status', '核查与发布状态']] as const).map(([key, label]) =>
              <button key={key} type="button" disabled={!!busy || key === 'verify' && asset.status === 'REVIEW_PENDING'}
                aria-current={currentStage === key ? 'page' : undefined} onClick={() => locate(group, current, version, key)}>{label}</button>)}
          </nav>
          {currentStage === 'overview' && <ViewOverview detail={value} />}
          {currentStage === 'status' && <section className="va-content"><dl className="va-facts">
            <div><dt>当前版本</dt><dd>{readOnly ? `线上 v${publishedVersion}` : '当前草稿'}</dd></div>
            <div><dt>状态</dt><dd>{task === 'REMOVAL_PENDING' ? '待下架' : STATUS[asset.status]}</dd></div>
            {asset.review?.reviewedAt && <div><dt>用途核查</dt><dd>{asset.review.reviewedBy} · {new Date(asset.review.reviewedAt).toLocaleString('zh-CN')}</dd></div>}
            {asset.review?.verifiedAt && <div><dt>问题验证</dt><dd>{asset.review.passed ? '通过' : '有问题'} · {asset.review.verifiedBy} · {new Date(asset.review.verifiedAt).toLocaleString('zh-CN')}</dd></div>}
            {!!asset.review?.timeColumns.length && <div><dt>输出时间列</dt><dd>{asset.review.timeColumns.join('、')}</dd></div>}
          </dl></section>}
          {currentStage === 'verify' && <AssetVerificationPanel key={currentKey + version + asset.hash} groupId={groupId}
            detail={value} published={readOnly} locked={!assistantIdle} busy={busy} run={run} onChange={next => {
              setFilter(''); apply(next); updated();
              if (next.asset.status === 'VERIFIED') detailScroller.current?.scrollTo({ top: 0 });
            }} />}
          {filesOpen && source && <EngineeringDialog title="视图工程文件" context={asset.name} canClose={() => !busy}
            onClose={requestCloseFiles} toolbar={value.files.length > 1 ? <select className="da-input" aria-label="选择视图文件" value={source.path}
              disabled={!!busy || sourceEdit !== undefined} onChange={e => setFile(e.target.value)}>
              {value.files.map(f => <option key={f.path} value={f.path}>{fileName(f.path)}</option>)}
            </select> : undefined}
            footer={<><span className="eng-message">{sourceEdit === undefined && readOnly ? `线上 v${publishedVersion} · 工程文件` : '当前草稿 · 保存后重新核查和验证。'}</span>
              <div className="eng-buttons">{sourceEdit !== undefined ? <>
                <button type="button" className="da-btn da-btn-sm" disabled={!!busy} onClick={requestCloseFiles}>取消编辑</button>
                <button type="button" className="da-btn da-btn-primary" disabled={!edited || waiting}
                  onClick={() => void run('save', async () => {
                    const next = await saveAsset(groupId, asset, fileName(source.path), sourceEdit);
                    closeFiles(); setQuery(''); setFilter(''); apply(next); locate('pending', current, 'draft'); updated();
                  })}>{busy === 'save' ? '保存中…' : '保存到草稿'}</button>
              </> : current.draft && !current.incomplete && <button type="button" className="da-btn" disabled={waiting}
                onClick={() => {
                  if (readOnly && current.draft?.hash !== asset.hash) { closeFiles(); locate(group, current, 'draft'); return; }
                  setError(''); setSourceEdit(source.content);
                }}>{readOnly && current.draft.hash !== asset.hash ? '查看当前草稿' : '编辑草稿'}</button>}</div></>}>
            {sourceEdit === undefined ? <ReadableCode key={source.path} fill text={source.content} title={source.path} language={fileLanguage(source.path)} />
              : <SourceFileEditor label={`编辑 ${fileName(source.path)}`} value={sourceEdit} onChange={setSourceEdit} disabled={waiting} />}
            {error && <p className="eng-inline-error" role="alert">{error}</p>}
          </EngineeringDialog>}
        </>}
        {incompleteOpen && current?.incomplete && <IncompleteViewDialog key={currentKey} groupId={groupId} name={current.name}
          disabled={waiting} onClose={() => setIncompleteOpen(false)}
          onRemoved={() => { setIncompleteOpen(false); setNotice('已移除未完成的视图草稿。'); updated(); }} />}
      </div>
    </div>}
    {confirmDiscard && <AssetActionDialog title="放弃文件修改" onClose={() => setConfirmDiscard(false)}
      actions={<><button type="button" className="da-btn" onClick={() => setConfirmDiscard(false)}>继续编辑</button>
        <button type="button" className="da-btn da-btn-primary" onClick={closeFiles}>放弃修改</button></>}>
      <p>关闭编辑窗口会放弃本次未保存的修改。</p>
    </AssetActionDialog>}
    {removal && <AssetActionDialog title={removal.batch ? '批量移除视图' : removal.entries[0]?.row.published ? '下架视图' : '移除草稿'} busy={busy === 'delete'}
      onClose={() => setRemoval(undefined)} actions={<>
        <button type="button" className="da-btn" disabled={!!busy} onClick={() => setRemoval(undefined)}>{removal.finished?.total ? '返回列表' : '取消'}</button>
        {removal.entries.some(entry => entry.error) && <button type="button" className="da-btn" disabled={waiting}
          onClick={() => openRemoval(removal.entries.map(entry => entry.row), removal.batch, removal.finished)}>重新检查</button>}
        <button type="button" className="da-btn da-btn-danger" disabled={waiting || removal.entries.some(entry => !entry.impact?.revision || entry.error)}
          onClick={() => void confirmRemoval(removal)}>{busy === 'delete' ? '处理中…' : removal.batch ? '确认批量移除'
            : removal.entries[0]?.row.published ? '确认下架' : '确认移除'}</button>
      </>}>
      {!!removal.finished?.total && <p role="status">已处理 {removal.finished.total} 项。以下 {removal.entries.length} 项仍需检查。</p>}
      <ul className="mo-removal-targets">{removal.entries.map(({ row, impact, error }) => <li key={assetKey(row)}>
        <strong>{assetDescription((row.draft ?? row.published)?.description).title || row.name}</strong>
        <p className="da-small">{row.published ? '待发布下架。线上问数在新版本发布后停止使用此视图。' : '移除草稿视图及引用规则。'}</p>
        {!impact && !error && <p role="status">正在检查引用…</p>}
        {error && <p className="mo-error" role="alert">{error}</p>}
        {impact && <div className="va-impact">
          <p>涉及 {impact.rules.length} 条规则、{impact.questions.length} 道问答。引用问答需复核。</p>
          {!!impact.rules.length && <ul aria-label="将移除的规则">{impact.rules.map((rule, index) => <li key={index}>{rule}</li>)}</ul>}
          {!!impact.questions.length && <ul aria-label="引用该视图的问答">{impact.questions.map((question, index) => <li key={index}>{question}</li>)}</ul>}
        </div>}
      </li>)}</ul>
    </AssetActionDialog>}
  </section>;
}

function ViewOverview({ detail }: { detail: AssetDetail }) {
  const { asset } = detail;
  const { title, sections } = assetDescription(asset.description);
  const summaryLabels = ['适用范围', '数据粒度', '指标', '时间列', '时间范围', '数据来源', '关联'];
  const purpose = sections.find(section => section.heading === '业务用途');
  const limits = sections.filter(section => section.heading === '使用限制');
  const facts = sections.filter(section => summaryLabels.includes(section.heading));
  const notes = sections.filter(section => section.heading !== '业务用途' && section.heading !== '使用限制' && !summaryLabels.includes(section.heading));
  return <section className="va-content" aria-label="用途与口径">
    {purpose && purpose.text !== title && <section className="va-purpose"><h4>业务用途</h4><p>{purpose.text}</p></section>}
    {!!facts.length && <dl className="va-facts">{facts.map((fact, index) => <div key={index}><dt>{fact.heading}</dt><dd>{fact.text}</dd></div>)}</dl>}
    {(limits.length > 0 || detail.fixedDates.length > 0 || detail.warnings.length > 0) && <section className="va-limits">
      <h4><Icon name="info" size="sm" />使用限制</h4>
      {limits.map((limit, index) => <p key={index}>{limit.text}</p>)}
      {detail.warnings.map(warning => <p key={warning}>{warning}</p>)}
      {!!detail.fixedDates.length && <p>SQL 含固定日期 {detail.fixedDates.join('、')}，请核对是否为固定业务口径。</p>}
    </section>}
    {!sections.length && <p className="da-small">还没有业务说明。可以在建模对话中补充用途、粒度和口径。</p>}
    {notes.map((note, index) => <section className="va-notes" key={index}><h4>{note.heading}</h4><p>{note.text}</p></section>)}
    {asset.sql?.trim() ? <details className="qc-detail-fold" open={asset.status === 'REVIEW_PENDING' || undefined}>
      <summary>查看视图 SQL</summary><ReadableCode text={asset.sql} language="sql" title="视图 SQL" />
    </details> : <p className="da-small">草稿尚未写入 SQL。请在建模对话中补齐定义后再核查。</p>}
  </section>;
}
