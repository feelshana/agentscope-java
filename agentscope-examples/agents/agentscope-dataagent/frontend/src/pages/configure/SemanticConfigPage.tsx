import React, { useCallback, useEffect, useMemo, useState } from 'react';
import BackToChatHeader from '../../components/BackToChatHeader';
import Icon from '../../components/Icon';
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

const linkBtn: React.CSSProperties = {
  background: 'transparent',
  border: 'none',
  color: 'var(--da-primary)',
  cursor: 'pointer',
  fontSize: 13,
  padding: 0,
};

const linkBtnDisabled: React.CSSProperties = {
  ...linkBtn,
  color: 'var(--da-text-muted)',
  opacity: 0.55,
  cursor: 'not-allowed',
};

const pagerBtn: React.CSSProperties = {
  width: 26,
  height: 26,
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  background: 'var(--da-surface)',
  border: '1px solid var(--da-border)',
  borderRadius: 6,
  color: 'var(--da-text-3)',
  cursor: 'pointer',
  padding: 0,
  flexShrink: 0,
};

const pagerBtnDisabled: React.CSSProperties = {
  ...pagerBtn,
  opacity: 0.45,
  cursor: 'not-allowed',
};

const synTagBox: React.CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  alignItems: 'center',
  gap: 4,
  minHeight: 34,
  padding: '3px 6px',
  width: '100%',
  boxSizing: 'border-box',
  background: 'var(--da-surface)',
  border: '1px solid var(--da-border-strong)',
  borderRadius: 'var(--da-radius-md)',
};

interface Draft {
  term: string;
  explanation: string;
  scope: string;
}

const emptyDraft: Draft = { term: '', explanation: '', scope: '全局生效' };

const splitSyn = (s: string | null): string[] =>
  (s ?? '').split(/[,，、]/).map(x => x.trim()).filter(Boolean);

function Chevron({ left = false, double = false }: { left?: boolean; double?: boolean }) {
  const one = (
    <span style={{ display: 'inline-flex', transform: left ? 'rotate(180deg)' : undefined }}>
      <Icon name="chevron" size="sm" />
    </span>
  );
  if (!double) return one;
  return (
    <span style={{ display: 'inline-flex' }}>
      <span style={{ marginRight: -7 }}>{one}</span>
      {one}
    </span>
  );
}

