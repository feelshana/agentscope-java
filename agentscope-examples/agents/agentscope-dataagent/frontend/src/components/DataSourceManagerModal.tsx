import React, { useCallback, useEffect, useState } from 'react';
import {
  createDataSource,
  deleteDataSource,
  ExternalDataSource,
  getDataSourceStatus,
  listDataSources,
  updateDataSource,
} from '../api/datasources';
import DataSourceDetailModal from './DataSourceDetailModal';

const overlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(24, 24, 27, 0.32)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 80,
};

const shellStyle: React.CSSProperties = {
  background: 'var(--da-surface)',
  borderRadius: 12,
  width: 'min(880px, 94vw)',
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
  gap: 12,
};

const bodyStyle: React.CSSProperties = { padding: 20, overflow: 'auto' };

const thStyle: React.CSSProperties = {
  textAlign: 'left',
  padding: '8px 10px',
  borderBottom: '1px solid var(--da-border)',
  fontSize: '0.78rem',
  color: 'var(--da-text-3)',
  fontWeight: 600,
};

const tdStyle: React.CSSProperties = {
  padding: '8px 10px',
  borderBottom: '1px solid var(--da-surface-sunken)',
  fontSize: '0.82rem',
  color: 'var(--da-text)',
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '7px 9px',
  borderRadius: 6,
  border: '1px solid var(--da-border-strong)',
  fontSize: '0.82rem',
  boxSizing: 'border-box',
};

