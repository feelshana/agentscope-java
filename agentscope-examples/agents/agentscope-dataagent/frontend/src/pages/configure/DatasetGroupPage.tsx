import React, { useCallback, useEffect, useRef, useState } from 'react';
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
  uploadDataset,
  uploadKnowledge,
} from '../../api/datasets';

type View = 'files' | 'graph' | 'tree' | 'doc';

interface UploadStatus {
  name: string;
  status: 'queued' | 'uploading' | 'ready' | 'failed';
  error?: string;
}

const STATUS_LABEL: Record<UploadStatus['status'], string> = {
  queued: '排队中',
  uploading: '上传/解析中',
  ready: '完成',
  failed: '失败',
};

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
  const [uploadStatuses, setUploadStatuses] = useState<UploadStatus[]>([]);
  const [associateOpen, setAssociateOpen] = useState(false);
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
  }, [groupId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // Default to the relationship doc surface when one exists (mirrors TC's doc-first landing).
  useEffect(() => {
    if (detail?.knowledge && !searchParams.get('view')) setView('doc');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detail?.knowledge]);

  function patchStatus(name: string, patch: Partial<UploadStatus>) {
    setUploadStatuses(prev => prev.map(s => (s.name === name ? { ...s, ...patch } : s)));
  }

  async function uploadFiles(files: FileList | File[]) {
    const list = Array.from(files);
    setUploadStatuses(prev => [
      ...prev,
      ...list.map(f => ({ name: f.name, status: 'queued' as const })),
    ]);
    setBusy(true);
    setError(null);
    for (const f of list) {
      patchStatus(f.name, { status: 'uploading' });
      const name = f.name.replace(/\.[^.]+$/, '');
      try {
        await uploadDataset(groupId, name, f);
        patchStatus(f.name, { status: 'ready' });
      } catch (e) {
        patchStatus(f.name, {
          status: 'failed',
          error: e instanceof Error ? e.message : String(e),
        });
      }
    }
    setBusy(false);
    await refresh();
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
            </div>
          ))}
          {uploadStatuses.length > 0 && (
            <div className="da-card" style={{ marginTop: 8, padding: 8 }}>
              {uploadStatuses.map((s, i) => (
                <div key={`${s.name}-${i}`} className="da-small" style={{ display: 'flex', gap: 8 }}>
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
              <div style={{ fontWeight: 600, color: 'var(--da-text)' }}>点击上传或拖入本地文档</div>
              <div style={{ marginTop: 6 }}>支持 .xlsx / .xls / .csv，可多选；每个文件成为一张可问数的表</div>
              {busy && <div style={{ color: 'var(--da-primary)', marginTop: 8 }}>处理中…</div>}
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
                          <span className={d.origin === 'datasource' ? 'da-badge da-badge-primary' : 'da-badge'}>
                            {d.origin === 'datasource' ? '数据源' : '上传'}
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

        {view === 'graph' && <KnowledgeGraphView groupId={groupId} />}
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
    </div>
  );
}
