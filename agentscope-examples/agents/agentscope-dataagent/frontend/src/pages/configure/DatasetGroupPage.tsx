import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import BackToChatHeader from '../../components/BackToChatHeader';
import GraphView from '../../components/GraphView';
import SchemaTreeView from '../../components/SchemaTreeView';
import AssociateTablesModal from '../../components/AssociateTablesModal';
import {
  deleteDataset,
  getGroupDetail,
  GroupDetail,
  uploadDataset,
  uploadKnowledge,
} from '../../api/datasets';

type Tab = 'files' | 'graph' | 'tree';

interface UploadStatus {
  name: string;
  status: 'queued' | 'uploading' | 'ready' | 'failed';
  error?: string;
}

const panelStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflow: 'auto',
  padding: 24,
  display: 'flex',
  flexDirection: 'column',
  gap: 20,
};

const cardStyle: React.CSSProperties = {
  background: '#ffffff',
  border: '1px solid #e2e8f0',
  borderRadius: 12,
  padding: 16,
};

const ghostButtonStyle: React.CSSProperties = {
  padding: '7px 14px',
  borderRadius: 8,
  border: '1px solid #cbd5e1',
  background: '#ffffff',
  color: '#475569',
  fontSize: '0.82rem',
  cursor: 'pointer',
};

const tabBarStyle: React.CSSProperties = {
  display: 'flex',
  gap: 4,
  padding: '0 24px',
  borderBottom: '1px solid #e2e8f0',
  background: '#ffffff',
  flexShrink: 0,
};

const tabStyle = (active: boolean): React.CSSProperties => ({
  padding: '10px 16px',
  fontSize: '0.88rem',
  fontWeight: active ? 600 : 500,
  color: active ? '#1677ff' : '#475569',
  background: 'transparent',
  border: 'none',
  borderBottom: active ? '2px solid #1677ff' : '2px solid transparent',
  cursor: 'pointer',
});

const buttonStyle: React.CSSProperties = {
  padding: '8px 16px',
  borderRadius: 8,
  border: '1px solid #2563eb',
  background: '#2563eb',
  color: '#ffffff',
  fontSize: '0.85rem',
  fontWeight: 600,
  cursor: 'pointer',
};

const dangerStyle: React.CSSProperties = {
  padding: '4px 10px',
  borderRadius: 6,
  border: '1px solid #dc2626',
  background: '#ffffff',
  color: '#dc2626',
  fontSize: '0.75rem',
  fontWeight: 600,
  cursor: 'pointer',
};

const thStyle: React.CSSProperties = {
  textAlign: 'left',
  padding: '8px 10px',
  borderBottom: '1px solid #e2e8f0',
  fontSize: '0.75rem',
  color: '#64748b',
  fontWeight: 600,
};

const tdStyle: React.CSSProperties = {
  padding: '8px 10px',
  borderBottom: '1px solid #f1f5f9',
  fontSize: '0.82rem',
  color: '#0f172a',
};

const STATUS_LABEL: Record<UploadStatus['status'], string> = {
  queued: '排队中',
  uploading: '上传/解析中',
  ready: '完成',
  failed: '失败',
};