/** TC-style 语义配置 page: business noun → explanation / synonyms / scope, fed to the agent. */
export default function SemanticConfigPage() {
  const [terms, setTerms] = useState<SemanticTerm[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [draft, setDraft] = useState<Draft>(emptyDraft);
  const [synList, setSynList] = useState<string[]>([]);
  const [synText, setSynText] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [pageSize, setPageSize] = useState(10);
  const [page, setPage] = useState(1);

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

  const totalPages = Math.max(1, Math.ceil(terms.length / pageSize));
  const curPage = Math.min(page, totalPages);
  const rows = useMemo(
    () => terms.slice((curPage - 1) * pageSize, curPage * pageSize),
    [terms, curPage, pageSize],
  );

  function commitSyn() {
    const v = synText.trim().replace(/[,，、]+$/, '');
    if (v && !synList.includes(v)) setSynList(l => [...l, v]);
    setSynText('');
  }

  function resetDraft() {
    setAdding(false);
    setEditingId(null);
    setDraft(emptyDraft);
    setSynList([]);
    setSynText('');
  }

  async function handleSave() {
    if (!draft.term.trim()) return;
    const pending = synText.trim().replace(/[,，、]+$/, '');
    const finalSyns = pending && !synList.includes(pending) ? [...synList, pending] : synList;
    setSynList(finalSyns);
    setSynText('');
    setBusy(true);
    setError(null);
    try {
      const req = {
        term: draft.term.trim(),
        explanation: draft.explanation,
        synonyms: finalSyns.join(','),
        scope: draft.scope,
      };
      if (editingId) await updateSemanticTerm(editingId, req);
      else await createSemanticTerm(req);
      resetDraft();
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
      scope: t.scope ?? '全局生效',
    });
    setSynList(splitSyn(t.synonyms));
    setSynText('');
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

  const canSave = draft.term.trim().length > 0 && !busy;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <BackToChatHeader title="语义配置" subtitle="业务名词 / 名词解析 / 同义词 / 作用范围" />
      <div style={panelStyle}>
        {error && (
          <div
            style={{
              color: 'var(--da-danger)',
              background: 'rgba(225, 29, 72, 0.06)',
              border: '1px solid rgba(225, 29, 72, 0.25)',
              borderRadius: 8,
              padding: '8px 12px',
              fontSize: '0.85rem',
              marginBottom: 12,
            }}
          >
            {error}
          </div>
        )}
        <div className="da-panel" style={{ padding: '16px 20px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14 }}>
            <button
              className="da-btn da-btn-primary"
              disabled={adding || busy}
              onClick={() => {
                setAdding(true);
                setEditingId(null);
                setDraft(emptyDraft);
                setSynList([]);
                setSynText('');
              }}
            >
              添加词条
            </button>
            <span style={{ flex: 1 }} />
            <button
              className="da-btn da-btn-ghost da-btn-sm"
              title="刷新"
              onClick={refresh}
              disabled={busy}
            >
              <Icon name="refresh" size="sm" />
            </button>
          </div>
          <table className="da-table">
            <thead>
              <tr>
                <th style={{ width: '18%' }}>业务名词</th>
                <th style={{ width: '30%' }}>名词解析</th>
                <th style={{ width: '20%' }}>同义词</th>
                <th style={{ width: '16%' }}>作用范围</th>
                <th style={{ width: '16%' }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {adding && (
                <tr>
                  <td>
                    <input
                      className="da-input"
                      placeholder="请输入业务名词，30字以内"
                      maxLength={30}
                      value={draft.term}
                      onChange={e => setDraft(d => ({ ...d, term: e.target.value }))}
                    />
                  </td>
                  <td>
                    <input
                      className="da-input"
                      placeholder="请输入业务名词解释，100字以内"
                      maxLength={100}
                      value={draft.explanation}
                      onChange={e => setDraft(d => ({ ...d, explanation: e.target.value }))}
                    />
                  </td>
                  <td>
                    <div className="da-input" style={synTagBox}>
                      {synList.map((s, i) => (
                        <span key={`${s}-${i}`} className="da-badge">
                          {s}
                          <button
                            type="button"
                            title="移除"
                            onClick={() => setSynList(l => l.filter((_, j) => j !== i))}
                            style={{
                              border: 'none',
                              background: 'transparent',
                              cursor: 'pointer',
                              padding: 0,
                              display: 'inline-flex',
                              color: 'var(--da-text-3)',
                            }}
                          >
                            <Icon name="close" size="sm" />
                          </button>
                        </span>
                      ))}
                      <input
                        placeholder={synList.length ? '' : '请输入同义词，回车键分隔'}
                        value={synText}
                        onChange={e => setSynText(e.target.value)}
                        onBlur={commitSyn}
                        onKeyDown={e => {
                          if (e.key === 'Enter' || e.key === ',') {
                            e.preventDefault();
                            commitSyn();
                          } else if (e.key === 'Backspace' && !synText && synList.length) {
                            setSynList(l => l.slice(0, -1));
                          }
                        }}
                        style={{
                          flex: 1,
                          minWidth: 110,
                          border: 'none',
                          outline: 'none',
                          background: 'transparent',
                          font: 'inherit',
                          fontSize: 13,
                          color: 'var(--da-text)',
                          padding: '3px 2px',
                        }}
                      />
                    </div>
                  </td>
                  <td>
                    <select
                      className="da-input"
                      value={draft.scope}
                      onChange={e => setDraft(d => ({ ...d, scope: e.target.value }))}
                    >
                      <option value="全局生效">全局生效</option>
                      <option value="仅数据集">仅数据集</option>
                    </select>
                  </td>
                  <td>
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 14 }}>
                      <button
                        style={canSave ? linkBtn : linkBtnDisabled}
                        disabled={!canSave}
                        onClick={handleSave}
                      >
                        保存
                      </button>
                      <button style={linkBtn} onClick={resetDraft}>
                        取消
                      </button>
                    </span>
                  </td>
                </tr>
              )}
              {rows.map(t => (
                <tr key={t.id}>
                  <td style={{ fontWeight: 500, color: 'var(--da-text)' }}>{t.term}</td>
                  <td>{t.explanation || '-'}</td>
                  <td>
                    <span style={{ display: 'inline-flex', flexWrap: 'wrap', gap: 4 }}>
                      {splitSyn(t.synonyms).length
                        ? splitSyn(t.synonyms).map(s => (
                            <span key={s} className="da-badge">{s}</span>
                          ))
                        : '-'}
                    </span>
                  </td>
                  <td>{t.scope}</td>
                  <td>
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 14 }}>
                      <button style={linkBtn} onClick={() => startEdit(t)}>
                        编辑
                      </button>
                      <button style={linkBtn} onClick={() => handleDelete(t.id)}>
                        删除
                      </button>
                    </span>
                  </td>
                </tr>
              ))}
              {!adding && terms.length === 0 && (
                <tr>
                  <td colSpan={5} style={{ textAlign: 'center', padding: '28px 0' }}>
                    <span
                      style={{
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: 6,
                        color: 'var(--da-text-muted)',
                        fontSize: 13,
                      }}
                    >
                      <Icon name="file" size="sm" /> 暂无数据
                    </span>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '12px 2px 2px' }}>
            <span className="da-small">共 {terms.length} 条</span>
            <span style={{ flex: 1 }} />
            <select
              className="da-input"
              style={{ width: 'auto', padding: '2px 6px', fontSize: 12 }}
              value={pageSize}
              onChange={e => {
                setPageSize(Number(e.target.value));
                setPage(1);
              }}
            >
              <option value={10}>10</option>
              <option value={20}>20</option>
              <option value={50}>50</option>
            </select>
            <span className="da-small">条 / 页</span>
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, marginLeft: 8 }}>
              <button
                style={curPage === 1 ? pagerBtnDisabled : pagerBtn}
                disabled={curPage === 1}
                title="首页"
                onClick={() => setPage(1)}
              >
                <Chevron left double />
              </button>
              <button
                style={curPage === 1 ? pagerBtnDisabled : pagerBtn}
                disabled={curPage === 1}
                title="上一页"
                onClick={() => setPage(p => Math.max(1, p - 1))}
              >
                <Chevron left />
              </button>
              <input
                className="da-input"
                style={{ width: 44, padding: '2px 4px', fontSize: 12, textAlign: 'center' }}
                value={curPage}
                onChange={e => {
                  const n = parseInt(e.target.value, 10);
                  if (!Number.isNaN(n)) setPage(Math.min(Math.max(1, n), totalPages));
                }}
              />
              <span className="da-small">/ {totalPages} 页</span>
              <button
                style={curPage === totalPages ? pagerBtnDisabled : pagerBtn}
                disabled={curPage === totalPages}
                title="下一页"
                onClick={() => setPage(p => Math.min(totalPages, p + 1))}
              >
                <Chevron />
              </button>
              <button
                style={curPage === totalPages ? pagerBtnDisabled : pagerBtn}
                disabled={curPage === totalPages}
                title="末页"
                onClick={() => setPage(totalPages)}
              >
                <Chevron double />
              </button>
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}
