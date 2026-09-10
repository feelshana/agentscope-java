import React, { useCallback, useEffect, useState } from 'react';
import BackToChatHeader from '../../components/BackToChatHeader';
import {
  createSemanticTerm,
  deleteSemanticTerm,
  listSemanticTerms,
  SemanticTerm,
  updateSemanticTerm,
} from '../../api/semantic';

const panelStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflow: 'auto',
  padding: 24,
};

const cardStyle: React.CSSProperties = {
  background: '#ffffff',
  border: '1px solid #e2e8f0',
  borderRadius: 12,
  padding: 16,
};

const thStyle: React.CSSProperties = {
  textAlign: 'left',
  padding: '8px 10px',
  borderBottom: '1px solid #e2e8f0',
  fontSize: '0.78rem',
  color: '#64748b',
  fontWeight: 600,
};

const tdStyle: React.CSSProperties = {
  padding: '8px 10px',
  borderBottom: '1px solid #f1f5f9',
  fontSize: '0.82rem',
  color: '#0f172a',
  verticalAlign: 'top',
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '6px 8px',
  borderRadius: 6,
  border: '1px solid #cbd5e1',
  fontSize: '0.8rem',
  color: '#0f172a',
  boxSizing: 'border-box',
};

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

const linkBtn: React.CSSProperties = {
  background: 'transparent',
  border: 'none',
  color: '#2563eb',
  cursor: 'pointer',
  fontSize: '0.8rem',
  padding: 0,
  marginRight: 8,
};

const dangerBtn: React.CSSProperties = {
  background: 'transparent',
  border: 'none',
  color: '#dc2626',
  cursor: 'pointer',
  fontSize: '0.8rem',
  padding: 0,
};

interface Draft {
  term: string;
  explanation: string;
  synonyms: string;
  scope: string;
}

const emptyDraft: Draft = { term: '', explanation: '', synonyms: '', scope: '全局生效' };

/** TC-style 语义配置 page: business noun → explanation / synonyms / scope, fed to the agent. */
export default function SemanticConfigPage() {
  const [terms, setTerms] = useState<SemanticTerm[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [draft, setDraft] = useState<Draft>(emptyDraft);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    try {
      setTerms(await listSemanticTerms());
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  async function handleSave() {
    if (!draft.term.trim()) {
      setError('业务名词不能为空');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const req = {
        term: draft.term,
        explanation: draft.explanation,
        synonyms: draft.synonyms,
        scope: draft.scope,
      };
      if (editingId) await updateSemanticTerm(editingId, req);
      else await createSemanticTerm(req);
      setDraft(emptyDraft);
      setAdding(false);
      setEditingId(null);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  function startEdit(t: SemanticTerm) {
    setAdding(true);
    setEditingId(t.id);
    setDraft({
      term: t.term,
      explanation: t.explanation ?? '',
      synonyms: t.synonyms ?? '',
      scope: t.scope ?? '全局生效',
    });
  }

  async function handleDelete(id: string) {
    if (!window.confirm('删除该词条？')) return;
    setBusy(true);
    try {
      await deleteSemanticTerm(id);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <BackToChatHeader title="语义配置" subtitle="业务名词 / 名词解析 / 同义词 / 作用范围" />
      <div style={panelStyle}>
        {error && (
          <div style={{ color: '#b91c1c', fontSize: '0.85rem', marginBottom: 12 }}>{error}</div>
        )}
        <div style={cardStyle}>
          <div style={{ marginBottom: 12 }}>
            <button
              style={{ ...buttonStyle, opacity: adding ? 0.6 : 1 }}
              onClick={() => {
                setAdding(a => !a);
                setEditingId(null);
                setDraft(emptyDraft);
              }}
              disabled={busy}
            >
              添加词条
            </button>
          </div>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th style={thStyle}>业务名词</th>
                <th style={thStyle}>名词解析</th>
                <th style={thStyle}>同义词</th>
                <th style={thStyle}>作用范围</th>
                <th style={thStyle}>操作</th>
              </tr>
            </thead>
            <tbody>
              {adding && (
                <tr>
                  <td style={tdStyle}>
                    <input
                      style={inputStyle}
                      placeholder="请输入业务名词，30字以内"
                      value={draft.term}
                      onChange={e => setDraft(d => ({ ...d, term: e.target.value }))}
                    />
                  </td>
                  <td style={tdStyle}>
                    <input
                      style={inputStyle}
                      placeholder="请输入业务名词解释，100字以内"
                      value={draft.explanation}
                      onChange={e => setDraft(d => ({ ...d, explanation: e.target.value }))}
                    />
                  </td>
                  <td style={tdStyle}>
                    <input
                      style={inputStyle}
                      placeholder="请输入同义词，回车键分隔"
                      value={draft.synonyms}
                      onChange={e => setDraft(d => ({ ...d, synonyms: e.target.value }))}
                    />
                  </td>
                  <td style={tdStyle}>
                    <select
                      style={inputStyle}
                      value={draft.scope}
                      onChange={e => setDraft(d => ({ ...d, scope: e.target.value }))}
                    >
                      <option value="全局生效">全局生效</option>
                      <option value="仅数据集">仅数据集</option>
                    </select>
                  </td>
                  <td style={tdStyle}>
                    <button style={linkBtn} onClick={handleSave} disabled={busy}>
                      保存
                    </button>
                    <button
                      style={dangerBtn}
                      onClick={() => {
                        setAdding(false);
                        setEditingId(null);
                        setDraft(emptyDraft);
                      }}
                    >
                      取消
                    </button>
                  </td>
                </tr>
              )}
              {terms.map(t => (
                <tr key={t.id}>
                  <td style={tdStyle}>{t.term}</td>
                  <td style={tdStyle}>{t.explanation ?? '-'}</td>
                  <td style={tdStyle}>{t.synonyms ?? '-'}</td>
                  <td style={tdStyle}>{t.scope}</td>
                  <td style={tdStyle}>
                    <button style={linkBtn} onClick={() => startEdit(t)}>
                      编辑
                    </button>
                    <button style={dangerBtn} onClick={() => handleDelete(t.id)}>
                      删除
                    </button>
                  </td>
                </tr>
              ))}
              {!adding && terms.length === 0 && (
                <tr>
                  <td style={{ ...tdStyle, color: '#94a3b8', textAlign: 'center' }} colSpan={5}>
                    暂无数据
                  </td>
                </tr>
              )}
            </tbody>
          </table>
          <div style={{ fontSize: '0.75rem', color: '#94a3b8', marginTop: 8 }}>
            共 {terms.length} 条
          </div>
        </div>
      </div>
    </div>
  );
}