export default function DatasetGroupPage() {
  const { groupId = '' } = useParams();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<GroupDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [uploadStatuses, setUploadStatuses] = useState<UploadStatus[]>([]);
  const [activeTab, setActiveTab] = useState<Tab>('files');
  const [associateOpen, setAssociateOpen] = useState(false);
  const fileRef = useRef<HTMLInputElement | null>(null);
  const knowledgeRef = useRef<HTMLInputElement | null>(null);

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

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <BackToChatHeader
        title={detail?.group.name ?? '知识库'}
        subtitle={detail?.group.description ?? ''}
      />
      <div style={{ padding: '10px 24px 0', display: 'flex', alignItems: 'center', gap: 12 }}>
        <button
          style={{
            padding: '6px 12px',
            borderRadius: 8,
            border: '1px solid #cbd5e1',
            background: '#ffffff',
            color: '#475569',
            fontSize: '0.8rem',
            cursor: 'pointer',
          }}
          onClick={() => navigate('/configure/datasets')}
        >
          ← 知识库列表
        </button>
        {error && <span style={{ color: '#b91c1c', fontSize: '0.8rem' }}>{error}</span>}
      </div>
      <div style={tabBarStyle}>
        <button style={tabStyle(activeTab === 'files')} onClick={() => setActiveTab('files')}>
          文件
        </button>
        <button style={tabStyle(activeTab === 'graph')} onClick={() => setActiveTab('graph')}>
          知识图谱
        </button>
        <button style={tabStyle(activeTab === 'tree')} onClick={() => setActiveTab('tree')}>
          树结构
        </button>
      </div>

      <div style={panelStyle}>
        {activeTab === 'files' && (
          <>
            <div
              style={{
                ...cardStyle,
                border: dragOver ? '2px dashed #2563eb' : '2px dashed #cbd5e1',
                background: dragOver ? '#eff6ff' : '#f8fafc',
                textAlign: 'center',
                padding: 28,
                cursor: 'pointer',
              }}
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
              <div style={{ fontSize: '0.95rem', fontWeight: 600, color: '#0f172a' }}>
                点击上传或拖入本地文档
              </div>
              <div style={{ fontSize: '0.78rem', color: '#64748b', marginTop: 6 }}>
                支持 .xlsx / .xls / .csv，可多选；每个文件成为一张可问数的表
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
              {busy && (
                <div style={{ fontSize: '0.78rem', color: '#2563eb', marginTop: 8 }}>处理中…</div>
              )}
            </div>

            <div style={{ display: 'flex', gap: 10 }}>
              <button style={ghostButtonStyle} onClick={() => setAssociateOpen(true)}>
                从数据源关联
              </button>
            </div>

            {uploadStatuses.length > 0 && (
              <div style={cardStyle}>
                <div
                  style={{ fontSize: '0.9rem', fontWeight: 600, color: '#0f172a', marginBottom: 8 }}
                >
                  上传状态
                </div>
                {uploadStatuses.map((s, i) => (
                  <div
                    key={`${s.name}-${i}`}
                    style={{
                      display: 'flex',
                      gap: 10,
                      alignItems: 'center',
                      padding: '4px 0',
                      fontSize: '0.8rem',
                    }}
                  >
                    <span style={{ flex: 1, color: '#0f172a' }}>{s.name}</span>
                    <span
                      style={{
                        color:
                          s.status === 'failed'
                            ? '#dc2626'
                            : s.status === 'ready'
                              ? '#047857'
                              : '#2563eb',
                      }}
                    >
                      {STATUS_LABEL[s.status]}
                    </span>
                    {s.error && <span style={{ color: '#dc2626', maxWidth: 320 }}>{s.error}</span>}
                  </div>
                ))}
              </div>
            )}

            <div style={cardStyle}>
              <div
                style={{ fontSize: '0.95rem', fontWeight: 600, color: '#0f172a', marginBottom: 12 }}
              >
                数据集 ({detail?.datasets.length ?? 0})
              </div>
              {(detail?.datasets.length ?? 0) === 0 ? (
                <div style={{ color: '#94a3b8', fontSize: '0.85rem' }}>暂无数据集，请上传文件。</div>
              ) : (
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead>
                    <tr>
                      <th style={thStyle}>名称</th>
                      <th style={thStyle}>来源</th>
                      <th style={thStyle}>行数</th>
                      <th style={thStyle}>列数</th>
                      <th style={thStyle}>来源文件</th>
                      <th style={thStyle}></th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail?.datasets.map(d => (
                      <tr key={d.id}>
                        <td style={tdStyle}>
                          <button
                            style={{
                              background: 'transparent',
                              border: 'none',
                              color: '#2563eb',
                              cursor: 'pointer',
                              fontSize: '0.82rem',
                              padding: 0,
                            }}
                            onClick={() =>
                              navigate(`/configure/datasets/${groupId}/table/${d.id}`)
                            }
                          >
                            {d.name}
                          </button>
                        </td>
                        <td style={tdStyle}>
                          <span
                            style={{
                              padding: '2px 8px',
                              borderRadius: 999,
                              fontSize: '0.7rem',
                              fontWeight: 600,
                              background: d.origin === 'datasource' ? '#e0f2fe' : '#f1f5f9',
                              color: d.origin === 'datasource' ? '#0369a1' : '#475569',
                            }}
                          >
                            {d.origin === 'datasource' ? '数据源' : '上传'}
                          </span>
                        </td>
                        <td style={tdStyle}>{d.rowCount}</td>
                        <td style={tdStyle}>{d.columns.length}</td>
                        <td style={tdStyle}>{d.sourceFileName ?? '-'}</td>
                        <td style={tdStyle}>
                          <button style={dangerStyle} onClick={() => handleDeleteDataset(d.id)}>
                            删除
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>

            <div style={cardStyle}>
              <div
                style={{ fontSize: '0.95rem', fontWeight: 600, color: '#0f172a', marginBottom: 8 }}
              >
                关系说明文档
              </div>
              <div style={{ fontSize: '0.78rem', color: '#64748b', marginBottom: 10 }}>
                上传一份 .docx / .md / .txt，描述本知识库内各数据集之间的关系（join
                键、业务口径等）。 agent 问数时会读到它，用于跨数据集联表。
              </div>
              <input
                ref={knowledgeRef}
                type="file"
                accept=".docx,.md,.txt"
                style={{ fontSize: '0.8rem' }}
                onChange={e => {
                  const f = e.target.files?.[0];
                  if (f) handleKnowledge(f);
                  e.target.value = '';
                }}
              />
              {detail?.knowledge && (
                <pre
                  style={{
                    marginTop: 10,
                    whiteSpace: 'pre-wrap',
                    fontSize: '0.75rem',
                    color: '#475569',
                    background: '#f8fafc',
                    border: '1px solid #e2e8f0',
                    borderRadius: 8,
                    padding: 10,
                    maxHeight: 180,
                    overflow: 'auto',
                  }}
                >
                  {detail.knowledge}
                </pre>
              )}
            </div>
          </>
        )}

        {activeTab === 'graph' && <GraphView groupId={groupId} />}

        {activeTab === 'tree' && <SchemaTreeView datasets={detail?.datasets ?? []} />}
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
