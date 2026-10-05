import { useCallback, useEffect, useMemo, useState } from 'react';
import type { CSSProperties } from 'react';
import type { HitlToolCall } from '../api/chat';
import { previewWorkspaceChange } from '../api/semanticModeling';
import type {
  ModelingOverview,
  ModelingRelation,
  WorkspacePreview,
} from '../api/semanticModeling';
import { diffLines } from '../utils/diff';

const JOIN_LABEL: Record<string, string> = {
  MANY_TO_ONE: '多对一',
  ONE_TO_MANY: '一对多',
  ONE_TO_ONE: '一对一',
};

/** Labels for every tool that can still reach the HITL gate (specs/019 M3, specs/035 write surface). */
const TOOL_LABEL: Record<string, string> = {
  decide_relation: '关系提案',
  decide_relations: '关系批量提案',
  confirm_relation: '确认关系',
  reject_relation: '否决关系',
  add_relation: '新增关系',
  write_file: '写入工程文件',
  patch_file: '修改工程文件',
  create_view: '新建命名视图',
};

function stringValue(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

function relationFor(call: HitlToolCall, overview: ModelingOverview | null): ModelingRelation | null {
  const relationId = stringValue(call.input.relation_id);
  return overview?.relations.find(item => item.id === relationId) ?? null;
}

function datasetLabel(overview: ModelingOverview | null, datasetId: string | undefined): string {
  if (!datasetId) return '未知数据表';
  const dataset = overview?.datasets.find(item => item.id === datasetId);
  return dataset?.name || dataset?.sourceFileName || dataset?.tableName || datasetId;
}

function columnLabel(overview: ModelingOverview | null, datasetId: string | undefined, column: string): string {
  const dataset = overview?.datasets.find(item => item.id === datasetId);
  const found = dataset?.columns.find(item => item.name === column || item.originalName === column);
  // specs/036: prefer the original header only — no description tail on the card.
  return found?.originalName || column;
}

function relationInputColumns(call: HitlToolCall, relation: ModelingRelation | null, side: 'source' | 'target') {
  const key = side === 'source' ? 'source_columns' : 'target_columns';
  const fromInput = stringList(call.input[key]);
  if (fromInput.length > 0) return fromInput;
  const fallback = side === 'source' ? relation?.sourceColumn : relation?.targetColumn;
  return fallback ? [fallback] : [];
}

export default function ModelingHitlCard({
  call,
  groupId,
  overview,
  submitting,
  onDecision,
}: {
  call: HitlToolCall;
  groupId: string;
  overview: ModelingOverview | null;
  submitting: boolean;
  onDecision: (confirmed: boolean, input?: Record<string, unknown>) => void;
}) {
  const relation = relationFor(call, overview);
  const isRelation = call.name === 'decide_relation' && relation != null;
  const isFileWrite = call.name === 'write_file' || call.name === 'patch_file';
  const filePath = stringValue(call.input.path);
  const fileReason = stringValue(call.input.reason);
  const recommendedJoinType = stringValue(call.input.join_type) || relation?.joinType || 'MANY_TO_ONE';
  const [adjusting, setAdjusting] = useState(false);
  const [joinType, setJoinType] = useState(recommendedJoinType);
  const [swap, setSwap] = useState(Boolean(call.input.swap));
  const initialSourceColumns = relationInputColumns(call, relation, 'source');
  const initialTargetColumns = relationInputColumns(call, relation, 'target');
  const [sourceColumns, setSourceColumns] = useState(initialSourceColumns.join(', '));
  const [targetColumns, setTargetColumns] = useState(initialTargetColumns.join(', '));
  const [editingJson, setEditingJson] = useState(false);
  const [jsonInput, setJsonInput] = useState(JSON.stringify(call.input, null, 2));
  const [jsonError, setJsonError] = useState<string | null>(null);

  // File-change card state (specs/019 §5): server-side gated preview + editable draft.
  const [contentText, setContentText] = useState(() => stringValue(call.input.content));
  const [replacementText, setReplacementText] = useState(() => stringValue(call.input.replacement));
  const [replaceAll, setReplaceAll] = useState(() => Boolean(call.input.replace_all));
  const [editingFile, setEditingFile] = useState(false);
  const [fileDirty, setFileDirty] = useState(false);
  const [preview, setPreview] = useState<WorkspacePreview | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);

  const loadPreview = useCallback(
    async (input: Record<string, unknown>) => {
      if (call.name !== 'write_file' && call.name !== 'patch_file') return;
      setPreviewing(true);
      setPreviewError(null);
      try {
        setPreview(await previewWorkspaceChange(groupId, call.name, input));
      } catch (error) {
        setPreview(null);
        setPreviewError(error instanceof Error ? error.message : '预检请求失败');
      } finally {
        setPreviewing(false);
      }
    },
    [call.name, groupId],
  );

  // Refresh the gate verdict + diff on mount; re-checks go through the explicit button.
  useEffect(() => {
    if (isFileWrite) void loadPreview(call.input);
  }, [isFileWrite, loadPreview, call.input]);

  const fileDiff = useMemo(
    () => (preview ? diffLines(preview.oldContent, preview.newContent) : []),
    [preview],
  );
  const addedCount = fileDiff.filter(line => line.type === 'add').length;
  const removedCount = fileDiff.filter(line => line.type === 'del').length;

  const sourceName = datasetLabel(overview, relation?.sourceDatasetId);
  const targetName = datasetLabel(overview, relation?.targetDatasetId);

  function submitRelation(action: 'CONFIRM' | 'ADJUST' | 'REJECT' | 'SKIP') {
    const next: Record<string, unknown> = { ...call.input, action };
    if (action === 'ADJUST') {
      next.join_type = joinType;
      next.swap = swap;
      next.source_columns = sourceColumns.split(',').map(item => item.trim()).filter(Boolean);
      next.target_columns = targetColumns.split(',').map(item => item.trim()).filter(Boolean);
    }
    onDecision(true, next);
  }

  function submitEditedJson() {
    try {
      const parsed = JSON.parse(jsonInput) as unknown;
      if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
        throw new Error('参数必须是 JSON 对象');
      }
      setJsonError(null);
      onDecision(true, parsed as Record<string, unknown>);
    } catch (error) {
      setJsonError(error instanceof Error ? error.message : 'JSON 格式无效');
    }
  }

  /** The confirmed input carries edited draft fields so the same gate re-runs on execute. */
  function fileDraftInput(): Record<string, unknown> {
    const next: Record<string, unknown> = { ...call.input };
    if (call.name === 'write_file') next.content = contentText;
    if (call.name === 'patch_file') {
      next.replacement = replacementText;
      next.replace_all = replaceAll;
    }
    return next;
  }

  function adoptFileChange() {
    onDecision(true, fileDirty ? fileDraftInput() : call.input);
  }

  return (
    <div style={S.card}>
      <div style={S.header}>
        <span style={S.badge}>需要确认</span>
        <strong>{TOOL_LABEL[call.name] ?? call.name}</strong>
      </div>

      {call.name === 'decide_relations' ? (
        <BatchRelationCard call={call} overview={overview} submitting={submitting} onDecision={onDecision} />
      ) : isFileWrite ? (
        <>
          <div style={S.summary}>
            {call.name === 'write_file' ? '新建或覆盖' : '精确替换'}工程文件 <code>{filePath}</code>
            {fileReason ? <>：{fileReason}</> : null}
          </div>
          {previewing && <div style={S.help}>正在预检（YAML 解析 + 工程校验）…</div>}
          {!previewing && previewError && <div style={S.error}>{previewError}</div>}
          {!previewing && !previewError && preview && (preview.ok ? (
            <div style={S.verdictOk}>
              ✓ 预检通过：YAML 解析与工程校验（context validate --strict）均通过
            </div>
          ) : (
            <div style={S.error}>✗ 预检未通过：{preview.error}</div>
          ))}
          {fileDirty && <div style={S.help}>内容已修改，请点「重新预检」刷新校验结论。</div>}
          {fileDiff.length > 0 && (
            <details style={S.details}>
              <summary>
                {addedCount + removedCount > 0
                  ? `变更内容（+${addedCount} / -${removedCount} 行）`
                  : '变更内容（无差异）'}
              </summary>
              <pre style={S.diffBox}>
                {fileDiff.map((line, index) => (
                  <div
                    key={index}
                    style={
                      line.type === 'add' ? S.diffAdd : line.type === 'del' ? S.diffDel : undefined
                    }
                  >
                    {line.type === 'add' ? '+ ' : line.type === 'del' ? '- ' : '  '}
                    {line.text || ' '}
                  </div>
                ))}
              </pre>
            </details>
          )}
          {editingFile && (
            <div style={S.form}>
              {call.name === 'write_file' ? (
                <label style={S.label}>
                  文件全文
                  <textarea
                    style={S.jsonEditor}
                    value={contentText}
                    onChange={event => {
                      setContentText(event.target.value);
                      setFileDirty(true);
                    }}
                  />
                </label>
              ) : (
                <>
                  <div>
                    <div style={S.label}>被替换文本（original，逐字符精确匹配）</div>
                    <pre style={S.pre}>{stringValue(call.input.original)}</pre>
                  </div>
                  <label style={S.label}>
                    替换为（replacement）
                    <textarea
                      style={S.jsonEditor}
                      value={replacementText}
                      onChange={event => {
                        setReplacementText(event.target.value);
                        setFileDirty(true);
                      }}
                    />
                  </label>
                  <label style={S.checkLabel}>
                    <input
                      type="checkbox"
                      checked={replaceAll}
                      onChange={event => {
                        setReplaceAll(event.target.checked);
                        setFileDirty(true);
                      }}
                    />
                    替换所有匹配（replace_all）
                  </label>
                </>
              )}
            </div>
          )}
          <div style={S.actions}>
            <button style={S.primary} disabled={submitting} onClick={adoptFileChange}>
              采用并写入
            </button>
            <button
              style={S.secondary}
              disabled={submitting}
              onClick={() => setEditingFile(value => !value)}
            >
              {editingFile ? '收起编辑' : '编辑内容'}
            </button>
            <button
              style={S.secondary}
              disabled={submitting || previewing}
              onClick={() => {
                setFileDirty(false);
                void loadPreview(fileDraftInput());
              }}
            >
              重新预检
            </button>
            <button style={S.ghost} disabled={submitting} onClick={() => onDecision(false)}>
              跳过
            </button>
          </div>
        </>
      ) : isRelation && relation ? (
        <>
          <div style={S.summary}>
            {`${sourceName}.${initialSourceColumns.join('+') || '?'} → ${targetName}.${initialTargetColumns.join('+') || '?'}（${JOIN_LABEL[recommendedJoinType] ?? '基数待定'}）`}
          </div>
          <details style={S.details}>
            <summary>技术详情</summary>
            <div>基数：{recommendedJoinType}</div>
            <div>
              字段：
              {initialSourceColumns.map((column, index) => (
                <span key={`${column}-${index}`}>
                  {index > 0 ? '；' : ''}
                  {columnLabel(overview, relation.sourceDatasetId, column)} ={' '}
                  {columnLabel(overview, relation.targetDatasetId, initialTargetColumns[index] ?? '?')}
                </span>
              ))}
            </div>
            {relation.description && <div>依据：{relation.description}</div>}
          </details>

          {adjusting && (
            <div style={S.form}>
              <label style={S.label}>
                记录对应方向
                <select style={S.control} value={joinType} onChange={event => setJoinType(event.target.value)}>
                  <option value="MANY_TO_ONE">左侧多条 → 右侧一条</option>
                  <option value="ONE_TO_MANY">左侧一条 → 右侧多条</option>
                  <option value="ONE_TO_ONE">两侧一一对应</option>
                </select>
              </label>
              <label style={S.checkLabel}>
                <input type="checkbox" checked={swap} onChange={event => setSwap(event.target.checked)} />
                交换左右两张表
              </label>
              <div style={S.help}>是否还需日期、地区等条件才能唯一对应？可用逗号补充两侧字段。</div>
              <label style={S.label}>
                左侧对齐字段
                <input style={S.control} value={sourceColumns} onChange={event => setSourceColumns(event.target.value)} />
              </label>
              <label style={S.label}>
                右侧对齐字段
                <input style={S.control} value={targetColumns} onChange={event => setTargetColumns(event.target.value)} />
              </label>
            </div>
          )}

          <div style={S.actions}>
            <button style={S.primary} disabled={submitting} onClick={() => submitRelation('CONFIRM')}>
              采用推荐
            </button>
            <button style={S.secondary} disabled={submitting} onClick={() => setAdjusting(value => !value)}>
              调整关系
            </button>
            <button style={S.danger} disabled={submitting} onClick={() => submitRelation('REJECT')}>
              不建立
            </button>
            <button style={S.ghost} disabled={submitting} onClick={() => submitRelation('SKIP')}>
              暂不处理
            </button>
          </div>
          {adjusting && (
            <button style={S.primaryWide} disabled={submitting} onClick={() => submitRelation('ADJUST')}>
              采用调整后的关系
            </button>
          )}
        </>
      ) : (
        <>
          <div style={S.summary}>建模助手准备执行此变更，请确认摘要和参数。</div>
          <details style={S.details} open={editingJson}>
            <summary>提案参数</summary>
            {editingJson ? (
              <textarea style={S.jsonEditor} value={jsonInput} onChange={event => setJsonInput(event.target.value)} />
            ) : (
              <pre style={S.pre}>{JSON.stringify(call.input, null, 2)}</pre>
            )}
          </details>
          {jsonError && <div style={S.error}>{jsonError}</div>}
          <div style={S.actions}>
            <button style={S.primary} disabled={submitting} onClick={() => onDecision(true, call.input)}>
              采用
            </button>
            <button style={S.secondary} disabled={submitting} onClick={() => setEditingJson(value => !value)}>
              修改要求
            </button>
            <button style={S.ghost} disabled={submitting} onClick={() => onDecision(false)}>
              跳过
            </button>
          </div>
          {editingJson && (
            <button style={S.primaryWide} disabled={submitting} onClick={submitEditedJson}>
              采用修改后的参数
            </button>
          )}
        </>
      )}
      {submitting && <div style={S.help}>正在提交决定并恢复建模会话…</div>}
    </div>
  );
}

