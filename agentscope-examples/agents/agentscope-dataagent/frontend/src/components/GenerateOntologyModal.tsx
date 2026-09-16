import React, { useMemo, useState } from 'react';
import { Dataset } from '../api/datasets';
import { autoGenerateSemanticModel, uploadInstructions, uploadSemanticModel } from '../api/semantic';
import Icon from './Icon';

const overlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(24, 24, 27, 0.32)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 85,
};

const shellStyle: React.CSSProperties = {
  background: 'var(--da-surface)',
  borderRadius: 12,
  width: 'min(680px, 94vw)',
  maxHeight: '86vh',
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
};

const headStyle: React.CSSProperties = {
  padding: '14px 20px',
  borderBottom: '1px solid var(--da-border)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
};

const bodyStyle: React.CSSProperties = {
  padding: 16,
  overflow: 'auto',
  flex: 1,
  minHeight: 0,
};

const footStyle: React.CSSProperties = {
  padding: '12px 20px',
  borderTop: '1px solid var(--da-border)',
  display: 'flex',
  gap: 10,
  alignItems: 'center',
  justifyContent: 'flex-end',
};

const primaryBtn: React.CSSProperties = {
  padding: '8px 16px',
  borderRadius: 8,
  border: '1px solid var(--da-primary)',
  background: 'var(--da-primary)',
  color: 'var(--da-surface)',
  fontSize: '0.85rem',
  fontWeight: 600,
  cursor: 'pointer',
};

const disabledBtn: React.CSSProperties = {
  ...primaryBtn,
  opacity: 0.5,
  cursor: 'not-allowed',
};

const ghostBtn: React.CSSProperties = {
  padding: '7px 12px',
  borderRadius: 8,
  border: '1px solid var(--da-border-strong)',
  background: 'var(--da-surface)',
  color: 'var(--da-text-2)',
  fontSize: '0.82rem',
  cursor: 'pointer',
};

const segBtn = (active: boolean): React.CSSProperties => ({
  padding: '7px 14px',
  borderRadius: 8,
  border: active ? '1px solid var(--da-primary)' : '1px solid var(--da-border-strong)',
  background: active ? 'var(--da-primary-subtle)' : 'var(--da-surface)',
  color: active ? 'var(--da-primary-hover)' : 'var(--da-text-2)',
  fontSize: '0.82rem',
  fontWeight: active ? 600 : 400,
  cursor: 'pointer',
});

const thStyle: React.CSSProperties = {
  textAlign: 'left',
  padding: '6px 8px',
  borderBottom: '1px solid var(--da-border)',
  fontSize: '0.75rem',
  color: 'var(--da-text-3)',
  fontWeight: 600,
};

const tdStyle: React.CSSProperties = {
  padding: '6px 8px',
  borderBottom: '1px solid var(--da-surface-sunken)',
  fontSize: '0.8rem',
  color: 'var(--da-text)',
};

type Mode = 'tables' | 'files';

/**
 * 本体图谱生成弹窗：两种生成方式——按数据表自动建模，或上传本体文件（mdl.json + instructions.md）。
 * 生成动作只产出语义模型（SemanticModel），旧 YAML 本体不再提供新生成入口。
 */
