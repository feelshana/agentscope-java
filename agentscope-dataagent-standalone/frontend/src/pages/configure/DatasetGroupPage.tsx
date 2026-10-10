import React, { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Navigate, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import AssociateTablesModal from '../../components/AssociateTablesModal';
import EmptyIllustration from '../../components/EmptyIllustration';
import Icon, { IconName } from '../../components/Icon';
import { KnowledgeBreadcrumb, WorkspaceMenu } from '../../components/KnowledgeWorkspace';
import { getModelingWorkflow } from '../../api/modelingWorkflow';
import type { ModelingWorkflow } from '../../api/modelingWorkflow';
import {
  deleteDataset,
  getGroupDetail,
  GroupDetail,
  ImportTask,
  listImportTasks,
  listSheets,
  retryImportTask,
  uploadDataset,
  updateGroup,
} from '../../api/datasets';
import {
  getUploadStatuses,
  mutateUploadStatuses,
  subscribeUploadStatuses,
  UploadStatus,
} from '../../state/uploadProgress';

type View = 'files' | 'modeling';

const STATUS_LABEL: Record<UploadStatus['status'], string> = {
  queued: '排队中',
  uploading: '上传/解析中',
  ready: '完成',
  failed: '失败',
};

const TASK_STATUS_LABEL: Record<string, string> = {
  RECEIVED: '已接收',
  SCANNING: '扫描中',
  WRITING: '写入中',
  VERIFYING: '校验中',
  PUBLISHED: '已发布',
  FAILED: '失败',
  INTERRUPTED: '已中断',
};

function taskStatusColor(status: string): string {
  switch (status) {
    case 'PUBLISHED':
      return 'var(--da-success)';
    case 'FAILED':
    case 'INTERRUPTED':
      return 'var(--da-danger)';
    default:
      return 'var(--da-primary)';
  }
}

const NAV_ITEMS: { key: View; icon: IconName; label: string }[] = [
  { key: 'files', icon: 'list', label: '数据集' },
  { key: 'modeling', icon: 'model', label: '语义建模' },
];

/** Knowledge workspace with a common header, top tabs and a single content area.
 * Upload/import progress and retry actions remain visible above the active view.
 * Modeling keeps its existing full-screen route and legacy URL redirect.
 */
export default function DatasetGroupPage() {
  const { groupId = '' } = useParams();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [detail, setDetail] = useState<GroupDetail | null>(null);
  const [workflow, setWorkflow] = useState<ModelingWorkflow | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [editingGroup, setEditingGroup] = useState(false);
  const [groupName, setGroupName] = useState('');
  const [groupDescription, setGroupDescription] = useState('');
  const uploadStatuses = useSyncExternalStore(subscribeUploadStatuses, () =>
    getUploadStatuses(groupId),
  );
  const [importTasks, setImportTasks] = useState<ImportTask[]>([]);
  const [associateOpen, setAssociateOpen] = useState(false);
  const [associatingTables, setAssociatingTables] = useState<Set<string>>(new Set());
  /** 多工作表选择弹窗。 */
  interface SheetPick {
    file: File;
    sheets: string[];
    selected: string;
  }
  const [sheetPicks, setSheetPicks] = useState<SheetPick[]>([]);
  const [sheetPickerBusy, setSheetPickerBusy] = useState(false);
  const fileRef = useRef<HTMLInputElement | null>(null);

  // Retired graph/tree deep links show datasets without mounting their components.
  const view: View = searchParams.get('view') === 'modeling' ? 'modeling' : 'files';
  const setView = (v: View) => {
    const next = new URLSearchParams(searchParams);
    next.set('view', v);
    setSearchParams(next, { replace: true });
  };

  const refresh = useCallback(async () => {
    try {
      setDetail(await getGroupDetail(groupId));
      setWorkflow(await getModelingWorkflow(groupId).catch(() => null));
      setError(null);
      setAssociatingTables(new Set());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    // 加载导入任务列表
    try {
      setImportTasks(await listImportTasks(groupId));
    } catch {
      /* non-critical */
    }
  }, [groupId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // 有活动任务时每 3 秒轮询一次后端状态；全部终态后停止并刷新数据集列表。
  const hasActiveTask = importTasks.some(t =>
    ['RECEIVED', 'SCANNING', 'WRITING', 'VERIFYING'].includes(t.status),
  );
  useEffect(() => {
    if (!hasActiveTask) return;
    const id = setInterval(async () => {
      try {
        setImportTasks(await listImportTasks(groupId));
      } catch {
        /* non-critical */
      }
    }, 3000);
    return () => clearInterval(id);
  }, [groupId, hasActiveTask]);

  // 当所有导入任务都到终态时，再刷新一次以拉取新创建的数据集。
  const allTerminal =
    importTasks.length > 0 &&
    !importTasks.some(t => ['RECEIVED', 'SCANNING', 'WRITING', 'VERIFYING'].includes(t.status));
  const prevAllTerminalRef = useRef(false);
  useEffect(() => {
    if (allTerminal && !prevAllTerminalRef.current) {
      refresh();
    }
    prevAllTerminalRef.current = allTerminal;
  }, [allTerminal, refresh]);

  // Always default to the files view on entry.

  function patchStatus(name: string, patch: Partial<UploadStatus>) {
    mutateUploadStatuses(groupId, prev =>
      prev.map(s => (s.name === name ? { ...s, ...patch } : s)),
    );
  }

  async function doUpload(
    files: File[],
    sheetMap: Map<string, string>,
  ) {
    mutateUploadStatuses(groupId, prev => [
      ...prev,
      ...files.map(f => ({
        name: f.name,
        status: 'queued' as const,
        file: f,
        selectedSheet: sheetMap.get(f.name),
      })),
    ]);
    setBusy(true);
    setError(null);

    files.forEach(f => patchStatus(f.name, { status: 'uploading' }));

    const results = await Promise.allSettled(
      files.map(async f => {
        const name = f.name.replace(/\.[^.]+$/, '');
        const selectedSheet = sheetMap.get(f.name);
        await uploadDataset(groupId, name, f, false, selectedSheet);
        return { file: f, name, selectedSheet };
      }),
    );

    const overwriteCandidates: { file: File; name: string; selectedSheet?: string }[] = [];
    results.forEach((r, i) => {
      if (r.status === 'fulfilled') {
        mutateUploadStatuses(groupId, prev => prev.filter(x => x.name !== files[i].name));
      } else {
        const msg = r.reason instanceof Error ? r.reason.message : String(r.reason);
        if (msg.includes('already exists')) {
          overwriteCandidates.push({
            file: files[i],
            name: files[i].name.replace(/\.[^.]+$/, ''),
            selectedSheet: sheetMap.get(files[i].name),
          });
        } else {
          patchStatus(files[i].name, { status: 'failed', error: msg });
        }
      }
    });

    if (overwriteCandidates.length > 0) {
      const names = overwriteCandidates.map(c => c.name).join('、');
      if (
        window.confirm(
          `数据集「${names}」已存在，是否覆盖？新版本通过完整校验后替换当前数据。`,
        )
      ) {
        const overwriteResults = await Promise.allSettled(
          overwriteCandidates.map(c =>
            uploadDataset(groupId, c.name, c.file, true, c.selectedSheet),
          ),
        );
        overwriteResults.forEach((r, i) => {
          if (r.status === 'fulfilled') {
            mutateUploadStatuses(groupId, prev => prev.filter(x => x.name !== overwriteCandidates[i].file.name));
          } else {
            const msg = r.reason instanceof Error ? r.reason.message : String(r.reason);
            patchStatus(overwriteCandidates[i].file.name, {
              status: 'failed',
              error: msg,
            });
          }
        });
      } else {
        overwriteCandidates.forEach(c =>
          patchStatus(c.file.name, { status: 'failed', error: '用户取消覆盖' }),
        );
      }
    }

    setBusy(false);
    await refresh();
  }

  async function uploadFiles(files: FileList | File[]) {
    if (busy || sheetPickerBusy || sheetPicks.length > 0) return;
    const list = Array.from(files);
    const xlsxFiles = list.filter(f => f.name.toLowerCase().endsWith('.xlsx'));
    const otherFiles = list.filter(f => !f.name.toLowerCase().endsWith('.xlsx'));

    if (xlsxFiles.length === 0) {
      await doUpload(list, new Map());
      return;
    }

    setSheetPickerBusy(true);
    setError(null);
    try {
      const sheetResults = await Promise.all(
        xlsxFiles.map(async f => {
          const sheets = await listSheets(f);
          return { file: f, sheets };
        }),
      );

      const multiSheet = sheetResults.filter(r => r.sheets.length > 1);

      if (multiSheet.length === 0) {
        setSheetPickerBusy(false);
        const sheetMap = new Map<string, string>();
        for (const r of sheetResults) {
          if (r.sheets.length === 1) sheetMap.set(r.file.name, r.sheets[0]);
        }
        await doUpload(list, sheetMap);
        return;
      }

      setSheetPicks(
        multiSheet.map(r => ({ file: r.file, sheets: r.sheets, selected: '' })),
      );
      setSheetPickerBusy(false);
      // Store otherFiles + single-sheet xlsx for later upload when picker confirms
      pendingFilesRef.current = {
        otherFiles,
        singleSheetMap: new Map(
          sheetResults.filter(r => r.sheets.length === 1).map(r => [r.file.name, r.sheets[0]]),
        ),
        singleSheetFiles: sheetResults.filter(r => r.sheets.length === 1).map(r => r.file),
      };
    } catch (e) {
      setSheetPickerBusy(false);
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  const pendingFilesRef = useRef<{
    otherFiles: File[];
    singleSheetFiles: File[];
    singleSheetMap: Map<string, string>;
  } | null>(null);

  async function confirmSheetSelection() {
    const pending = pendingFilesRef.current;
    const sheetMap = new Map<string, string>();
    if (pending) {
      for (const [k, v] of pending.singleSheetMap) sheetMap.set(k, v);
    }
    for (const pick of sheetPicks) sheetMap.set(pick.file.name, pick.selected);

    const allFiles: File[] = [
      ...(pending?.otherFiles ?? []),
      ...(pending?.singleSheetFiles ?? []),
      ...sheetPicks.map(p => p.file),
    ];
    setSheetPicks([]);
    pendingFilesRef.current = null;
    await doUpload(allFiles, sheetMap);
  }

  function cancelSheetSelection() {
    setSheetPicks([]);
    pendingFilesRef.current = null;
  }

  async function handleDeleteDataset(id: string) {
    if (!window.confirm('删除该数据集？其数据和物理表将一并删除。')) return;
    setBusy(true);
    try {
      await deleteDataset(id);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const fileCount = detail?.datasets.length ?? 0;

  async function saveGroupInfo() {
    if (!/^[一-龥A-Za-z0-9_-]{1,100}$/.test(groupName.trim()) || groupDescription.length > 1000) { setError('名称需为 1-100 个中文、字母、数字、- 或 _；说明不能超过 1000 字'); return; }
    setBusy(true);
    try { await updateGroup(groupId, groupName.trim(), groupDescription.trim()); setEditingGroup(false); await refresh(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  return (
    <div className="kw-page">
      <KnowledgeBreadcrumb items={[{ label: '返回对话', to: '/chat' }, { label: '知识库', to: '/configure/datasets' }, { label: detail?.group.name ?? '加载中…' }]} />
      <div className="kw-scroll"><div className="kw-content">
        <div className="kw-pagehead">
          <div className="kw-heading"><div className="kw-eyebrow"><Icon name="book" size="sm" />知识库</div><h1>{detail?.group.name ?? '知识库'}</h1><p>{detail?.group.description || '暂无说明'}</p>
            <div className="kw-infochips"><span><Icon name="table" size="sm" />{fileCount} 个数据集</span>{detail?.group.createdAt && <span><Icon name="clock" size="sm" />创建于 {new Date(detail.group.createdAt).toLocaleDateString('zh-CN')}</span>}</div>
          </div>
          <div className="kw-actions"><button className="da-btn da-btn-ghost" disabled={!detail || busy} onClick={() => { setGroupName(detail?.group.name ?? ''); setGroupDescription(detail?.group.description ?? ''); setError(null); setEditingGroup(true); }}><Icon name="edit" size="sm" />编辑信息</button><button className="da-btn da-btn-primary" disabled={!detail} onClick={() => navigate(`/chat?groups=${encodeURIComponent(groupId)}`)}><Icon name="chat" size="sm" />基于此库提问</button></div>
        </div>
        {error && <div className="kw-alert" role="alert">{error}</div>}
        {workflow && <div className="kw-progress" style={{ display: 'flex', alignItems: 'center', gap: 16, justifyContent: 'space-between', flexWrap: 'wrap' }}>
          <span className="da-small">{workflow.queryAvailable
            ? `基础模型已发布 v${workflow.publishedVersion}，可以问数；业务口径可继续完善。`
            : '数据接入后需要基础模型发布成功，才可问数。'}</span>
          <button className="da-btn da-btn-sm" onClick={() => navigate(`/configure/modeling/${groupId}`)}>{workflow.queryAvailable ? '完善业务模型' : '查看建模进度'}</button>
        </div>}
        <div className="kw-tabs" role="tablist" aria-label="知识库内容">{NAV_ITEMS.map(n => <button key={n.key} role="tab" aria-selected={view === n.key} className={view === n.key ? 'active' : ''} onClick={() => n.key === 'modeling' ? navigate(`/configure/modeling/${groupId}`) : setView(n.key)}>{n.label}{n.key === 'files' && <span className="kw-badge">{fileCount}</span>}</button>)}</div>
        <input ref={fileRef} type="file" multiple accept=".xlsx,.xls,.csv" style={{ display: 'none' }} onChange={e => { if (e.target.files?.length) uploadFiles(e.target.files); e.target.value = ''; }} />
        <details className="kw-progress" open={hasActiveTask || uploadStatuses.some(s => s.status !== 'ready') || associatingTables.size > 0} hidden={!uploadStatuses.some(s => s.status !== 'ready') && importTasks.length === 0 && associatingTables.size === 0}>
          <summary>导入与关联进度{importTasks.length > 0 ? ` · ${importTasks.length} 条记录` : ''}</summary>
          {Array.from(associatingTables).map(name => <div className="da-small" key={name}>{name} · 关联中…</div>)}
          {((uploadStatuses.some(s => s.status !== 'ready')) || importTasks.length > 0) && (
            <div className="da-card" style={{ marginTop: 8, padding: 8 }}>
              {uploadStatuses.filter(s => s.status !== 'ready').map((s, i) => (
                <div key={`up-${s.name}-${i}`} className="da-small" style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                  <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {s.name}
                  </span>
                  {(s.status === 'uploading' || s.status === 'queued') && (
                    <span className="da-spinner da-spinner-sm" style={{ borderColor: 'var(--da-border)', borderTopColor: 'var(--da-primary)' }} />
                  )}
                  <span
                    style={{
                      color:
                        s.status === 'failed'
                          ? 'var(--da-danger)'
                          : 'var(--da-primary)',
                    }}
                  >
                    {STATUS_LABEL[s.status]}
                  </span>
                  {s.status === 'failed' && (
                    <>
                      <button
                        className="da-btn"
                        style={{ fontSize: '0.7rem', padding: '1px 6px' }}
                        onClick={async () => {
                          if (!s.file) return;
                          patchStatus(s.name, { status: 'uploading', error: undefined });
                          try {
                            const datasetName = s.name.replace(/\.[^.]+$/, '');
                            await uploadDataset(groupId, datasetName, s.file, true, s.selectedSheet);
                            mutateUploadStatuses(groupId, prev => prev.filter(x => x.name !== s.name));
                            await refresh();
                          } catch (e) {
                            const msg = e instanceof Error ? e.message : String(e);
                            patchStatus(s.name, { status: 'failed', error: msg });
                          }
                        }}
                      >
                        重试
                      </button>
                      <button
                        className="da-btn"
                        style={{ fontSize: '0.7rem', padding: '1px 6px', color: 'var(--da-text-3)' }}
                        onClick={() => {
                          mutateUploadStatuses(groupId, prev => prev.filter(x => x.name !== s.name));
                        }}
                      >
                        ✕
                      </button>
                    </>
                  )}
                </div>
              ))}
              {importTasks.map(t => {
                const progress =
                  t.sourceRows != null && t.writtenRows != null
                    ? `${t.writtenRows}/${t.sourceRows} 行`
                    : t.sourceRows != null
                      ? `${t.sourceRows} 行`
                      : null;
                return (
                  <div key={t.id} style={{ marginTop: 4 }}>
                    <div className="da-small" style={{ display: 'flex', gap: 8 }}>
                      <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {t.datasetName}
                        {t.sourceFileName && t.sourceFileName !== t.datasetName && (
                          <span style={{ color: 'var(--da-text-2)', marginLeft: 4 }}>
                            ({t.sourceFileName})
                          </span>
                        )}
                        {t.selectedSheet && (
                          <span
                            className="da-badge"
                            style={{ marginLeft: 4, fontSize: '0.7rem', padding: '1px 5px' }}
                          >
                            {t.selectedSheet}
                          </span>
                        )}
                      </span>
                      <span style={{ color: taskStatusColor(t.status), whiteSpace: 'nowrap', display: 'flex', alignItems: 'center', gap: 6 }}>
                        {['SCANNING', 'WRITING', 'VERIFYING'].includes(t.status) && (
                          <span className="da-spinner da-spinner-sm" style={{ borderColor: 'var(--da-border)', borderTopColor: taskStatusColor(t.status) }} />
                        )}
                        {TASK_STATUS_LABEL[t.status] ?? t.status}
                      </span>
                      {(t.status === 'FAILED' || t.status === 'INTERRUPTED') && (
                        <button
                          className="da-btn"
                          style={{ fontSize: '0.7rem', padding: '1px 6px' }}
                          onClick={async () => {
                            try {
                              await retryImportTask(t.id);
                              setImportTasks(await listImportTasks(groupId));
                            } catch (e) {
                              alert(e instanceof Error ? e.message : '重试失败');
                            }
                          }}
                        >
                          重试
                        </button>
                      )}
                    </div>
                    {(progress || t.warningSummary || t.errorSummary) && (
                      <div className="da-small" style={{ color: 'var(--da-text-2)', paddingLeft: 4, fontSize: '0.75rem' }}>
                        {progress && <span>{progress}</span>}
                        {t.warningSummary && (
                          <span style={{ color: 'var(--da-warning, #b58900)', marginLeft: progress ? 8 : 0 }}>
                            ⚠ {t.warningSummary}
                          </span>
                        )}
                        {t.errorSummary && (
                          <span style={{ color: 'var(--da-danger)', marginLeft: progress ? 8 : 0 }}>
                            {t.errorSummary}
                          </span>
                        )}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}

        </details>
        {view === 'files' && <>
          <div className="kw-toolbar"><label className="kw-search"><Icon name="search" size="sm" /><input value={query} onChange={e => setQuery(e.target.value)} placeholder="搜索数据集" aria-label="搜索数据集" /></label><span className="kw-spacer" /><button className="da-btn" disabled={busy || !detail} onClick={() => setAssociateOpen(true)}><Icon name="database" size="sm" />关联数据源</button><button className="da-btn" disabled={busy || !detail} onClick={() => setUploadOpen(true)}><Icon name="upload" size="sm" />上传文件</button></div>
          {!detail && !error && <div className="kw-empty">加载知识库…</div>}
          {detail && fileCount === 0 && <div className="kw-empty"><EmptyIllustration variant="table" caption="添加第一份数据，开始分析" /><button className="da-btn da-btn-primary" disabled={busy} onClick={() => setUploadOpen(true)}>上传文件</button></div>}
          {detail && fileCount > 0 && <div className="kw-tablebox"><div className="kw-table-scroll"><table className="kw-table"><thead><tr><th>数据集名称</th><th>来源</th><th className="numeric">行数</th><th className="numeric">字段数</th><th>来源文件</th><th className="end">操作</th></tr></thead><tbody>
            {detail.datasets.filter(d => `${d.name} ${d.sourceFileName ?? ''}`.toLowerCase().includes(query.trim().toLowerCase())).map(d => <tr key={d.id}>
              <td><button className="kw-namecell" onClick={() => navigate(`/configure/datasets/${groupId}/table/${d.id}`)}><Icon name="table" size="sm" />{d.name}{(d.currentVersion ?? 1) > 1 && <span className="kw-badge">v{d.currentVersion}</span>}</button></td>
              <td><span className="kw-source">{d.origin === 'datasource' ? '数据源关联' : d.origin === 'derived' ? '派生数据' : '文件上传'}</span></td><td className="numeric">{d.rowCount.toLocaleString()}</td><td className="numeric">{d.columns.length}</td><td title={d.sourceFileName ?? ''}>{d.sourceFileName ?? '—'}</td>
              <td className="end"><WorkspaceMenu label={`${d.name}的更多操作`} items={[{ label: '查看数据集', icon: 'table', onClick: () => navigate(`/configure/datasets/${groupId}/table/${d.id}`) }, { label: '删除数据集', icon: 'trash', danger: true, disabled: busy, onClick: () => void handleDeleteDataset(d.id) }]} /></td>
            </tr>)}
            {detail.datasets.every(d => !`${d.name} ${d.sourceFileName ?? ''}`.toLowerCase().includes(query.trim().toLowerCase())) && <tr><td colSpan={6} className="kw-empty">没有匹配的数据集</td></tr>}
          </tbody></table></div><div className="kw-tablebottom"><span>共 {fileCount} 个数据集</span><span>点击名称查看数据与字段</span></div></div>}
        </>}
        {view === 'modeling' && <Navigate to={`/configure/modeling/${groupId}`} replace />}
      </div></div>
      {editingGroup && <div className="da-modal-overlay" onClick={() => { if (!busy) setEditingGroup(false); }}><div className="da-modal-shell" style={{ width: 'min(520px, 92vw)' }} onClick={e => e.stopPropagation()}><div className="da-modal-head"><div className="da-modal-title">编辑知识库</div></div><div className="da-modal-body">{error && <div className="kw-alert" role="alert">{error}</div>}<label className="da-label">名称</label><input className="da-input" value={groupName} onChange={e => setGroupName(e.target.value)} maxLength={100} /><label className="da-label" style={{ marginTop: 16 }}>说明</label><textarea className="da-input" value={groupDescription} onChange={e => setGroupDescription(e.target.value)} maxLength={1000} rows={4} /></div><div className="da-modal-foot"><button className="da-btn" disabled={busy} onClick={() => setEditingGroup(false)}>取消</button><button className="da-btn da-btn-primary" disabled={busy} onClick={saveGroupInfo}>{busy ? '保存中…' : '保存'}</button></div></div></div>}
      {uploadOpen && <div className="da-modal-overlay" onClick={() => setUploadOpen(false)}><div className="da-modal-shell" style={{ width: 'min(560px, 92vw)' }} onClick={e => e.stopPropagation()}><div className="da-modal-head"><div className="da-modal-title">上传数据文件</div></div><div className="da-modal-body">{error && <div className="kw-alert" role="alert">{error}</div>}<div className="da-dropzone" role="button" tabIndex={0} style={dragOver ? { borderColor: 'var(--da-primary)' } : undefined} onClick={() => fileRef.current?.click()} onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fileRef.current?.click(); } }} onDragOver={e => { e.preventDefault(); setDragOver(true); }} onDragLeave={() => setDragOver(false)} onDrop={e => { e.preventDefault(); setDragOver(false); if (e.dataTransfer.files.length) uploadFiles(e.dataTransfer.files); }}><Icon name="upload" /><div style={{ marginTop: 12 }}>点击选择或拖入 Excel / CSV 文件</div><div className="da-small" style={{ marginTop: 8 }}>支持 .xlsx / .xls / .csv，可多选</div>{(busy || sheetPickerBusy) && <div className="da-small" style={{ marginTop: 12 }}>{sheetPickerBusy ? '检测工作表…' : '上传并解析中…'}</div>}</div></div><div className="da-modal-foot"><button className="da-btn" onClick={() => setUploadOpen(false)}>关闭</button></div></div></div>}
      {associateOpen && (
        <AssociateTablesModal
          groupId={groupId}
          onClose={() => setAssociateOpen(false)}
          onAssociated={() => refresh()}
          onAssociateStart={(tables) => {
            setAssociatingTables(new Set(tables));
            setAssociateOpen(false);
          }}
        />
      )}

      {sheetPicks.length > 0 && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(0,0,0,0.45)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
          }}
          onClick={cancelSheetSelection}
        >
          <div
            className="da-card"
            style={{
              width: 480,
              maxHeight: '80vh',
              overflowY: 'auto',
              padding: 20,
            }}
            onClick={e => e.stopPropagation()}
          >
            <div className="da-h2" style={{ marginBottom: 12 }}>
              选择工作表
            </div>
            <div className="da-small" style={{ marginBottom: 12 }}>
              以下文件包含多个工作表，请选择要导入的工作表：
            </div>
            {sheetPicks.map((pick, idx) => (
              <div key={pick.file.name} style={{ marginBottom: idx < sheetPicks.length - 1 ? 14 : 0 }}>
                <div style={{ fontWeight: 500, marginBottom: 4 }}>{pick.file.name}</div>
                <select
                  className="da-input"
                  value={pick.selected}
                  onChange={e => {
                    const val = e.target.value;
                    setSheetPicks(prev =>
                      prev.map((p, i) => (i === idx ? { ...p, selected: val } : p)),
                    );
                  }}
                  style={{ width: '100%' }}
                >
                  <option value="" disabled>
                    请选择工作表
                  </option>
                  {pick.sheets.map(s => (
                    <option key={s} value={s}>
                      {s}
                    </option>
                  ))}
                </select>
              </div>
            ))}
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16 }}>
              <button className="da-btn" onClick={cancelSheetSelection}>
                取消
              </button>
              <button
                className="da-btn da-btn-primary"
                onClick={confirmSheetSelection}
                disabled={sheetPicks.some(p => !p.selected)}
              >
                确认上传
              </button>
            </div>
          </div>
        </div>
      )}

    </div>
  );
}