/** One rendered row of the batched relation decision card (specs/024): three elements only. */
interface BatchRelationRow {
  relationId: string;
  joinLabel: string;
  sourceLabel: string;
  targetLabel: string;
}

function batchRelationRows(
  call: HitlToolCall,
  overview: ModelingOverview | null,
): { rows: BatchRelationRow[]; error: string | null } {
  let parsed: unknown;
  try {
    parsed = JSON.parse(stringValue(call.input.relations_json));
  } catch {
    return { rows: [], error: 'relations_json 不是合法 JSON' };
  }
  if (!Array.isArray(parsed) || parsed.length === 0) {
    return { rows: [], error: 'relations_json 必须是非空 JSON 数组' };
  }
  const rows: BatchRelationRow[] = [];
  for (const item of parsed) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      return { rows: [], error: 'relations_json 条目格式无效' };
    }
    const rec = item as Record<string, unknown>;
    const relationId = stringValue(rec.relation_id);
    if (!relationId) return { rows: [], error: '条目缺少 relation_id' };
    const relation = overview?.relations.find(r => r.id === relationId) ?? null;
    const joinType = stringValue(rec.join_type) || relation?.joinType || 'MANY_TO_ONE';
    const sourceColumns = stringList(rec.source_columns);
    const targetColumns = stringList(rec.target_columns);
    const sourceColumn = sourceColumns.length > 0 ? sourceColumns.join('+') : relation?.sourceColumn ?? '?';
    const targetColumn = targetColumns.length > 0 ? targetColumns.join('+') : relation?.targetColumn ?? '?';
    rows.push({
      relationId,
      joinLabel: JOIN_LABEL[joinType] ?? joinType,
      sourceLabel: `${datasetLabel(overview, relation?.sourceDatasetId)}.${sourceColumn}`,
      targetLabel: `${datasetLabel(overview, relation?.targetDatasetId)}.${targetColumn}`,
    });
  }
  return { rows, error: null };
}

