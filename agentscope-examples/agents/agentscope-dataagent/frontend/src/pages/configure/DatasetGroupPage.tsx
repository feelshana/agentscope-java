import React, { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import AssociateTablesModal from '../../components/AssociateTablesModal';
import DocDetailView from '../../components/DocDetailView';
import EmptyIllustration from '../../components/EmptyIllustration';
import KnowledgeGraphView from '../../components/KnowledgeGraphView';
import ObjectCatalogView from '../../components/ObjectCatalogView';
import OntologyGraphView from '../../components/OntologyGraphView';
import SchemaTreeView from '../../components/SchemaTreeView';
import SemanticGraphView from '../../components/SemanticGraphView';
import GenerateOntologyModal from '../../components/GenerateOntologyModal';
import Icon, { IconName } from '../../components/Icon';
import {
  deleteDataset,
  getGroupDetail,
  GroupDetail,
  uploadDataset,
  uploadKnowledge,
  saveDescription,
  getDescription,
  uploadDataWithDescription,
} from '../../api/datasets';
import {
  getOntologyModel,
} from '../../api/ontology';
import { getSemanticGraph, uploadSemanticModel, uploadInstructions, getInstructions, deleteSemanticModel } from '../../api/semantic';
import {
  getUploadStatuses,
  mutateUploadStatuses,
  subscribeUploadStatuses,
  UploadStatus,
} from '../../state/uploadProgress';

type View = 'files' | 'model' | 'graph' | 'tree' | 'doc';

const STATUS_LABEL: Record<UploadStatus['status'], string> = {
  queued: '排队中',
  uploading: '上传/解析中',
  ready: '完成',
  failed: '失败',
};

const NAV_ITEMS: { key: View; icon: IconName; label: string }[] = [
  { key: 'files', icon: 'list', label: '文件' },
  { key: 'model', icon: 'model', label: '对象目录' },
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
  const [associateOpen, setAssociateOpen] = useState(false);
  /** 当前本体来源：'uploaded'（用户上传 model.yaml）/'auto-generated'（自动生成）/null（无本体）。 */
  const [ontologyOrigin, setOntologyOrigin] = useState<string | null>(null);
  /** 是否存在语义模型（优先于本体图谱展示）。 */
  const [hasSemanticModel, setHasSemanticModel] = useState(false);
  /** 是否已上传 instructions.md。 */
  const [hasInstructions, setHasInstructions] = useState(false);
  /** 语义模型来源：'uploaded'/'auto-generated'/'cleared'/null。 */
  const [semanticOrigin, setSemanticOrigin] = useState<string | null>(null);
  /** 语义模型实体 ID（删除用）。 */
  const [semanticModelId, setSemanticModelId] = useState<string | null>(null);
  /** 语义模型更新时间（用作图谱视图 key，重新生成后强制重挂载）。 */
  const [semanticUpdatedAt, setSemanticUpdatedAt] = useState<string | null>(null);
  /** 本体图谱生成弹窗开关。 */
  const [generateOpen, setGenerateOpen] = useState(false);
  const fileRef = useRef<HTMLInputElement | null>(null);
  const knowledgeRef = useRef<HTMLInputElement | null>(null);
  const semanticMdlRef = useRef<HTMLInputElement | null>(null);
  const instructionsRef = useRef<HTMLInputElement | null>(null);
  const manifestRef = useRef<HTMLInputElement | null>(null);
  const dataFilesRef = useRef<HTMLInputElement | null>(null);
  /** 是否已保存数据描述（来自后端）。 */
  const [hasDescription, setHasDescription] = useState(false);
  /** 已保存的描述内容（用于预览）。 */
  const [descriptionContent, setDescriptionContent] = useState<string | null>(null);
  /** JSON 预览弹窗内容。 */
  const [jsonPreview, setJsonPreview] = useState<{ title: string; content: string } | null>(null);

  // 从后端加载描述状态
  useEffect(() => {
    getDescription(groupId)
      .then(info => {
        setHasDescription(info.hasDescription);
        setDescriptionContent(info.content);
      })
      .catch(() => { /* ignore */ });
  }, [groupId]);

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
    // 同步本体来源：上传/关联/导入清单后本体状态可能变化，决定知识图谱视图展示哪种图谱
    try {
      const m = await getOntologyModel(groupId);
      setOntologyOrigin(m?.origin ?? null);
    } catch {
      setOntologyOrigin(null);
    }
    // 检查是否存在语义模型
    try {
      const sg = await getSemanticGraph(groupId);
      setHasSemanticModel((sg?.nodes?.length ?? 0) > 0);
      setSemanticOrigin(sg?.meta?.origin ?? null);
      setSemanticModelId(sg?.meta?.modelId ?? null);
      setSemanticUpdatedAt(sg?.meta?.updatedAt ?? null);
    } catch {
      setHasSemanticModel(false);
      setSemanticOrigin(null);
      setSemanticModelId(null);
      setSemanticUpdatedAt(null);
    }
    // 检查是否存在 instructions
    try {
      const inst = await getInstructions(groupId);
      setHasInstructions(inst.hasInstructions);
    } catch {
      setHasInstructions(false);
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
    mutateUploadStatuses(groupId, prev =>
      prev.map(s => (s.name === name ? { ...s, ...patch } : s)),
    );
  }

  async function uploadFiles(files: FileList | File[]) {
    const list = Array.from(files);
    // 如果已保存描述，走 manifest 导入流程（按描述建表，无前缀），逐文件上传
    if (hasDescription) {
      const dataFiles = list.filter(f => /\.(xlsx|xls|csv)$/i.test(f.name));
      if (dataFiles.length > 0) {
        // 填充状态队列，让用户看到进度
        mutateUploadStatuses(groupId, prev => [
          ...prev,
          ...dataFiles.map(f => ({ name: f.name, status: 'queued' as const })),
        ]);
        setBusy(true);
        setError(null);
        try {
          // 逐文件上传：每个文件完成上传→建表→入库后立即更新状态并刷新数据集列表
          for (const f of dataFiles) {
            patchStatus(f.name, { status: 'uploading' });
            try {
              await uploadDataWithDescription(groupId, f);
              patchStatus(f.name, { status: 'ready' });
              // 每个文件完成后立即刷新数据集列表，让新表实时展示
              await refresh();
            } catch (fileErr) {
              const msg = fileErr instanceof Error ? fileErr.message : String(fileErr);
              patchStatus(f.name, { status: 'failed', error: msg });
            }
          }
        } finally {
          setBusy(false);
        }
        return;
      }
    }
    mutateUploadStatuses(groupId, prev => [
      ...prev,
      ...list.map(f => ({ name: f.name, status: 'queued' as const })),
    ]);
    setBusy(true);
    setError(null);
    try {
      for (const f of list) {
        patchStatus(f.name, { status: 'uploading' });
        const name = f.name.replace(/\.[^.]+$/, '');
        try {
          await uploadDataset(groupId, name, f);
          patchStatus(f.name, { status: 'ready' });
        } catch (e) {
          const msg = e instanceof Error ? e.message : String(e);
          // 同名冲突：询问是否覆盖，确认后删旧重建
          if (
            msg.includes('already exists') &&
            window.confirm(
              `数据集「${name}」已存在，是否覆盖？覆盖将删除原数据集（含物理表）并重新导入。`,
            )
          ) {
            try {
              await uploadDataset(groupId, name, f, undefined, true);
              patchStatus(f.name, { status: 'ready' });
            } catch (e2) {
              patchStatus(f.name, {
                status: 'failed',
                error: e2 instanceof Error ? e2.message : String(e2),
              });
            }
          } else {
            patchStatus(f.name, { status: 'failed', error: msg });
          }
        }
      }
    } finally {
      setBusy(false);
      await refresh();
    }
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

  /** 上传语义模型 mdl.json。 */
  async function handleUploadSemanticMdl(f: File) {
    setBusy(true);
    setError(null);
    try {
      await uploadSemanticModel(f, groupId);
      alert(`语义模型上传成功：${f.name}`);
      await refresh();
      setView('graph');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  /** 上传并保存数据描述文件（sources.json）。 */
  async function handleSaveDescription(file: File) {
    setBusy(true);
    setError(null);
    try {
      const result = await saveDescription(groupId, file);
      setHasDescription(true);
      // 重新获取内容用于预览
      const info = await getDescription(groupId);
      setDescriptionContent(info.content);
      alert(`数据描述文件已保存：${result.name}`);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  /** 上传数据文件，使用已保存的描述进行建表（无前缀）。逐文件上传，每个完成后立即更新状态。 */
  async function handleUploadData(files: File[]) {
    // 填充状态队列
    mutateUploadStatuses(groupId, prev => [
      ...prev,
      ...files.map(f => ({ name: f.name, status: 'queued' as const })),
    ]);
    setBusy(true);
    setError(null);
    try {
      // 逐文件上传：每个文件完成上传→建表→入库后立即更新状态并刷新数据集列表
      for (const f of files) {
        patchStatus(f.name, { status: 'uploading' });
        try {
          const summary = await uploadDataWithDescription(groupId, f);
          patchStatus(f.name, { status: 'ready' });
          // 每个文件完成后立即刷新数据集列表
          await refresh();
          const parts: string[] = [];
          if (summary.batchesImported > 0) parts.push(`${summary.batchesImported} 批次导入`);
          if (summary.derivedCreated > 0) parts.push(`${summary.derivedCreated} 派生表`);
          if (summary.warnings.length > 0) parts.push(`${summary.warnings.length} 条警告`);
          console.log(`${f.name} 导入完成：${parts.join('、') || '无变更'}`);
        } catch (fileErr) {
          const msg = fileErr instanceof Error ? fileErr.message : String(fileErr);
          patchStatus(f.name, { status: 'failed', error: msg });
        }
      }
    } finally {
      setBusy(false);
    }
  }

  /** 上传 instructions.md（业务规则 + 数据限制）。 */
  async function handleUploadInstructions(f: File) {
    setBusy(true);
    setError(null);
    try {
      await uploadInstructions(f, groupId);
      alert(`业务规则文档上传成功：${f.name}`);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  /** 删除语义模型（后端软清除保留 instructions 业务文档）。 */
  async function handleDeleteSemanticModel() {
    if (!semanticModelId) return;
    if (!window.confirm('删除语义模型？业务文档（instructions）将保留，可稍后重新生成。')) return;
    setBusy(true);
    setError(null);
    try {
      await deleteSemanticModel(semanticModelId);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const fileCount = (detail?.datasets.length ?? 0) + (detail?.knowledge ? 1 : 0);
  // 上传过 model.yaml 或存在数据源关联表时，知识图谱视图展示本体图谱；其余场景保持语义知识图谱
  const useOntologyGraph =
    ontologyOrigin === 'uploaded' ||
    (detail?.datasets ?? []).some(d => d.origin === 'datasource');

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
        <input
          ref={semanticMdlRef}
          type="file"
          accept=".json"
          style={{ display: 'none' }}
          onChange={e => {
            const f = e.target.files?.[0];
            if (f) handleUploadSemanticMdl(f);
            e.target.value = '';
          }}
        />
        <input
          ref={instructionsRef}
          type="file"
          accept=".md,.txt"
          style={{ display: 'none' }}
          onChange={e => {
            const f = e.target.files?.[0];
            if (f) handleUploadInstructions(f);
            e.target.value = '';
          }}
        />
        <input
          ref={manifestRef}
          type="file"
          accept=".json,.yaml,.yml"
          style={{ display: 'none' }}
          onChange={e => {
            const f = e.target.files?.[0];
            if (f) handleSaveDescription(f);
            e.target.value = '';
          }}
        />
        <input
          ref={dataFilesRef}
          type="file"
          multiple
          accept=".xlsx,.xls,.csv"
          style={{ display: 'none' }}
          onChange={e => {
            const files = e.target.files ? Array.from(e.target.files) : [];
            if (files.length > 0) handleUploadData(files);
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
            {/* 方式一：两步式导入（推荐）—— ① 上传描述 ② 上传数据 */}
            <div className="da-card">
              <div className="da-h2" style={{ marginBottom: 6 }}>按数据描述导入（推荐）</div>
              <div className="da-small" style={{ marginBottom: 12 }}>
                先上传 sources.json 描述文件，再上传对应的 Excel 数据文件。
                系统将按描述内容执行：按指定表名建表（无前缀） → 入数据 → 生成衍生表 → 入衍生数据。
              </div>

              {/* Step 1: 上传描述文件 */}
              <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginBottom: 10 }}>
                <span className="da-small" style={{ minWidth: 24, fontWeight: 600 }}>①</span>
                <button className="da-btn" onClick={() => manifestRef.current?.click()}>
                  <Icon name="upload" size="sm" /> 上传数据描述 (sources.json)
                </button>
                {hasDescription && descriptionContent && (
                  <>
                    <span className="da-badge da-badge-primary">已保存</span>
                    <button
                      className="da-btn da-btn-sm"
                      onClick={() => setJsonPreview({ title: 'sources.json', content: descriptionContent })}
                    >
                      预览
                    </button>
                  </>
                )}
              </div>

              {/* Step 2: 上传数据文件 */}
              <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                <span className="da-small" style={{ minWidth: 24, fontWeight: 600 }}>②</span>
                {hasDescription ? (
                  <>
                    <button className="da-btn da-btn-primary" onClick={() => dataFilesRef.current?.click()}>
                      <Icon name="upload" size="sm" /> 上传数据文件 (.xlsx)
                    </button>
                    <span className="da-small">按已保存的描述建表，表名和列名完全按描述文件</span>
                  </>
                ) : (
                  <span className="da-small" style={{ color: 'var(--da-muted, #999)' }}>
                    请先上传数据描述文件，再上传数据文件
                  </span>
                )}
              </div>
            </div>

            {/* 方式二：直接上传（简单模式，表名 = 文件名） */}
            <div
              className="da-dropzone"
              style={dragOver ? { borderColor: 'var(--da-primary)', background: 'var(--da-primary-subtle)' } : hasDescription ? { borderColor: 'var(--da-primary)', borderStyle: 'dashed' } : undefined}
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
              {hasDescription ? (
                <>
                  <div style={{ fontWeight: 600, color: 'var(--da-primary)' }}>上传数据文件（按描述建表）</div>
                  <div style={{ marginTop: 6 }}>已保存数据描述，上传 Excel 后将按描述中的表名和列名建表</div>
                </>
              ) : (
                <>
                  <div style={{ fontWeight: 600, color: 'var(--da-text)' }}>直接上传数据文件</div>
                  <div style={{ marginTop: 6 }}>点击上传或拖入 .xlsx / .xls / .csv，可多选；表名将使用文件名</div>
                </>
              )}
              {busy && <div style={{ color: 'var(--da-primary)', marginTop: 8 }}>处理中…</div>}
            </div>

            {/* 语义模型（可选） */}
            <div className="da-card">
              <div className="da-h2" style={{ marginBottom: 8 }}>语义模型（可选）</div>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <button className="da-btn" onClick={() => semanticMdlRef.current?.click()}>
                  <Icon name="upload" size="sm" /> 上传语义模型 (mdl.json)
                  {hasSemanticModel && <span className="da-badge da-badge-primary" style={{ marginLeft: 6 }}>已配置</span>}
                </button>
                <button className="da-btn" onClick={() => instructionsRef.current?.click()}>
                  <Icon name="upload" size="sm" /> 上传业务规则 (instructions.md)
                  {hasInstructions && <span className="da-badge da-badge-primary" style={{ marginLeft: 6 }}>已配置</span>}
                </button>
              </div>
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

        {view === 'model' && <ObjectCatalogView groupId={groupId} />}
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
                本体图谱
              </div>
              <span className="da-badge da-badge-primary">
                {hasSemanticModel
                  ? semanticOrigin === 'uploaded'
                    ? '语义模型 · 文件上传'
                    : '语义模型 · 按表生成'
                  : useOntologyGraph
                    ? '遗留本体'
                    : '未生成'}
              </span>
              <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
                {hasSemanticModel ? (
                  <>
                    <button className="da-btn da-btn-sm" onClick={() => setGenerateOpen(true)}>
                      <Icon name="refresh" size="sm" /> 重新生成
                    </button>
                    <button className="da-btn da-btn-sm" onClick={handleDeleteSemanticModel}>
                      删除模型
                    </button>
                  </>
                ) : (
                  <button
                    className="da-btn da-btn-primary da-btn-sm"
                    onClick={() => setGenerateOpen(true)}
                  >
                    <Icon name="plus" size="sm" /> 生成本体图谱
                  </button>
                )}
              </div>
            </div>
            {!hasSemanticModel && (
              <div
                style={{
                  background: 'var(--da-primary-subtle)',
                  border: '1px solid var(--da-border)',
                  borderRadius: 8,
                  padding: '8px 12px',
                  fontSize: '0.8rem',
                  color: 'var(--da-text-2)',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                }}
              >
                尚未生成语义本体——可按数据表生成，或上传本体文件（mdl.json + instructions.md）生成。
                <button
                  className="da-btn da-btn-sm"
                  style={{ marginLeft: 'auto' }}
                  onClick={() => setGenerateOpen(true)}
                >
                  去生成
                </button>
              </div>
            )}
            {hasSemanticModel ? (
              <SemanticGraphView
                key={`${semanticModelId ?? 'x'}-${semanticUpdatedAt ?? 'y'}`}
                groupId={groupId}
              />
            ) : useOntologyGraph ? (
              <OntologyGraphView groupId={groupId} />
            ) : (
              <KnowledgeGraphView groupId={groupId} />
            )}
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

      {generateOpen && (
        <GenerateOntologyModal
          groupId={groupId}
          datasets={detail?.datasets ?? []}
          onClose={() => setGenerateOpen(false)}
          onGenerated={() => refresh()}
        />
      )}

      {jsonPreview && (
        <div
          style={{
            position: 'fixed', inset: 0, zIndex: 1000,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            background: 'rgba(0,0,0,0.4)',
          }}
          onClick={() => setJsonPreview(null)}
        >
          <div
            style={{
              background: 'var(--da-card-bg, #fff)', borderRadius: 8,
              maxWidth: 720, width: '90%', maxHeight: '80vh',
              display: 'flex', flexDirection: 'column', boxShadow: '0 4px 24px rgba(0,0,0,0.15)',
            }}
            onClick={e => e.stopPropagation()}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px 16px', borderBottom: '1px solid var(--da-border, #e5e5e5)' }}>
              <span style={{ fontWeight: 600 }}>{jsonPreview.title}</span>
              <button className="da-btn da-btn-sm" onClick={() => setJsonPreview(null)}>✕</button>
            </div>
            <pre
              style={{
                margin: 0, padding: 16, overflow: 'auto', flex: 1,
                fontSize: 12, lineHeight: 1.5, fontFamily: 'monospace',
                background: 'var(--da-app-bg, #f8f8f8)', borderRadius: '0 0 8px 8px',
                whiteSpace: 'pre-wrap', wordBreak: 'break-word',
              }}
            >
              {(() => {
                try { return JSON.stringify(JSON.parse(jsonPreview.content), null, 2); }
                catch { return jsonPreview.content; }
              })()}
            </pre>
          </div>
        </div>
      )}
    </div>
  );
}