const labelStyle: React.CSSProperties = {
  display: 'block',
  fontSize: '0.75rem',
  fontWeight: 600,
  color: 'var(--da-text-2)',
  marginBottom: 4,
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

const ghostBtn: React.CSSProperties = {
  padding: '7px 12px',
  borderRadius: 8,
  border: '1px solid var(--da-border-strong)',
  background: 'var(--da-surface)',
  color: 'var(--da-text-2)',
  fontSize: '0.82rem',
  cursor: 'pointer',
};

const linkBtn: React.CSSProperties = {
  background: 'transparent',
  border: 'none',
  color: 'var(--da-primary)',
  cursor: 'pointer',
  fontSize: '0.8rem',
  padding: 0,
  marginRight: 10,
};

const dangerBtn: React.CSSProperties = {
  background: 'transparent',
  border: 'none',
  color: 'var(--da-danger)',
  cursor: 'pointer',
  fontSize: '0.8rem',
  padding: 0,
};

interface FormState {
  name: string;
  kind: string;
  jdbcUrl: string;
  username: string;
  password: string;
  sampling: boolean;
}

const emptyForm: FormState = {
  name: '',
  kind: 'mysql',
  jdbcUrl: 'jdbc:mysql://',
  username: '',
  password: '',
  sampling: true,
};

/** TC-style 数据源管理 modal: list + add/edit local DB connections + detail browser. */
export default function DataSourceManagerModal({
  open,
  onClose,
}: {
  open: boolean;
  onClose: () => void;
}) {
  const [items, setItems] = useState<ExternalDataSource[]>([]);
  const [statuses, setStatuses] = useState<Record<string, boolean>>({});
  const [error, setError] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [detail, setDetail] = useState<ExternalDataSource | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const list = await listDataSources();
      setItems(list);
      setError(null);
      const st: Record<string, boolean> = {};
      await Promise.all(
        list.map(async d => {
          try {
            const s = await getDataSourceStatus(d.id);
            st[d.id] = s.connected;
          } catch {
            st[d.id] = false;
          }
        }),
      );
      setStatuses(st);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    if (open) refresh();
  }, [open, refresh]);

  async function handleSave() {
    if (!form.name.trim() || !form.jdbcUrl.trim()) {
      setError('名称与 jdbc 地址必填');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const req = {
        name: form.name,
        kind: form.kind,
        jdbcUrl: form.jdbcUrl,
        username: form.username,
        password: form.password,
        sampling: form.sampling,
      };
      if (editingId) await updateDataSource(editingId, req);
      else await createDataSource(req);
      setFormOpen(false);
      setEditingId(null);
      setForm(emptyForm);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(id: string) {
    if (!window.confirm('删除该数据源？已关联的库表需先解除关联。')) return;
    setBusy(true);
    try {
      await deleteDataSource(id);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  if (!open) return null;

  return (
    <div style={overlayStyle} onClick={onClose}>
      <div style={shellStyle} onClick={e => e.stopPropagation()}>
        <div style={headStyle}>
          <span style={{ fontSize: '1rem', fontWeight: 700 }}>数据源管理</span>
          <span style={{ flex: 1 }} />
          <button className="da-btn" onClick={onClose}>
            关闭
          </button>
        </div>
        <div style={bodyStyle}>
          {error && (
            <div style={{ color: 'var(--da-danger)', fontSize: '0.85rem', marginBottom: 10 }}>{error}</div>
          )}
          <div style={{ marginBottom: 12 }}>
            <button
              className="da-btn da-btn-primary"
              onClick={() => {
                setFormOpen(o => !o);
                setEditingId(null);
                setForm(emptyForm);
              }}
              disabled={busy}
            >
              添加数据源
            </button>
          </div>

          {formOpen && (
            <div
              className="da-card"
              style={{ marginBottom: 14, padding: 16 }}
            >
              <div className="da-form-grid">
                <label className="da-label">数据源名称 *</label>
                <input
                  className="da-input"
                  value={form.name}
                  placeholder="字母/数字/下划线/中文，1-64 字符"
                  onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                />
                <label className="da-label">类型</label>
                <select
                  className="da-input"
                  value={form.kind}
                  onChange={e => setForm(f => ({ ...f, kind: e.target.value }))}
                >
                  <option value="mysql">MySQL</option>
                  <option value="postgresql">PostgreSQL</option>
                </select>
                <label className="da-label">jdbc 地址 *</label>
                <input
                  className="da-input"
                  value={form.jdbcUrl}
                  placeholder="jdbc:mysql://host:port/dbname"
                  onChange={e => setForm(f => ({ ...f, jdbcUrl: e.target.value }))}
                />
                <label className="da-label">用户名</label>
                <input
                  className="da-input"
                  value={form.username}
                  onChange={e => setForm(f => ({ ...f, username: e.target.value }))}
                />
                <label className="da-label">密码</label>
                <input
                  className="da-input"
                  type="password"
                  value={form.password}
                  onChange={e => setForm(f => ({ ...f, password: e.target.value }))}
                />
              </div>
              <details style={{ marginTop: 12 }}>
                <summary className="da-section" style={{ cursor: 'pointer' }}>高级配置</summary>
                <label
                  className="da-label"
                  style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 10 }}
                >
                  <input
                    type="checkbox"
                    checked={form.sampling}
                    onChange={e => setForm(f => ({ ...f, sampling: e.target.checked }))}
                  />
                  数据采样（低基数列自动采样示例值，提升 Agent 效果）
                </label>
              </details>
              <div style={{ display: 'flex', gap: 10, marginTop: 14 }}>
                <button className="da-btn da-btn-primary" onClick={handleSave} disabled={busy}>
                  {editingId ? '完成' : '确认添加'}
                </button>
                <button
                  className="da-btn"
                  onClick={() => {
                    setFormOpen(false);
                    setEditingId(null);
                    setForm(emptyForm);
                  }}
                >
                  取消
                </button>
              </div>
            </div>
          )}

          <table className="da-table">
            <thead>
              <tr>
                <th>数据源名称</th>
                <th>类型</th>
                <th>连通性</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {items.map(d => (
                <tr key={d.id}>
                  <td>{d.name}</td>
                  <td>{d.kind.toUpperCase()}</td>
                  <td>
                    {statuses[d.id] === undefined ? (
                      '检测中…'
                    ) : statuses[d.id] ? (
                      <span style={{ color: '#047857' }}>✓ 数据已连通</span>
                    ) : (
                      <span style={{ color: 'var(--da-danger)' }}>✗ 连接失败</span>
                    )}
                  </td>
                  <td>
                    <button className="da-btn da-btn-sm" onClick={() => setDetail(d)}>
                      查看详情
                    </button>
                    <button
                      className="da-btn da-btn-sm"
                      onClick={() => {
                        setEditingId(d.id);
                        setFormOpen(true);
                        setForm({
                          name: d.name,
                          kind: d.kind,
                          jdbcUrl: d.jdbcUrl,
                          username: d.username ?? '',
                          password: '',
                          sampling: d.sampling,
                        });
                      }}
                    >
                      配置
                    </button>
                    <button className="da-btn da-btn-danger da-btn-sm" onClick={() => handleDelete(d.id)}>
                      删除
                    </button>
                  </td>
                </tr>
              ))}
              {items.length === 0 && (
                <tr>
                  <td style={{ ...tdStyle, color: 'var(--da-text-muted)', textAlign: 'center' }} colSpan={4}>
                    暂无数据源
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
      {detail && <DataSourceDetailModal dataSource={detail} onClose={() => setDetail(null)} />}
    </div>
  );
}