/** specs/024: one card lists every candidate; the user multi-selects, one submit writes all. */
function BatchRelationCard({
  call,
  overview,
  submitting,
  onDecision,
}: {
  call: HitlToolCall;
  overview: ModelingOverview | null;
  submitting: boolean;
  onDecision: (confirmed: boolean, input?: Record<string, unknown>) => void;
}) {
  const { rows, error } = useMemo(() => batchRelationRows(call, overview), [call, overview]);
  const [checked, setChecked] = useState<boolean[]>([]);
  useEffect(() => {
    setChecked(rows.map(() => true));
  }, [rows]);

  function submitBatch(restAction: 'SKIP' | 'REJECT') {
    let parsed: unknown;
    try {
      parsed = JSON.parse(stringValue(call.input.relations_json));
    } catch {
      onDecision(false);
      return;
    }
    if (!Array.isArray(parsed)) {
      onDecision(false);
      return;
    }
    const next = parsed.map((item, index) => {
      const rec = (item && typeof item === 'object' && !Array.isArray(item)
        ? item
        : {}) as Record<string, unknown>;
      return {
        ...rec,
        action: checked[index]
          ? typeof rec.action === 'string' && rec.action
            ? rec.action
            : 'CONFIRM'
          : restAction,
      };
    });
    onDecision(true, { ...call.input, relations_json: JSON.stringify(next) });
  }

  if (error) {
    return (
      <>
        <div style={S.summary}>关系批量提案参数无效，请跳过并让助手重新整理。</div>
        <div style={S.error}>{error}</div>
        <div style={S.actions}>
          <button style={S.ghost} disabled={submitting} onClick={() => onDecision(false)}>
            跳过
          </button>
        </div>
        {submitting && <div style={S.help}>正在提交决定并恢复建模会话…</div>}
      </>
    );
  }

  const checkedCount = rows.filter((_, index) => checked[index]).length;
  return (
    <>
      <div style={S.summary}>共 {rows.length} 条候选关系，勾选要建立的关系后一次验证写入。</div>
      <div style={S.form}>
        {rows.map((row, index) => (
          <label key={row.relationId} style={S.checkLabel}>
            <input
              type="checkbox"
              checked={checked[index] ?? true}
              onChange={event =>
                setChecked(prev => {
                  const next = [...prev];
                  next[index] = event.target.checked;
                  return next;
                })
              }
            />
            <span>
              {row.sourceLabel} → {row.targetLabel}（{row.joinLabel}）
            </span>
          </label>
        ))}
      </div>
      <div style={S.actions}>
        <button
          style={S.secondary}
          disabled={submitting || checkedCount === rows.length}
          onClick={() => setChecked(rows.map(() => true))}
        >
          全选
        </button>
        <button
          style={S.secondary}
          disabled={submitting || checkedCount === 0}
          onClick={() => setChecked(rows.map(() => false))}
        >
          全不选
        </button>
      </div>
      <div style={S.actions}>
        <button
          style={S.primary}
          disabled={submitting || checkedCount === 0}
          onClick={() => submitBatch('SKIP')}
        >
          采用勾选项（其余暂不处理）
        </button>
        <button
          style={S.secondary}
          disabled={submitting || checkedCount === 0}
          onClick={() => submitBatch('REJECT')}
        >
          采用勾选项（其余全部否决）
        </button>
        <button style={S.ghost} disabled={submitting} onClick={() => onDecision(false)}>
          跳过
        </button>
      </div>
      {submitting && <div style={S.help}>正在提交决定并恢复建模会话…</div>}
    </>
  );
}

