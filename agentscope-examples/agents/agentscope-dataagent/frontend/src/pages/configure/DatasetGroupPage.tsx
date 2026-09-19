import React, { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import AssociateTablesModal from '../../components/AssociateTablesModal';
import DocDetailView from '../../components/DocDetailView';
import EmptyIllustration from '../../components/EmptyIllustration';
import KnowledgeGraphView from '../../components/KnowledgeGraphView';
import SchemaTreeView from '../../components/SchemaTreeView';
import Icon, { IconName } from '../../components/Icon';
import {
  deleteDataset,
  getGroupDetail,
  GroupDetail,
  ImportTask,
  listImportTasks,
  listSheets,
  retryImportTask,
  uploadDataset,
  uploadKnowledge,
} from '../../api/datasets';
import {
  getUploadStatuses,
  mutateUploadStatuses,
  subscribeUploadStatuses,
  UploadStatus,
} from '../../state/uploadProgress';

type View = 'files' | 'graph' | 'tree' | 'doc';

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
  { key: 'files', icon: 'list', label: '文件' },
  { key: 'graph', icon: 'graph', label: '知识图谱' },
  { key: 'tree', icon: 'table', label: '树结构目录' },
  { key: 'doc', icon: 'file', label: '关系说明文档' },
];

/**
 * TC-style knowledge-base workspace: a left rail (KB header, add-file/associate actions, view nav,
 * file list) plus a right content pane that swaps between the file manager, the semantic knowledge
 * graph, the schema tree and the relationship-document detail surface.
 */
