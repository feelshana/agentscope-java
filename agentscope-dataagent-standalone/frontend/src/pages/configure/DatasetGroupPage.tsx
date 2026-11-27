import { lazy, Suspense, useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Navigate, useLocation, useMatch, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import KnowledgeBaseMembersModal from '../../components/KnowledgeBaseMembersModal';
import AssociationProgress from '../../components/AssociationProgress';
import type { AssociationState } from '../../components/AssociationProgress';
import AssociateTablesModal from '../../components/AssociateTablesModal';
import EmptyIllustration from '../../components/EmptyIllustration';
import Icon from '../../components/Icon';
import { WorkspaceMenu } from '../../components/KnowledgeWorkspace';
import { releaseDecision } from '../../components/modeling/releaseDecision';
import { caliberReleaseBlocker } from '../../components/modeling/caliberPresentation';
import type { ReviewSection } from '../../components/modeling/releaseDecision';
import { useKnowledgeReleaseData } from '../../components/modeling/useKnowledgeReleaseData';
import { useQueryCoachData } from '../../components/modeling/useQueryCoachData';
import { getDraftReview } from '../../api/draftReview';
import { getModelingWorkflow } from '../../api/modelingWorkflow';
import { getKnowledgeBaseBrowse, getMdlPreview } from '../../api/semanticModeling';
import { parseRuleFile } from '../../components/modeling/ruleText';
import {
  DatasetUploadError,
  getUploadPolicy,
  getServerUploadStatuses,
  validateUploadSelection,
  UploadPolicy,
  deleteDataset,
  getGroupDetail,
  GroupDetail,
  listSheets,
  uploadDataset,
} from '../../api/datasets';
import {
  getUploadStatuses,
  mutateUploadStatuses,
  subscribeUploadStatuses,
  UploadStatus,
} from '../../state/uploadProgress';

import { settleUploads } from '../../api/uploadQueue';
import { getToken } from '../../api/auth';
import { useQueryReadiness } from '../../components/useQueryReadiness';
import { fetchSample, refreshOnboarding, useOnboarding } from '../../api/onboarding';
import { tourActive, tourHoldsQuery, tourLocksDraftDrawer, tourSignal, useTour } from '../../state/guidedTour';
import KnowledgeWorkspaceShell from './KnowledgeWorkspaceShell';
import KnowledgeWorkspaceOverview, { describedColumns, WorkspaceHeading } from './KnowledgeWorkspaceOverview';
import KnowledgeWorkspaceSettings from './KnowledgeWorkspaceSettings';
import KnowledgeSourceConnection from './KnowledgeSourceConnection';
import DatasetDetailPage from './DatasetDetailPage';
import { datasetPath, knowledgePath, modelingParams, modelingPath, modelingPreparationPath, modelingReviewPath, modelingSection, type KnowledgeModelingHost, type KnowledgeSection, type ModelingHeaderActivity } from './knowledgeWorkspaceRoutes';
import './DatasetGroupPage.css';
import '../../detail-surfaces.css';
import './KnowledgeDetails.css';

const SemanticModelingPage = lazy(() => import('./SemanticModelingPage'));

const STATUS_LABEL: Record<UploadStatus['status'], string> = {
  queued: '排队中',
  uploading: '上传中',
  importing: '解析/导入中',
  publishing: '准备模型草稿中',
  model_pending: '已导入，模型未就绪',
  uncertain: '状态待确认',
  ready: '完成',
  failed: '失败',
};

const ACTIVE_UPLOAD: UploadStatus['status'][] = ['uploading', 'queued', 'importing', 'publishing'];

export default function DatasetGroupPage() {
  const { groupId = '' } = useParams();
  return <KnowledgeBaseWorkspace key={groupId} groupId={groupId} />;
}

function KnowledgeBaseWorkspace({ groupId }: { groupId: string }) {
  const modelRoute = !!useMatch('/configure/datasets/:groupId/modeling');
  const datasetId = useMatch('/configure/datasets/:groupId/table/:datasetId')?.params.datasetId;
  const rootPath = knowledgePath(groupId);
  const modelPath = modelingPath(groupId);
  const navigate = useNavigate();
  const location = useLocation();
  const currentLocation = useRef(location.key);
  currentLocation.current = location.key;
  const activeGroup = useRef<string | null>(groupId);
  useEffect(() => { activeGroup.current = groupId; return () => { activeGroup.current = null; }; }, [groupId]);
  const uploadScope = `${getToken() ?? ''}:${groupId}`;
  const [searchParams, setSearchParams] = useSearchParams();
  const [detail, setDetail] = useState<GroupDetail | null>(null);
  const canManage = detail?.group.canManage === true && !detail.group.deletionPending;
  const hasExternalDatasets = detail?.datasets.some(dataset => dataset.origin === 'datasource') ?? false;
  const fileCount = detail?.datasets.length ?? 0;
  const knownVersion = detail?.publishedVersion ?? detail?.group.publishedVersion ?? 0;
  const release = useKnowledgeReleaseData(groupId, canManage, fileCount > 0 || knownVersion > 0, detail?.group.mode === 'CALIBER');
  const { reload: reloadRelease } = release;
  const workflow = canManage ? release.workflow : null;
  const publishedVersion = workflow?.publishedVersion ?? knownVersion;
  const queryAvailable = workflow?.queryAvailable ?? publishedVersion > 0;
  const coach = useQueryCoachData(groupId, canManage && publishedVersion > 0);
  const [modelActivity, setModelActivity] = useState<ModelingHeaderActivity | null>(null);
  const lastModelActivity = useRef<ModelingHeaderActivity | null>(null);
  const updateModelActivity = useCallback((activity: ModelingHeaderActivity | null) => {
    // Recheck the server when leaving the chat with a running turn. The workbench no longer owns its progress.
    if (!activity && lastModelActivity.current?.assistant === 'running') void reloadRelease();
    lastModelActivity.current = activity;
    setModelActivity(activity);
  }, [reloadRelease]);
  const tour = useTour();
  const singleViewTour = tourLocksDraftDrawer(tour, groupId);
  const [ruleCount, setRuleCount] = useState<number>();
  const [publishedAt, setPublishedAt] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const queryAllowed = !!detail?.group.canQuery && queryAvailable && !detail.group.deletionPending;
  const queryPreparation = useQueryReadiness(queryAllowed ? groupId : undefined, publishedVersion);
  const preparing = queryPreparation ?? null;
  const [busy, setBusy] = useState(false);
  const [uploadPolicy, setUploadPolicy] = useState<UploadPolicy | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  useEffect(() => {
    if (!canManage || searchParams.get('upload') !== '1') return;
    setUploadOpen(true);
    setSearchParams(previous => { const next = new URLSearchParams(previous); next.delete('upload'); next.set('section', 'datasets'); return next; }, { replace: true });
  }, [canManage, searchParams, setSearchParams]);
  const [sampleBusy, setSampleBusy] = useState(false);
  const onboarding = useOnboarding();
  // A status request that failed (e.g. while the service restarted) is never retried by itself; the
  // upload dialog reloads it so the built-in samples, and the tour step pointing at them, appear.
  useEffect(() => { if (uploadOpen && !onboarding?.samples.length) void refreshOnboarding(true); }, [uploadOpen]); // eslint-disable-line react-hooks/exhaustive-deps
  const tourActiveNow = tourActive();
  const openUpload = () => { tourSignal('source-chosen', { groupId, route: 'upload' }); setUploadOpen(true); };
  const openAssociate = () => { tourSignal('source-chosen', { groupId, route: 'datasource' }); setAssociateOpen(true); };
  const [query, setQuery] = useState('');
  const uploadStatuses = useSyncExternalStore(subscribeUploadStatuses, () =>
    getUploadStatuses(uploadScope),
  );
  const [associateOpen, setAssociateOpen] = useState(false);
  const [associatingTables, setAssociatingTables] = useState<Set<string>>(new Set());
  const [association, setAssociation] = useState<AssociationState | null>(null);
  const [importHistoryOpen, setImportHistoryOpen] = useState(false);
  /** 多工作表选择弹窗。 */
  interface SheetPick {
    file: File;
    sheets: string[];
    selected: string;
  }
  const [sheetPicks, setSheetPicks] = useState<SheetPick[]>([]);
  const [sheetPickerBusy, setSheetPickerBusy] = useState(false);
  const fileRef = useRef<HTMLInputElement | null>(null);

  const hasPendingUploads = uploadStatuses.some(item => ['uploading', 'importing', 'publishing', 'uncertain'].includes(item.status));
  useEffect(() => {
    if (!canManage) return;
    let active = true;
    void getUploadPolicy().then(policy => { if (active) setUploadPolicy(policy); }).catch(e => {
      if (active) setError(e instanceof Error ? e.message : String(e));
    });
    return () => { active = false; };
  }, [canManage]);
  useEffect(() => {
    if (!canManage || hasExternalDatasets || !uploadPolicy?.statusAvailable) return;
    let cancelled = false;
    let fetching = false;
    const poll = async () => {
      if (fetching) return;
      fetching = true;
      try {
        const items = await getServerUploadStatuses(groupId);
        const recovered = items.some(item => item.phase === 'IMPORTED_MODEL_PENDING')
          ? await getModelingWorkflow(groupId).catch(() => null) : null;
        if (cancelled) return;
        mutateUploadStatuses(uploadScope, previous => {
          const rows = [...previous];
          const latest = new Map(items.map(item => [item.fileName, item]));
          const phases: Record<string, UploadStatus['status']> = { UPLOADING: 'uploading', IMPORTING: 'importing', PUBLISHING: 'publishing', COMPLETED: 'ready', IMPORTED_MODEL_PENDING: 'model_pending', FAILED: 'failed', CANCELLED: 'uncertain' };
          for (const item of latest.values()) {
            const index = rows.findIndex(row => row.name === item.fileName);
            if (index >= 0 && rows[index].uploadId && rows[index].uploadId !== item.uploadId) continue;
            const recoveredReady = item.phase === 'IMPORTED_MODEL_PENDING' && recovered?.queryAvailable && !recovered.draftChanged && recovered.mdlState === 'PUBLISHED';
            const ready = recoveredReady || item.phase === 'COMPLETED';
            const update = { name: item.fileName, uploadId: item.uploadId, datasetId: item.datasetId,
              status: recoveredReady ? 'ready' as const : phases[item.phase] ?? 'uncertain' as UploadStatus['status'], error: recoveredReady ? undefined : item.error,
              file: ready ? undefined : index >= 0 ? rows[index].file : undefined };
            if (index >= 0) rows[index] = { ...rows[index], ...update }; else rows.push(update);
          }
          return rows;
        });
      } catch { /* A failed status poll must not turn an upload into a failed import. */ }
      finally { fetching = false; }
    };
    void poll();
    const timer = busy || hasPendingUploads ? window.setInterval(() => void poll(), 1500) : undefined;
    return () => { cancelled = true; if (timer) window.clearInterval(timer); };
  }, [groupId, uploadScope, busy, hasPendingUploads, uploadPolicy?.statusAvailable, canManage, hasExternalDatasets]);

  const refreshSequence = useRef(0);
  const refresh = useCallback(async () => {
    const sequence = ++refreshSequence.current;
    const isCurrent = () => activeGroup.current === groupId && sequence === refreshSequence.current;
    try {
      const next = await getGroupDetail(groupId);
      if (!isCurrent()) return;
      setDetail(next);
      setError(null);
      const mayManage = next.group.canManage && !next.group.deletionPending;
      const hasPublished = (next.publishedVersion ?? next.group.publishedVersion ?? 0) > 0;
      const ruleFiles = mayManage && (next.datasets.length > 0 || hasPublished)
        ? getMdlPreview(groupId).then(preview => {
          if (isCurrent()) setPublishedAt(preview.mdlPublishedAt);
          return preview.files;
        })
        : !next.group.deletionPending && next.group.canQuery && hasPublished
          ? getKnowledgeBaseBrowse(groupId).then(browse => browse.knowledge)
          : Promise.resolve(undefined);
      void ruleFiles.then(files => {
        if (!isCurrent()) return;
        setRuleCount(files?.filter(file => /^knowledge\/rules\/[^/]+\.md$/.test(file.path))
          .reduce((count, file) => count + parseRuleFile(file.path, file.content).length, 0));
      }).catch(() => { if (isCurrent()) setRuleCount(undefined); });
      setAssociatingTables(new Set());
      return next;
    } catch (e) {
      if (!isCurrent()) return;
      setDetail(null); setRuleCount(undefined);
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [groupId]);

  useEffect(() => {
    void refresh();
    const onUpdate = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) void refresh();
    };
    window.addEventListener('modeling:updated', onUpdate);
    return () => window.removeEventListener('modeling:updated', onUpdate);
  }, [groupId, refresh]);

  useEffect(() => {
    if (!canManage || !release.review?.assistantRunning || (modelRoute && modelActivity?.assistant === 'running')) return;
    let cancelled = false;
    let timer: number;
    const watch = async () => {
      try {
        const draft = await getDraftReview(groupId);
        if (cancelled) return;
        if (!draft.assistantRunning) {
          window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
          return;
        }
      } catch { /* Keep the last reported run state until the server responds again. */ }
      if (!cancelled) timer = window.setTimeout(watch, 2500);
    };
    timer = window.setTimeout(watch, 2500);
    return () => { cancelled = true; window.clearTimeout(timer); };
  }, [modelRoute, modelActivity?.assistant, canManage, release.review?.assistantRunning, groupId]);

  const startsFirstModel = detail?.datasets.length === 0
    && !(detail.publishedVersion ?? detail.group.publishedVersion);
  const refreshDetail = async () => { await Promise.all([refresh(), reloadRelease()]); };

  async function finishImport(startFirstModel: boolean, startedAt: string, problem?: string) {
    const updated = await refresh();
    if (activeGroup.current !== groupId || currentLocation.current !== startedAt) return;
    void reloadRelease();
    if (problem) {
      setError(problem);
      setImportHistoryOpen(true);
      return;
    }
    if (startFirstModel && updated?.datasets.length && updated.group.canManage && !updated.group.deletionPending
      && !(updated.publishedVersion ?? updated.group.publishedVersion)) {
      navigate(modelingPreparationPath(groupId));
    }
  }

  function patchStatus(name: string, patch: Partial<UploadStatus>) {
    mutateUploadStatuses(uploadScope, prev =>
      prev.map(s => (s.name === name ? { ...s, ...patch } : s)),
    );
  }

  async function doUpload(
    files: File[],
    sheetMap: Map<string, string>,
  ) {
    const startedAt = currentLocation.current;
    let prepared = 0;
    const batchErrors: string[] = [];
    const policy = await getUploadPolicy();
    setUploadOpen(false);
    tourSignal('sample-started', { groupId });
    mutateUploadStatuses(uploadScope, prev => [
      ...prev.filter(row => !files.some(file => file.name === row.name)),
      ...files.map(f => ({ name: f.name, status: 'queued' as const, file: f, selectedSheet: sheetMap.get(f.name) })),
    ]);
    setBusy(true);
    setError(null);
    const results = await settleUploads(files, policy.concurrentFiles, async f => {
      const name = f.name.replace(/\.[^.]+$/, '');
      const selectedSheet = sheetMap.get(f.name);
      const uploadId = crypto.randomUUID();
      patchStatus(f.name, { status: 'uploading', uploadId, error: undefined });
      const dataset = await uploadDataset(groupId, name, f, false, selectedSheet, uploadId);
      patchStatus(f.name, { status: 'ready', datasetId: dataset.id, file: undefined });
      return { file: f, name, selectedSheet };
    });

    const overwriteCandidates: { file: File; name: string; selectedSheet?: string }[] = [];
    results.forEach((r, i) => {
      if (r.status === 'fulfilled') {
        prepared++;
        patchStatus(files[i].name, { status: 'ready' });
      } else {
        const msg = r.reason instanceof Error ? r.reason.message : String(r.reason);
        if (!msg.includes('already exists')) batchErrors.push(`${files[i].name}：${msg}`);
        if (msg.includes('already exists')) {
          overwriteCandidates.push({
            file: files[i],
            name: files[i].name.replace(/\.[^.]+$/, ''),
            selectedSheet: sheetMap.get(files[i].name),
          });
        } else {
          const failure = r.reason instanceof DatasetUploadError ? r.reason : undefined;
          patchStatus(files[i].name, { status: failure?.code === 'IMPORTED_MODEL_PENDING' ? 'model_pending' : failure?.code === 'UNCERTAIN' ? 'uncertain' : 'failed', error: msg, datasetId: failure?.datasetId });
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
        const overwriteResults = await settleUploads(overwriteCandidates, policy.concurrentFiles, candidate => {
          const uploadId = crypto.randomUUID();
          patchStatus(candidate.file.name, { status: 'uploading', uploadId, error: undefined });
          return uploadDataset(groupId, candidate.name, candidate.file, true, candidate.selectedSheet, uploadId);
        });
        overwriteResults.forEach((r, i) => {
          if (r.status === 'fulfilled') {
            prepared++;
            patchStatus(overwriteCandidates[i].file.name, { status: 'ready', datasetId: r.value.id, file: undefined, error: undefined });
          } else {
            const msg = r.reason instanceof Error ? r.reason.message : String(r.reason);
            batchErrors.push(`${overwriteCandidates[i].file.name}：${msg}`);
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
    await finishImport(!!startsFirstModel && prepared === files.length, startedAt, batchErrors.join('、') || undefined);
  }

  // spec105: the built-in files take the normal upload path; each uses its first worksheet.
  async function uploadSamples() {
    if (busy || !onboarding?.samples.length) return;
    setSampleBusy(true);
    setError(null);
    try {
      const files = await Promise.all(onboarding.samples.map(fetchSample));
      setUploadOpen(false);
      await doUpload(files, new Map());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSampleBusy(false);
    }
  }

  async function uploadFiles(files: FileList | File[]) {
    if (busy || sheetPickerBusy || sheetPicks.length > 0) return;
    const list = Array.from(files);
    let policy: UploadPolicy;
    try { policy = await getUploadPolicy(); validateUploadSelection(list, policy); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); return; }
    const xlsxFiles = list.filter(f => f.name.toLowerCase().endsWith('.xlsx'));
    const otherFiles = list.filter(f => !f.name.toLowerCase().endsWith('.xlsx'));

    if (xlsxFiles.length === 0) {
      await doUpload(list, new Map());
      return;
    }

    setSheetPickerBusy(true);
    setError(null);
    try {
      const inspected = await settleUploads(xlsxFiles, policy.concurrentFiles, async f => ({ file: f, sheets: await listSheets(f) }));
      const rejected = inspected.find(result => result.status === 'rejected');
      if (rejected?.status === 'rejected') throw rejected.reason;
      const sheetResults = inspected.flatMap(result => result.status === 'fulfilled' ? [result.value] : []);

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
      setUploadOpen(false);
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
    const external = detail?.datasets.find(d => d.id === id)?.origin === 'datasource';
    if (!window.confirm(external ? '移除这张表的关联？数据库中的原始表和数据会保留。' : '删除该数据集？其数据和物理表将一并删除。')) return;
    setBusy(true);
    try {
      await deleteDataset(id);
      await refreshDetail();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const visibleDatasets = detail?.datasets.filter(d => `${d.name} ${d.sourceFileName ?? ''} ${d.description ?? ''}`.toLowerCase().includes(query.trim().toLowerCase())) ?? [];
  const hasUncountedExternalTables = detail?.datasets.some(d => d.origin === 'datasource' && d.rowCount == null);
  const hasLocalDatasets = detail?.datasets.some(dataset => dataset.origin !== 'datasource') ?? false;
  const importing = !!association && association.phase !== 'done' || uploadStatuses.some(item => ACTIVE_UPLOAD.includes(item.status));
  useEffect(() => {
    if (importing) setImportHistoryOpen(true);
  }, [importing]);

  const requestedSection = searchParams.get('section');
  const current: KnowledgeSection = modelRoute ? modelingSection(searchParams)
    : datasetId || requestedSection === 'datasets' || searchParams.get('upload') === '1' || searchParams.get('view') === 'files' ? 'datasets'
    : requestedSection === 'members' || requestedSection === 'settings' ? requestedSection : 'overview';
  const leave = useRef<(() => boolean | Promise<boolean>) | null>(null);
  const selectModel = useRef<KnowledgeModelingHost['select']['current']>(null);
  const [versionTarget, setVersionTarget] = useState<HTMLDivElement | null>(null);
  const [actionsTarget, setActionsTarget] = useState<HTMLDivElement | null>(null);
  const decision = releaseDecision({
    workflow, files: release.review?.changes ?? [], assetTasks: release.assetTasks, incompleteViews: release.incompleteViews,
    confirmations: release.confirmations?.length ?? 0,
    questionIds: coach.reviews.filter(question => question.status === 'REVIEW').map(question => question.question.id),
    removalIds: coach.pendingRemovals.map(({ question }) => question.id),
    publicationFailure: release.publicationFailure,
    caliberBlocker: caliberReleaseBlocker(release.calibers),
  });
  const host: KnowledgeModelingHost = { versionTarget, actionsTarget, leave, select: selectModel, release, decision, coach, importing, onActivity: updateModelActivity };
  const go = async (destination: string) => {
    if (!leave.current || await leave.current()) navigate(destination);
  };
  const openDataset = (id: string, tab?: 'fields') => {
    if (id !== datasetId || tab) void go(datasetPath(groupId, id) + (tab ? '?tab=' + tab : ''));
  };
  const select = (section: KnowledgeSection) => {
    if (section === 'overview' || section === 'datasets' || section === 'members' || section === 'settings') {
      if (section !== current || datasetId) void go(rootPath + (section === 'overview' ? '' : `?section=${section}`));
      return;
    }
    if (modelRoute && selectModel.current) { selectModel.current(section); return; }
    const params = new URLSearchParams(modelingParams(section));
    void go(`${modelPath}?${params}`);
  };
  const activity = modelRoute ? modelActivity : null;
  const canAsk = queryAllowed && queryPreparation === null && !activity?.setup && !tourHoldsQuery(tour, groupId);
  const releaseStatus = release.loading || coach.loading ? 'loading' : release.error || coach.error ? 'failed' : 'ready';
  const ask = () => void go(`/chat?groups=${encodeURIComponent(groupId)}`);
  const review = (section: ReviewSection = 'changes') => {
    const params = modelRoute ? new URLSearchParams(searchParams) : new URLSearchParams(modelingParams('graph'));
    params.set('review', section === 'changes' ? '1' : section);
    void go(`${modelPath}?${params}`);
  };
  const showImport = () => { setImportHistoryOpen(true); select('datasets'); };
  const statusAction = (() => {
    if (detail?.group.deletionPending) return <span className="kbw-flow-status" role="status">正在清理知识库</span>;
    if (activity?.setup || singleViewTour) return null;
    if (activity?.publishing) return <span className="kbw-flow-status" role="status">正在检查并发布…</span>;
    if (canManage && importing) return { label: '正在导入 · 查看进度', action: showImport };
    if (canManage && activity?.assistant === 'awaiting') return {
      label: '查看助手待处理项', action: release.confirmations?.length ? () => review('tasks') : () => void go(modelPath + '?asset=modeling&mode=chat'),
    };
    if (canManage && (activity?.assistant === 'running' || release.review?.assistantRunning)) {
      return { label: '正在建模 · 查看进度', action: () => void go(modelPath + '?asset=modeling&mode=chat') };
    }
    if (preparing) return <span className="kbw-flow-status" role="status" title={preparing}>正在准备问数…</span>;
    if (!canManage) return queryAllowed && queryPreparation === undefined
      ? <span className="kbw-flow-status" role="status">正在读取问数状态…</span> : null;
    if (releaseStatus === 'loading') return <span className="kbw-flow-status" role="status">正在读取知识库状态…</span>;
    if (releaseStatus === 'failed') return { label: '重试读取状态', action: () => { void reloadRelease(); coach.reload(); } };
    if (!fileCount) return { label: '添加数据集', action: () => current === 'datasets' && !datasetId ? openUpload() : select('datasets') };
    if (decision.blocker?.kind === 'data') return { label: '检查数据集', action: () => select('datasets') };
    if (decision.blocker?.kind === 'model') return { label: '继续建模', action: () => void go(modelingPreparationPath(groupId)) };
    if (decision.blocker?.kind === 'incomplete') return { label: '处理未完成视图', action: () => review('tasks'), tone: 'blocked' };
    if (decision.blocker?.kind === 'asset') {
      const target = decision.blocker.target;
      const verify = target.step === 'VERIFY_PENDING';
      const params = new URLSearchParams({ asset: 'assets', mode: 'board', viewGroup: 'pending', viewItem: `${target.kind}:${target.name}`,
        viewVersion: 'draft', viewStage: verify ? 'verify' : 'overview' });
      return { label: verify ? '去验证视图' : '去核查视图', action: () => void go(`${modelPath}?${params}`), tone: 'blocked' };
    }
    if (decision.blocker?.kind === 'caliber') return { label: '完成口径', action: () => void go(`${modelPath}?asset=calibers&mode=board&check=1`), tone: 'blocked' };
    if (decision.blocker?.kind === 'engineering') return { label: '查看校验问题', action: () => review('checks'), tone: 'blocked' };
    if (!publishedVersion && release.screening?.some(table => !table.confirmed)) {
      return { label: '确认建模字段', action: () => void go(modelingPreparationPath(groupId)) };
    }
    if (!publishedVersion && !workflow?.canPublish && !release.review?.changes.some(change => change.sources.includes('auto') || change.sources.includes('confirm'))) {
      return decision.taskCount ? { label: `处理待办 · ${decision.taskCount} 项`, action: () => review('tasks'), tone: 'pending' }
        : { label: '继续建模', action: () => void go(modelingPreparationPath(groupId)) };
    }
    if (decision.objectCount) return { label: `审阅草稿 · ${decision.objectCount} 项`, action: () => review() };
    if (decision.republish) return { label: '审阅并重新发布', action: () => review() };
    if (decision.taskCount) return { label: `处理待办 · ${decision.taskCount} 项`, action: () => review('tasks'), tone: 'pending' };
    return !publishedVersion ? { label: '继续建模', action: () => void go(modelingPreparationPath(groupId)) } : null;
  })();
  const hasStatusAction = statusAction !== null && 'action' in statusAction;
  const hasReleaseWork = decision.objectCount > 0 || decision.taskCount > 0 || decision.republish || !!decision.blocker;

  if (modelRoute && searchParams.get('asset') === 'publish') return <Navigate to={modelingReviewPath(groupId)} replace />;
  if (searchParams.get('view') === 'modeling' && !modelRoute && canManage) return <Navigate to={modelPath} replace />;
  return (
    <KnowledgeWorkspaceShell detail={detail} current={current} datasetId={datasetId}
      version={publishedVersion} draftCount={releaseStatus === 'ready' ? decision.objectCount : undefined}
      statusAction={hasStatusAction ? <button type="button" className="da-btn da-btn-primary kbw-flow-action"
        data-tone={statusAction.tone} onClick={statusAction.action}>{statusAction.label}</button> : statusAction}
      queryAction={canAsk && <button type="button" className={`da-btn kbw-query-action${hasStatusAction ? '' : ' da-btn-primary'}`} onClick={ask}>
        <Icon name="chat" size="sm" />去问数</button>}
      onLibrary={() => void go('/configure/datasets')}
      onSelect={select} onDataset={openDataset} versionRef={setVersionTarget} actionsRef={setActionsTarget}>
      {error && current !== 'datasets' && <div className="kbw-error kw-alert" role="alert">{error}</div>}
      {!detail && !error && <div className="kbw-loading" role="status">正在打开知识库…</div>}
      {detail && current === 'overview' && <KnowledgeWorkspaceOverview detail={detail} decision={decision} releaseStatus={releaseStatus} publishedViews={release.publishedViews}
        version={publishedVersion} ruleCount={ruleCount} publishedAt={canManage ? publishedAt : null}
        queryAvailable={queryAvailable} preparing={preparing} onGo={select}
        onReview={canManage && fileCount > 0 && releaseStatus === 'ready' && hasReleaseWork && !singleViewTour
          ? () => review(decision.blocker?.kind === 'engineering' ? 'checks' : decision.objectCount || decision.republish ? 'changes' : 'tasks') : undefined}
        onDataset={openDataset}
        onAsk={canAsk ? ask : undefined}
        onUpload={() => { select('datasets'); openUpload(); }} onAssociate={() => { select('datasets'); openAssociate(); }} />}
      {detail && datasetId && <DatasetDetailPage key={datasetId} detail={detail} datasetId={datasetId} leave={leave} onChanged={refreshDetail} />}
      {detail && modelRoute && (fileCount > 0 || !!publishedVersion) && !detail.group.deletionPending && <Suspense fallback={<div className="kbw-loading" role="status">正在加载语义模型…</div>}>
        <SemanticModelingPage group={detail.group} host={host} />
      </Suspense>}
      {detail && modelRoute && !fileCount && !publishedVersion && !detail.group.deletionPending && <div className="kbw-page">
        <WorkspaceHeading title="语义模型" description="先添加数据集，再整理字段和业务口径。" />
        <section className="kbw-card kbw-empty-intro"><div className="kbw-empty-icon"><Icon name="graph" /></div><h2>还没有可建模的数据集</h2>
          <p>上传数据文件或关联数据库表后，可以在这里查看模型、关系和业务规则。</p>
          <button type="button" className="da-btn da-btn-primary" onClick={() => select('datasets')}>前往数据集<Icon name="chevron" size="sm" /></button></section>
      </div>}
      {detail?.group.deletionPending && modelRoute && <div className="kbw-page"><p className="kw-alert" role="status">知识库正在清理。</p>
        {detail.group.canManage && <button type="button" className="da-btn" onClick={() => select('settings')}>查看删除状态</button>}</div>}
      {detail && current === 'members' && (canManage
        ? <KnowledgeBaseMembersModal key={groupId} groupId={groupId} name={detail.group.name} onChanged={() => void refresh()} inline />
        : <div className="kbw-page"><WorkspaceHeading title="成员与权限" /><section className="kbw-card kbw-card-pad"><h2>使用权限</h2><p>你可以查看数据集、浏览已发布的语义模型并基于此库提问。联系知识库管理者变更授权。</p></section></div>)}
      {detail && current === 'settings' && <KnowledgeWorkspaceSettings key={groupId} detail={detail} version={publishedVersion} workflow={workflow}
        leave={leave} onChanged={refreshDetail} onPublish={() => void go(modelingReviewPath(groupId))} />}
      <input ref={fileRef} type="file" multiple accept=".xlsx,.xls,.csv" hidden onChange={e => { if (e.target.files?.length) void uploadFiles(e.target.files); e.target.value = ''; }} />
      {current === 'datasets' && !datasetId && <div className="kbw-page kbw-data-page kd-page dataset-workspace">
        <WorkspaceHeading title="数据集" description={detail ? `共 ${fileCount} 个数据集${fileCount ? '' : ' · 添加数据后可以整理字段和建模'}` : undefined}>
          {canManage && !fileCount && <WorkspaceMenu label="添加数据集" triggerLabel="添加数据集" triggerIcon="plus" items={[
            { label: '上传文件', icon: 'upload', disabled: busy, onClick: openUpload },
            { label: '关联数据源', icon: 'database', disabled: busy, onClick: openAssociate },
          ]} />}
        </WorkspaceHeading>
        {error && <div className="kw-alert" role="alert">{error}</div>}
        {canManage && fileCount > 0 && !publishedVersion && !importing && <section className="kbw-card kbw-data-next" aria-label="首次建模下一步">
          <span className="kbw-empty-icon"><Icon name="model" size="sm" /></span>
          <div><h2>接下来完成首次建模</h2><p>在建模对话中确认字段、完善模型并发布。发布后即可基于此库问数。</p></div>
          <button type="button" className="da-btn da-btn-primary" onClick={() => void go(modelPath)}>进入建模对话<Icon name="chevron" size="sm" /></button>
        </section>}
        {detail && <KnowledgeSourceConnection datasets={detail.datasets} canManage={canManage} onAssociate={openAssociate} />}
        <details className="kbw-card kbw-imports" aria-label="导入记录" open={importing || importHistoryOpen}>
          <summary className="kbw-import-summary" onClick={event => {
            event.preventDefault();
            if (!importing) setImportHistoryOpen(value => !value);
          }}>
            <Icon name="upload" size="sm" /><strong>导入记录</strong><span>·</span>
            <span>{importing ? '正在导入' : '暂无进行中的导入'}</span>
            {!importing && <em>{importHistoryOpen ? '收起历史' : '查看历史'}<Icon name="chevron" size="sm" /></em>}
          </summary>
          {!association && !uploadStatuses.length && <p className="kbw-card-copy">暂无导入记录。</p>}
          {association && <AssociationProgress state={association} stayOnPage={!tourActiveNow} />}
          {uploadStatuses.length > 0 && (
            <ul className="kw-up-list">
              {uploadStatuses.map((s, i) => (
                <li key={`up-${s.name}-${i}`} className={`kw-up-row is-${s.status}`}>
                  <span className="kw-up-file" aria-hidden="true"><Icon name="file" size="sm" /></span>
                  <span className="kw-up-name">
                    <span title={s.name}>{s.name}</span>
                    {s.error && <small>{s.error}</small>}
                  </span>
                  <span className="kw-up-status">
                    {ACTIVE_UPLOAD.includes(s.status) && <span className="da-spinner da-spinner-sm" aria-hidden="true" />}
                    {s.status === 'ready' && <Icon name="check" size="sm" />}
                    {s.status === 'failed' && <Icon name="warn" size="sm" />}
                    {STATUS_LABEL[s.status]}
                  </span>
                  <span className="kw-up-actions">
                    {s.status === 'model_pending' && <button className="da-btn da-btn-sm" onClick={() => void go(modelPath)}>查看建模进度</button>}
                    {s.status === 'uncertain' && <button className="da-btn da-btn-sm" onClick={() => void refresh()}>刷新确认结果</button>}
                    {s.status === 'failed' && <>
                      <button
                        className="da-btn da-btn-sm"
                        disabled={!s.file || busy}
                        title={!s.file ? '请重新选择原文件后上传' : '重新导入此文件'}
                        onClick={async () => {
                          if (!s.file) return;
                          const startedAt = currentLocation.current;
                          const retryId = crypto.randomUUID();
                          patchStatus(s.name, { status: 'uploading', error: undefined, uploadId: retryId });
                          try {
                            const datasetName = s.name.replace(/\.[^.]+$/, '');
                            const imported = await uploadDataset(groupId, datasetName, s.file, false, s.selectedSheet, retryId);
                            patchStatus(s.name, { status: 'ready', datasetId: imported.id, file: undefined, error: undefined });
                            const otherImports = uploadStatuses.some(row => row.name !== s.name && row.status !== 'ready');
                            await finishImport(!!startsFirstModel && !otherImports, startedAt);
                          } catch (e) {
                            const msg = e instanceof Error ? e.message : String(e);
                            patchStatus(s.name, { status: e instanceof DatasetUploadError && e.code === 'IMPORTED_MODEL_PENDING' ? 'model_pending' : e instanceof DatasetUploadError && e.code === 'UNCERTAIN' ? 'uncertain' : 'failed', error: msg, datasetId: e instanceof DatasetUploadError ? e.datasetId : undefined });
                          }
                        }}
                      >
                        重试
                      </button>
                      <button
                        className="da-btn da-btn-sm da-btn-ghost"
                        aria-label="移除这条记录"
                        title="移除这条记录"
                        onClick={() => mutateUploadStatuses(uploadScope, prev => prev.filter(x => x.name !== s.name))}
                      >
                        <Icon name="close" size="sm" />
                      </button>
                    </>}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </details>
        {detail && <>
          {fileCount > 0 && <div className="kw-toolbar dw-toolbar"><label className="kw-search"><Icon name="search" size="sm" /><input value={query} onChange={e => setQuery(e.target.value)} placeholder="搜索数据集名称或来源…" aria-label="搜索数据集" /></label></div>}
          {detail && fileCount === 0 && (canManage ? <div className="kd-empty-grid" data-tour="kb-source">
            <div className={`kd-empty-drop${dragOver ? ' dragging' : ''}`} onDragOver={e => { e.preventDefault(); setDragOver(true); }} onDragLeave={() => setDragOver(false)} onDrop={e => { e.preventDefault(); setDragOver(false); if (!busy && e.dataTransfer.files.length) void uploadFiles(e.dataTransfer.files); }}>
              <span className="kd-tile"><Icon name="upload" /></span><h2>把 Excel 或 CSV 拖到这里</h2><p>先把业务数据放进来，再整理字段与业务口径。</p>
              <button className="da-btn da-btn-primary" disabled={busy} onClick={openUpload}>选择文件<Icon name="chevron" size="sm" /></button>
              <small>支持 .xlsx / .xls / .csv · 单文件 {uploadPolicy ? uploadPolicy.maxFileBytes / 1024 ** 2 : 50} MB · 最多 {uploadPolicy?.maxFiles ?? 10} 个</small>
            </div>
            <div className="kd-empty-aside"><section><h3><Icon name="database" size="sm" />或者关联数据库表</h3><p>连接已有数据源，选择需要分析的表，原始数据保留在数据库中。</p><button className="da-btn" disabled={busy} onClick={openAssociate}>关联数据源<Icon name="chevron" size="sm" /></button><button className="dw-text" onClick={() => navigate('/configure/datasources')}>管理数据源</button></section>
            <section><h3><Icon name="file" size="sm" />没有文件？用示例数据</h3><p>使用内置演示文件，体验从建模到问数的完整过程。</p><button className="da-btn" disabled={busy || sampleBusy} onClick={openUpload}>查看示例数据<Icon name="chevron" size="sm" /></button></section></div>
          </div> : <div className="kw-empty"><EmptyIllustration variant="table" caption="这个知识库还没有数据" /></div>)}
          {detail && fileCount > 0 && <><div className="kw-tablebox dw-tablebox"><div className="kw-table-scroll"><table className="kw-table dw-table" aria-label="知识库数据集"><thead><tr><th scope="col">数据集</th><th scope="col">来源</th><th scope="col">字段</th><th scope="col">业务说明</th><th scope="col" className="end">操作</th></tr></thead><tbody>
            {visibleDatasets.map(d => <tr key={d.id}>
              <td><button className="dw-name" title={d.name} onClick={() => openDataset(d.id)}><span className="dw-table-icon" aria-hidden="true"><Icon name="table" size="sm" /></span><span className="dw-name-text">{d.name}</span>{(d.currentVersion ?? 1) > 1 && <span className="kw-badge">v{d.currentVersion}</span>}</button></td>
              <td><span className="kd-source-badge"><Icon name={d.origin === 'datasource' ? 'database' : 'file'} size="sm" />{d.origin === 'datasource' ? '数据源' : d.origin === 'derived' ? '派生' : '文件'}</span></td>
              <td>{d.columns.length}</td><td><span className="kbw-field-count" data-missing={describedColumns(d) < d.columns.length || undefined}>{describedColumns(d)}/{d.columns.length}</span></td>
              <td className="end"><div className="dw-row-actions"><button className="dw-text" aria-label={`查看 ${d.name} 的数据`} onClick={() => openDataset(d.id)}>查看</button><WorkspaceMenu label={`${d.name}的更多操作`} items={[{ label: '查看数据集', icon: 'table', onClick: () => openDataset(d.id) }, ...(canManage ? [{ label: d.origin === 'datasource' ? '移除关联' : '删除数据集', icon: 'trash' as const, danger: true, disabled: busy, onClick: () => void handleDeleteDataset(d.id) }] : [])]} /></div></td>
            </tr>)}
            {visibleDatasets.length === 0 && <tr><td colSpan={5} className="kw-empty">没有匹配的数据集</td></tr>}
          </tbody></table></div><div className="kw-tablebottom"><span>{query.trim() ? `找到 ${visibleDatasets.length} 个，共 ${fileCount} 个数据集` : `共 ${fileCount} 个数据集`}</span><span className="dw-table-note"><Icon name="info" size="sm" />{hasUncountedExternalTables ? '外部数据表总行数未统计 · 按需采样，完整数据保留在源库' : '点击名称查看数据与字段'}</span></div></div>
          {canManage && <div className="kbw-data-entry-actions">
            <div className="kbw-data-entry-card">
              <button type="button" disabled={busy || hasExternalDatasets} onClick={openUpload}><Icon name="upload" size="sm" /><span><strong>上传文件</strong><small>{hasExternalDatasets ? '此库通过数据源接入。上传文件需在未关联数据源的知识库中进行。' : '从 Excel 或 CSV 导入数据'}</small></span>{!hasExternalDatasets && <Icon name="chevron" size="sm" />}</button>
              {hasExternalDatasets && <DatasetSourceHelp />}
            </div>
            <div className="kbw-data-entry-card">
              <button type="button" disabled={busy || hasLocalDatasets} onClick={openAssociate}><Icon name="database" size="sm" /><span><strong>关联数据表</strong><small>{hasLocalDatasets ? '此库已有上传文件。关联数据表需在未上传文件的知识库中进行。' : '从已有数据源选择业务表'}</small></span>{!hasLocalDatasets && <Icon name="chevron" size="sm" />}</button>
              {hasLocalDatasets && <DatasetSourceHelp />}
            </div>
          </div>}</>}
        </>}
      </div>}
      {canManage && uploadOpen && <div className="da-modal-overlay" onClick={() => setUploadOpen(false)}><div className="da-modal-shell" style={{ width: 'min(560px, 92vw)' }} onClick={e => e.stopPropagation()}><div className="da-modal-head"><div className="da-modal-title">上传数据文件</div></div><div className="da-modal-body">{error && <div className="kw-alert" role="alert">{error}</div>}<div className="da-dropzone" role="button" tabIndex={0} style={dragOver ? { borderColor: 'var(--da-primary)' } : undefined} onClick={() => fileRef.current?.click()} onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fileRef.current?.click(); } }} onDragOver={e => { e.preventDefault(); setDragOver(true); }} onDragLeave={() => setDragOver(false)} onDrop={e => { e.preventDefault(); setDragOver(false); if (e.dataTransfer.files.length) uploadFiles(e.dataTransfer.files); }}><Icon name="upload" /><div style={{ marginTop: 12 }}>点击选择或拖入 Excel / CSV 文件</div><div className="da-small" style={{ marginTop: 8 }}>支持 .xlsx / .xls / .csv；单文件 {uploadPolicy ? uploadPolicy.maxFileBytes / 1024 ** 2 : 50} MB，最多 {uploadPolicy?.maxFiles ?? 10} 个，总计 {uploadPolicy ? uploadPolicy.maxSelectionBytes / 1024 ** 2 : 100} MB</div>{(busy || sheetPickerBusy) && <div className="da-small" style={{ marginTop: 12 }}>{sheetPickerBusy ? '检测工作表…' : '上传并解析中…'}</div>}</div>{!!onboarding?.samples.length && <div className="kw-samples"><strong>没有文件？用示例数据</strong><span className="da-small">系统内置，不用下载，点一下就会导入到这个知识库</span><ul>{onboarding.samples.map(name => <li key={name}><Icon name="file" size="sm" />{name}</li>)}</ul><button type="button" className="da-btn da-btn-primary" data-tour="sample-use" disabled={busy || sampleBusy} onClick={() => void uploadSamples()}>{sampleBusy ? '正在读取示例文件…' : '使用示例文件'}</button></div>}</div><div className="da-modal-foot"><button className="da-btn" onClick={() => setUploadOpen(false)}>关闭</button></div></div></div>}
      {canManage && associateOpen && (
        <AssociateTablesModal
          groupId={groupId}
          preferBuiltin={tourActiveNow}
          onClose={() => setAssociateOpen(false)}
          onAssociated={problem => void finishImport(!!startsFirstModel, location.key, problem)}
          onError={message => void finishImport(false, location.key, message)}
          onProgress={state => { if (activeGroup.current === groupId) setAssociation(state); }}
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

    </KnowledgeWorkspaceShell>
  );
}

function DatasetSourceHelp() {
  return <details className="kbw-source-help">
    <summary>了解区别</summary>
    <div>
      <p><strong>文件库</strong> 从 Excel 或 CSV 导入文件，更新时重新上传。</p>
      <p><strong>数据源库</strong> 关联数据库中的业务表，原表留在数据源。</p>
    </div>
  </details>;
}
