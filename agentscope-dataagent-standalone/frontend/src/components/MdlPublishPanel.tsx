import { useCallback, useEffect, useMemo, useState } from 'react';
import Icon from './Icon';
import type { ModelingWorkflow } from '../api/modelingWorkflow';
import { getMdlPreview, publishMdl, validateMdl } from '../api/semanticModeling';
import type {
  MdlIssue,
  MdlPreview,
  MdlPublishResult,
  MdlValidation,
} from '../api/semanticModeling';

type DiffLine = { kind: 'same' | 'add' | 'del'; text: string };

/** Minimal LCS line diff; MDL files are small YAML documents, so O(n*m) is fine. */
function diffLines(before: string, after: string): DiffLine[] {
  const a = before === '' ? [] : before.split('\n');
  const b = after === '' ? [] : after.split('\n');
  const n = a.length;
  const m = b.length;
  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }
  const out: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      out.push({ kind: 'same', text: a[i] });
      i++;
      j++;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      out.push({ kind: 'del', text: a[i] });
      i++;
    } else {
      out.push({ kind: 'add', text: b[j] });
      j++;
    }
  }
  while (i < n) out.push({ kind: 'del', text: a[i++] });
  while (j < m) out.push({ kind: 'add', text: b[j++] });
  return out;
}

function msg(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

function IssueList({ issues }: { issues: MdlIssue[] }) {
  if (issues.length === 0) return null;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginBottom: 10 }}>
      {issues.map((issue, idx) => {
        const isError = issue.severity === 'error';
        const color = isError ? 'var(--da-danger)' : 'var(--da-warn)';
        return (
          <div
            key={`${idx}-${issue.message}`}
            style={{ display: 'flex', alignItems: 'baseline', gap: 6, flexWrap: 'wrap' }}
          >
            <span className="da-badge" style={{ color, borderColor: color }}>
              {isError ? '错误' : '警告'}
            </span>
            <span className="da-badge">{issue.source === 'wren' ? 'Wren' : '本地'}</span>
            <span className="da-small" style={{ color }}>
              {issue.message}
            </span>
          </div>
        );
      })}
    </div>
  );
}

/**
 * MDL publish card (specs/010 M2): assembles the wren project for the knowledge base, renders a
 * per-file diff against the published snapshot, validates through `wren context validate
 * --strict` with inline structured errors, and publishes (build + snapshot + version bump).
 * Any failure leaves the previous snapshot serving; the card never edits modeling state.
 */