const S: Record<string, CSSProperties> = {
  card: {
    marginTop: 8,
    padding: 12,
    border: '1px solid color-mix(in srgb, var(--da-primary) 40%, var(--da-border))',
    borderRadius: 10,
    background: 'color-mix(in srgb, var(--da-primary) 4%, var(--da-surface, #fff))',
  },
  header: { display: 'flex', alignItems: 'center', gap: 8, fontSize: 13 },
  badge: {
    padding: '2px 7px',
    borderRadius: 999,
    background: 'var(--da-primary)',
    color: '#fff',
    fontSize: 10,
  },
  summary: { marginTop: 8, lineHeight: 1.6, color: 'var(--da-text)' },
  details: { marginTop: 8, fontSize: 11.5, color: 'var(--da-text-muted)', lineHeight: 1.7 },
  form: { display: 'flex', flexDirection: 'column', gap: 8, marginTop: 10 },
  label: { display: 'flex', flexDirection: 'column', gap: 4, fontSize: 11.5 },
  checkLabel: { display: 'flex', alignItems: 'center', gap: 6, fontSize: 11.5 },
  control: {
    width: '100%',
    padding: '6px 8px',
    border: '1px solid var(--da-border)',
    borderRadius: 7,
    background: 'var(--da-surface, #fff)',
    color: 'var(--da-text)',
    boxSizing: 'border-box',
  },
  help: { marginTop: 6, fontSize: 11, color: 'var(--da-text-muted)', lineHeight: 1.5 },
  actions: { display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 10 },
  primary: {
    border: 'none',
    borderRadius: 7,
    padding: '6px 10px',
    background: 'var(--da-primary)',
    color: '#fff',
    cursor: 'pointer',
  },
  secondary: {
    border: '1px solid var(--da-primary)',
    borderRadius: 7,
    padding: '6px 10px',
    background: 'var(--da-surface, #fff)',
    color: 'var(--da-primary)',
    cursor: 'pointer',
  },
  danger: {
    border: '1px solid var(--da-danger)',
    borderRadius: 7,
    padding: '6px 10px',
    background: 'var(--da-surface, #fff)',
    color: 'var(--da-danger)',
    cursor: 'pointer',
  },
  ghost: {
    border: '1px solid var(--da-border)',
    borderRadius: 7,
    padding: '6px 10px',
    background: 'transparent',
    color: 'var(--da-text-muted)',
    cursor: 'pointer',
  },
  primaryWide: {
    width: '100%',
    marginTop: 8,
    border: 'none',
    borderRadius: 7,
    padding: '7px 10px',
    background: 'var(--da-primary)',
    color: '#fff',
    cursor: 'pointer',
  },
  jsonEditor: {
    width: '100%',
    minHeight: 150,
    boxSizing: 'border-box',
    fontFamily: 'monospace',
    fontSize: 11,
  },
  pre: { maxHeight: 180, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word' },
  error: { marginTop: 6, color: 'var(--da-danger)', fontSize: 11 },
  verdictOk: { marginTop: 6, color: '#15803d', fontSize: 11 },
  diffBox: {
    margin: '6px 0 0',
    padding: 8,
    background: '#f8fafc',
    border: '1px solid var(--da-border)',
    borderRadius: 7,
    maxHeight: 260,
    overflow: 'auto',
    fontFamily: 'monospace',
    fontSize: 11,
    lineHeight: 1.5,
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
  },
  diffAdd: { background: '#e6ffec', color: '#1a7f37' },
  diffDel: { background: '#ffebe9', color: '#cf222e' },
};