export default function DatasetGroupPage() {
  const { groupId = '' } = useParams();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [detail, setDetail] = useState<GroupDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const uploadStatuses = useSyncExternalStore(subscribeUploadStatuses, () =>
    getUploadStatuses(groupId),
  );
  const [importTasks, setImportTasks] = useState<ImportTask[]>([]);
  const [associateOpen, setAssociateOpen] = useState(false);
  /** 多工作表选择弹窗。 */
  interface SheetPick {
    file: File;
    sheets: string[];
    selected: string;
  }
  const [sheetPicks, setSheetPicks] = useState<SheetPick[]>([]);
  const [sheetPickerBusy, setSheetPickerBusy] = useState(false);
  const fileRef = useRef<HTMLInputElement | null>(null);
  const knowledgeRef = useRef<HTMLInputElement | null>(null);

  const view = (searchParams.get('view') as View) || 'files';
  const setView = (v: View) => {
    const next = new URLSearchParams(searchParams);
    next.set('view', v);
    setSearchParams(next, { replace: true });
  };

  const refresh = useCallback(async () => {
    try {
      setDetail(await getGroupDetail(groupId));
      setError(null);
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

  // 有活动任务时每 3 秒轮询一次后端状态；全部终态后停止。
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

  // Default to the relationship doc surface when one exists (mirrors TC's doc-first landing).
  useEffect(() => {
    if (detail?.knowledge && !searchParams.get('view')) setView('doc');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detail?.knowledge]);

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
      ...files.map(f => ({ name: f.name, status: 'queued' as const })),
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
        patchStatus(files[i].name, { status: 'ready' });
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
            patchStatus(overwriteCandidates[i].file.name, { status: 'ready' });
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

  async function handleKnowledge(f: File) {
    setBusy(true);
    setError(null);
    try {
      await uploadKnowledge(groupId, f);
      await refresh();
      setView('doc');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function handleDeleteDataset(id: string) {
    if (!window.confirm('删除该数据集？其物理表将被 DROP。')) return;
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

  const fileCount = (detail?.datasets.length ?? 0) + (detail?.knowledge ? 1 : 0);

  return (
    <div style={{ display: 'flex', height: '100%', minHeight: 0 }}>
      {/* ---------- rail ---------- */}
      <div className="da-rail">
        <div className="da-rail-head">
          <button className="da-btn da-btn-sm" onClick={() => navigate('/configure/datasets')}>
            ← 知识库列表
          </button>
          <div className="da-page-title" style={{ marginTop: 10 }}>
            {detail?.group.name ?? '知识库'}
          </div>
          {detail?.group.description && (
            <div className="da-small" style={{ marginTop: 4 }}>
              {detail.group.description}
            </div>
          )}
          <button
            className="da-btn da-btn-primary"
            style={{ width: '100%', marginTop: 12 }}
            onClick={() => fileRef.current?.click()}
          >
            + 添加文件
          </button>
          <button className="da-btn" style={{ width: '100%', marginTop: 8 }} onClick={() => setAssociateOpen(true)}>
            从数据源关联
          </button>
          {error && (
            <div className="da-small" style={{ color: 'var(--da-danger)', marginTop: 8 }}>
              {error}
            </div>
          )}
        </div>

        <div style={{ padding: '8px 8px 0', display: 'flex', flexDirection: 'column', gap: 2 }}>
          {NAV_ITEMS.map(n => (
            <button
              key={n.key}
              className={view === n.key ? 'da-navitem da-navitem-active' : 'da-navitem'}
              onClick={() => setView(n.key)}
            >
              <Icon name={n.icon} /> {n.label}
            </button>
          ))}
        </div>

        <div className="da-rail-body">
          <div className="da-small" style={{ padding: '6px 8px' }}>
            {fileCount} 个文件
          </div>
          {detail?.knowledge && (
            <div
              className={'da-row' + (view === 'doc' ? ' da-row-active' : '')}
              onClick={() => setView('doc')}
            >
              <Icon name="file" />
              <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                关系说明文档
              </span>
            </div>
          )}
          {detail?.datasets.map(d => (
            <div
              key={d.id}
              className="da-row"
              onClick={() => navigate(`/configure/datasets/${groupId}/table/${d.id}`)}
            >
              <Icon name="database" />
              <span
                style={{
                  flex: 1,
                  minWidth: 0,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {d.name}
              </span>
              {(d.currentVersion ?? 1) > 1 && (
                <span
                  className="da-badge"
                  style={{ fontSize: '0.68rem', padding: '1px 5px', flexShrink: 0 }}
                >
                  v{d.currentVersion}
                </span>
              )}
            </div>
          ))}
          {(uploadStatuses.length > 0 || importTasks.length > 0) && (
            <div className="da-card" style={{ marginTop: 8, padding: 8 }}>
              {uploadStatuses.map((s, i) => (
                <div key={`up-${s.name}-${i}`} className="da-small" style={{ display: 'flex', gap: 8 }}>
                  <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {s.name}
                  </span>
                  <span
                    style={{
                      color:
                        s.status === 'failed'
                          ? 'var(--da-danger)'
                          : s.status === 'ready'
                            ? 'var(--da-success)'
                            : 'var(--da-primary)',
                    }}
                  >
                    {STATUS_LABEL[s.status]}
                  </span>
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
                      <span style={{ color: taskStatusColor(t.status), whiteSpace: 'nowrap' }}>
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
        </div>

        <input
          ref={fileRef}
          type="file"
          multiple
          accept=".xlsx,.xls,.csv"
          style={{ display: 'none' }}
          onChange={e => {
            if (e.target.files?.length) uploadFiles(e.target.files);
            e.target.value = '';
          }}
        />
        <input
          ref={knowledgeRef}
          type="file"
          accept=".docx,.md,.txt"
          style={{ display: 'none' }}
          onChange={e => {
            const f = e.target.files?.[0];
            if (f) handleKnowledge(f);
            e.target.value = '';
          }}
        />
      </div>

      {/* ---------- content ---------- */}
      <div
        style={{
          flex: 1,
          minWidth: 0,
          display: 'flex',
          flexDirection: 'column',
          background: 'var(--da-app-bg)',
          overflow: 'hidden',
        }}
      >
        {view === 'files' && (
          <div style={{ flex: 1, overflowY: 'auto', padding: 16, display: 'flex', flexDirection: 'column', gap: 16 }}>
            <div
              className="da-dropzone"
              style={dragOver ? { borderColor: 'var(--da-primary)', background: 'var(--da-primary-subtle)' } : undefined}
              onClick={() => fileRef.current?.click()}
              onDragOver={e => {
                e.preventDefault();
                setDragOver(true);
              }}
              onDragLeave={() => setDragOver(false)}
              onDrop={e => {
                e.preventDefault();
                setDragOver(false);
                if (e.dataTransfer.files.length) uploadFiles(e.dataTransfer.files);
              }}
            >
              <div style={{ fontWeight: 600, color: 'var(--da-text)' }}>上传数据文件</div>
              <div style={{ marginTop: 6 }}>
                点击上传或拖入 .xlsx / .xls / .csv，可多选。CSV/XLSX 将完整导入并建立原始表。
              </div>
              {sheetPickerBusy && <div style={{ color: 'var(--da-primary)', marginTop: 8 }}>检测工作表…</div>}
              {busy && !sheetPickerBusy && <div style={{ color: 'var(--da-primary)', marginTop: 8 }}>处理中…</div>}
            </div>

            <div className="da-card">
              <div className="da-h2" style={{ marginBottom: 12 }}>
                数据集 ({detail?.datasets.length ?? 0})
              </div>
              {(detail?.datasets.length ?? 0) === 0 ? (
                <EmptyIllustration variant="table" caption="暂无数据集，请上传文件" />
              ) : (
                <table className="da-table">
                  <thead>
                    <tr>
                      <th>名称</th>
                      <th>来源</th>
                      <th>行数</th>
                      <th>列数</th>
                      <th>来源文件</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail?.datasets.map(d => (
                      <tr key={d.id}>
                        <td>
                          <button
                            style={{
                              background: 'transparent',
                              border: 'none',
                              color: 'var(--da-primary)',
                              cursor: 'pointer',
                              fontSize: 13,
                              padding: 0,
                            }}
                            onClick={() => navigate(`/configure/datasets/${groupId}/table/${d.id}`)}
                          >
                            {d.name}
                          </button>
                        </td>
                        <td>
                          <span className={d.origin === 'datasource' ? 'da-badge da-badge-primary' : d.origin === 'derived' ? 'da-badge' : 'da-badge'}>
                            {d.origin === 'datasource' ? '数据源' : d.origin === 'derived' ? '派生' : '上传'}
                          </span>
                        </td>
                        <td>{d.rowCount}</td>
                        <td>{d.columns.length}</td>
                        <td>{d.sourceFileName ?? '-'}</td>
                        <td>
                          <button className="da-btn da-btn-danger da-btn-sm" onClick={() => handleDeleteDataset(d.id)}>
                            删除
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>

            <div className="da-card">
              <div className="da-h2" style={{ marginBottom: 8 }}>关系说明文档</div>
              <div className="da-small" style={{ marginBottom: 10 }}>
                上传一份 .docx / .md / .txt，描述本知识库内各数据集之间的关系（join
                键、业务口径等）。agent 问数时会读到它，用于跨数据集联表。
              </div>
              <button className="da-btn" onClick={() => knowledgeRef.current?.click()}>
                上传关系说明文档
              </button>
            </div>
          </div>
        )}

        {view === 'graph' && (
          <div
            style={{
              flex: 1,
              overflowY: 'auto',
              padding: 16,
              display: 'flex',
              flexDirection: 'column',
              gap: 10,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <div className="da-h2" style={{ margin: 0 }}>
                知识图谱
              </div>
            </div>
            <KnowledgeGraphView groupId={groupId} />
          </div>
        )}
        {view === 'tree' && <SchemaTreeView datasets={detail?.datasets ?? []} />}
        {view === 'doc' && <DocDetailView groupId={groupId} group={detail?.group ?? null} />}
      </div>

      {associateOpen && (
        <AssociateTablesModal
          groupId={groupId}
          onClose={() => setAssociateOpen(false)}
          onAssociated={() => refresh()}
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