export default function MdlPublishPanel({
  groupId,
  onPublished,
  workflow,
  onReview,
}: {
  groupId: string;
  onPublished?: () => void | Promise<void>;
  workflow?: ModelingWorkflow | null;
  onReview?: () => void;
}) {
  const [preview, setPreview] = useState<MdlPreview | null>(null);
  const [validation, setValidation] = useState<MdlValidation | null>(null);
  const [result, setResult] = useState<MdlPublishResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [onlyChanges, setOnlyChanges] = useState(true);

  const load = useCallback(async () => {
    try {
      const fresh = await getMdlPreview(groupId);
      setPreview(fresh);
      setError(null);
      setSelected(prev =>
        prev && fresh.files.some(f => f.path === prev) ? prev : (fresh.files[0]?.path ?? null),
      );
    } catch (e) {
      setError(msg(e));
    }
  }, [groupId]);

  useEffect(() => {
    setPreview(null);
    setValidation(null);
    setResult(null);
    setSelected(null);
    load();
  }, [load]);

  const publishedByPath = useMemo(() => {
    const map = new Map<string, string>();
    for (const f of preview?.publishedFiles ?? []) map.set(f.path, f.content);
    return map;
  }, [preview]);

  const selectedFile = preview?.files.find(f => f.path === selected) ?? null;
  const lines = useMemo(() => {
    if (!selectedFile) return [];
    return diffLines(publishedByPath.get(selectedFile.path) ?? '', selectedFile.content);
  }, [selectedFile, publishedByPath]);

  const hasPublished = (preview?.publishedFiles.length ?? 0) > 0;
  const visibleLines = onlyChanges ? lines.filter(l => l.kind !== 'same') : lines;
  const added = lines.filter(l => l.kind === 'add').length;
  const removed = lines.filter(l => l.kind === 'del').length;

  async function run(label: string, fn: () => Promise<void>) {
    setBusy(label);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(msg(e));
    } finally {
      setBusy(null);
    }
  }

  const onValidate = () =>
    run('validate', async () => {
      setResult(null);
      setValidation(await validateMdl(groupId));
      window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
    });

  const onPublish = () =>
    run('publish', async () => {
      const outcome = await publishMdl(groupId);
      setResult(outcome);
      setValidation(null);
      if (outcome.ok) {
        await onPublished?.();
        await load();
      }
    });

  const issues = validation ? validation.issues : (preview?.issues ?? []);
  const changedLabel = !preview
    ? ''
    : preview.changed
      ? '有未发布变更'
      : '与已发布版本一致';

  return (
    <div className="da-card">
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 }}>
        <Icon name="upload" />
        <div className="da-h2" style={{ margin: 0 }}>
          发布业务模型
        </div>
        {preview && (
          <span
            className="da-badge"
            style={
              preview.changed
                ? { color: 'var(--da-warn)', borderColor: 'var(--da-warn)' }
                : { color: 'var(--da-success)', borderColor: 'var(--da-success)' }
            }
          >
            {changedLabel}
          </span>
        )}
        {preview?.mdlState === 'PUBLISHED' && (
          <span className="da-small" style={{ color: 'var(--da-text-3)' }}>
            当前 v{preview.mdlVersion}
            {preview.mdlPublishedAt
              ? `（${preview.mdlPublishedAt.slice(0, 19).replace('T', ' ')}）`
              : ''}
          </span>
        )}
        <div style={{ flex: 1 }} />
        {busy && <span className="da-spinner da-spinner-sm" />}
        <button className="da-btn da-btn-sm" disabled={busy !== null} onClick={load}>
          刷新预览
        </button>
        <button className="da-btn da-btn-sm" disabled={busy !== null} onClick={onValidate}>
          {busy === 'validate' ? '校验中…' : '工程校验'}
        </button>
        <button
          className="da-btn da-btn-primary da-btn-sm"
          disabled={busy !== null || !workflow?.canPublish || !workflow.draftChanged}
          onClick={onPublish}
        >
          {busy === 'publish' ? '发布中…' : '发布'}
        </button>
      </div>
      <div className="da-small" style={{ color: 'var(--da-text-3)', marginBottom: 10 }}>
        发布后问数使用新版本的业务口径。请检查本次变更与模型工程检查结果；预设问题测试可选，发布失败时保留原版本。
      </div>
      {workflow && <div style={{ marginBottom: 12 }}>
        <strong>本次业务模型变更</strong>
        <ul>{workflow.changedAssets.map(change => <li key={change.path}>
          {change.kind === 'ADDED' ? '新增' : change.kind === 'REMOVED' ? '移除' : '调整'}：{change.label}
        </li>)}</ul>
        {!workflow.draftChanged && <p className="da-small">没有待发布变更。</p>}
        <p className="da-small">分析问题已确认 {workflow.questionSummary.confirmed}/{workflow.questionSummary.total}。</p>
        {(workflow.questionSummary.incomplete + workflow.questionSummary.needsValidation + workflow.questionSummary.awaitingConfirmation > 0) &&
          <p className="da-small">部分可选问题待补充、验证或审阅，不阻碍模型发布。可继续问数并对最终答案反馈。</p>}
        {workflow.blockers.length > 0 && <div role="status">
          <ul>{workflow.blockers.map((item, i) => <li key={`${item.code}-${i}`}>{item.message}</li>)}</ul>
          <button className="da-btn" onClick={onReview}>前往验证与确认</button>
        </div>}
      </div>}

      {error && (
        <div className="da-small" style={{ color: 'var(--da-danger)', marginBottom: 8 }}>
          {error}
        </div>
      )}

      {result && (
        <div
          className="da-card"
          style={{
            padding: '8px 10px',
            marginBottom: 10,
            borderColor: result.ok ? 'var(--da-success)' : 'var(--da-danger)',
            background: 'var(--da-surface-sunken)',
          }}
        >
          <div
            className="da-small"
            style={{ color: result.ok ? 'var(--da-success)' : 'var(--da-danger)' }}
          >
            {result.ok
              ? `发布成功：v${result.mdlVersion}${
                  result.mdlPublishedAt
                    ? `（${result.mdlPublishedAt.slice(0, 19).replace('T', ' ')}）`
                    : ''
                }`
              : '发布失败，已发布版本保持不变'}
          </div>
          {!result.ok && result.output && (
            <div
              className="da-small"
              style={{
                fontFamily: 'var(--da-mono, monospace)',
                color: 'var(--da-text-3)',
                marginTop: 4,
                whiteSpace: 'pre-wrap',
              }}
            >
              {result.output.slice(0, 600)}
            </div>
          )}
        </div>
      )}

      <IssueList issues={issues} />

      {validation && (
        <div
          className="da-small"
          style={{
            color: validation.ok ? 'var(--da-success)' : 'var(--da-danger)',
            marginBottom: 8,
          }}
        >
          {validation.ok
            ? '工程校验通过；业务口径与结果仍需人员确认。'
            : `验证未通过：${validation.issues.length} 个问题`}
        </div>
      )}

      <details><summary>查看技术文件差异</summary>
      {(preview?.files.length ?? 0) === 0 ? (
        <div className="da-small" style={{ color: 'var(--da-text-3)' }}>
          暂无可发布的模型文件，请先完成数据准备。
        </div>
      ) : (
        <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
          <div
            style={{
              width: 240,
              flexShrink: 0,
              display: 'flex',
              flexDirection: 'column',
              gap: 2,
            }}
          >
            {preview?.files.map(f => {
              const published = publishedByPath.get(f.path);
              const same = published === f.content;
              const active = f.path === selected;
              return (
                <button
                  key={f.path}
                  className="da-btn da-btn-sm"
                  onClick={() => setSelected(f.path)}
                  style={{
                    justifyContent: 'flex-start',
                    fontFamily: 'var(--da-mono, monospace)',
                    fontSize: '0.74rem',
                    textAlign: 'left',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                    borderColor: active ? 'var(--da-primary)' : undefined,
                    background: active ? 'var(--da-surface-sunken)' : undefined,
                  }}
                  title={f.path}
                >
                  {!hasPublished ? '＋ ' : same ? '= ' : '± '}
                  {f.path}
                </button>
              );
            })}
          </div>

          <div style={{ flex: 1, minWidth: 0 }}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                marginBottom: 6,
                flexWrap: 'wrap',
              }}
            >
              <span className="da-small" style={{ fontFamily: 'var(--da-mono, monospace)' }}>
                {selected ?? ''}
              </span>
              {hasPublished ? (
                <span className="da-small" style={{ color: 'var(--da-text-3)' }}>
                  +{added} / -{removed}
                </span>
              ) : (
                <span className="da-small" style={{ color: 'var(--da-text-3)' }}>
                  尚无已发布版本，以下为初始版本
                </span>
              )}
              <div style={{ flex: 1 }} />
              <label
                className="da-small"
                style={{ display: 'flex', alignItems: 'center', gap: 4, cursor: 'pointer' }}
              >
                <input
                  type="checkbox"
                  checked={onlyChanges}
                  onChange={e => setOnlyChanges(e.target.checked)}
                />
                仅显示变更
              </label>
            </div>
            <div
              style={{
                maxHeight: 360,
                overflow: 'auto',
                border: '1px solid var(--da-border)',
                borderRadius: 6,
                background: 'var(--da-surface-sunken)',
                padding: '6px 8px',
              }}
            >
              {visibleLines.length === 0 ? (
                <div className="da-small" style={{ color: 'var(--da-text-3)' }}>
                  该文件与已发布版本一致。
                </div>
              ) : (
                visibleLines.map((line, idx) => (
                  <div
                    key={idx}
                    style={{
                      fontFamily: 'var(--da-mono, monospace)',
                      fontSize: '0.74rem',
                      lineHeight: 1.5,
                      whiteSpace: 'pre-wrap',
                      background:
                        line.kind === 'add'
                          ? 'rgba(22, 163, 74, 0.12)'
                          : line.kind === 'del'
                            ? 'rgba(225, 29, 72, 0.10)'
                            : undefined,
                      color: line.kind === 'same' ? 'var(--da-text-3)' : undefined,
                    }}
                  >
                    {(line.kind === 'add' ? '+ ' : line.kind === 'del' ? '- ' : '  ') + line.text}
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      )}
      </details>
    </div>
  );
}