export default function GenerateOntologyModal({
  groupId,
  datasets,
  onClose,
  onGenerated,
}: {
  groupId: string;
  datasets: Dataset[];
  onClose: () => void;
  onGenerated: () => void;
}) {
  const [mode, setMode] = useState<Mode>('tables');
  const allNames = useMemo(() => datasets.map(d => d.name), [datasets]);
  const [selected, setSelected] = useState<string[]>(allNames);
  const [mdlFile, setMdlFile] = useState<File | null>(null);
  const [instructionsFile, setInstructionsFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const toggle = (name: string) =>
    setSelected(prev => (prev.includes(name) ? prev.filter(n => n !== name) : [...prev, name]));

  const canConfirm = mode === 'tables' ? selected.length > 0 : mdlFile !== null;

  async function confirm() {
    setBusy(true);
    setError(null);
    try {
      if (mode === 'tables') {
        await autoGenerateSemanticModel(groupId, selected);
      } else {
        if (!mdlFile) return;
        await uploadSemanticModel(mdlFile, groupId);
        if (instructionsFile) await uploadInstructions(instructionsFile, groupId);
      }
      onGenerated();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={overlayStyle} onClick={onClose}>
      <div style={shellStyle} onClick={e => e.stopPropagation()}>
        <div style={headStyle}>
          <div style={{ fontSize: '0.95rem', fontWeight: 600, color: 'var(--da-text)' }}>
            生成本体图谱
          </div>
          <button className="da-btn da-btn-sm" onClick={onClose} aria-label="关闭">
            ×
          </button>
        </div>

        <div style={{ ...bodyStyle, display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ display: 'flex', gap: 8 }}>
            <button style={segBtn(mode === 'tables')} onClick={() => setMode('tables')}>
              按数据表生成
            </button>
            <button style={segBtn(mode === 'files')} onClick={() => setMode('files')}>
              按本体文件生成
            </button>
          </div>

          {mode === 'tables' ? (
            <>
              <div className="da-small">
                从知识库已有数据表推断逻辑表、关系与 Cube 指标；可勾选子集，默认全选。
              </div>
              <div style={{ border: '1px solid var(--da-border)', borderRadius: 8, maxHeight: 300, overflow: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead>
                    <tr>
                      <th style={thStyle}>
                        <input
                          type="checkbox"
                          checked={selected.length === allNames.length && allNames.length > 0}
                          onChange={e => setSelected(e.target.checked ? allNames : [])}
                          aria-label="全选"
                        />
                      </th>
                      <th style={thStyle}>数据集</th>
                      <th style={thStyle}>物理表</th>
                      <th style={thStyle}>行数</th>
                      <th style={thStyle}>列数</th>
                    </tr>
                  </thead>
                  <tbody>
                    {datasets.map(d => (
                      <tr key={d.id}>
                        <td style={tdStyle}>
                          <input
                            type="checkbox"
                            checked={selected.includes(d.name)}
                            onChange={() => toggle(d.name)}
                            aria-label={d.name}
                          />
                        </td>
                        <td style={tdStyle}>{d.name}</td>
                        <td style={{ ...tdStyle, color: 'var(--da-text-3)' }}>{d.tableName}</td>
                        <td style={tdStyle}>{d.rowCount}</td>
                        <td style={tdStyle}>{d.columns.length}</td>
                      </tr>
                    ))}
                    {datasets.length === 0 && (
                      <tr>
                        <td colSpan={5} style={{ ...tdStyle, color: 'var(--da-text-3)' }}>
                          知识库暂无数据表，请先上传数据或改用本体文件生成。
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </>
          ) : (
            <>
              <div className="da-small">
                上传语义模型文件 mdl.json（必选）与业务规则文件 instructions.md（可选），按文件内容生成本体图谱。
              </div>
              <label style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                <span style={{ fontSize: '0.82rem', color: 'var(--da-text-2)' }}>
                  语义模型 mdl.json *
                </span>
                <input
                  type="file"
                  accept=".json"
                  onChange={e => setMdlFile(e.target.files?.[0] ?? null)}
                />
                {mdlFile && <span className="da-small">{mdlFile.name}</span>}
              </label>
              <label style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                <span style={{ fontSize: '0.82rem', color: 'var(--da-text-2)' }}>
                  业务规则 instructions.md（可选）
                </span>
                <input
                  type="file"
                  accept=".md,.txt"
                  onChange={e => setInstructionsFile(e.target.files?.[0] ?? null)}
                />
                {instructionsFile && <span className="da-small">{instructionsFile.name}</span>}
              </label>
            </>
          )}

          {error && (
            <div
              role="alert"
              style={{
                background: '#fef2f2',
                border: '1px solid #fecaca',
                borderRadius: 8,
                padding: '8px 12px',
                color: '#b91c1c',
                fontSize: '0.8rem',
              }}
            >
              {error.includes('未找到匹配的数据表')
                ? '未找到匹配的数据表：请先上传数据文件，或改用本体文件生成。'
                : error}
            </div>
          )}
        </div>

        <div style={footStyle}>
          <button style={ghostBtn} onClick={onClose} disabled={busy}>
            取消
          </button>
          <button style={canConfirm && !busy ? primaryBtn : disabledBtn} onClick={confirm} disabled={!canConfirm || busy}>
            {busy ? '生成中…' : <><Icon name="refresh" size="sm" /> 生成</>}
          </button>
        </div>
      </div>
    </div>
  );
}
