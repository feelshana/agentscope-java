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
  borderRadius: 16,
  width: 'min(1300px, 96vw)',
  maxHeight: '90vh',
  minHeight: 600,
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  boxShadow: 'var(--da-shadow-pop)',
  border: '1px solid var(--da-border)',
};

const headStyle: React.CSSProperties = {
  padding: '18px 24px',
  borderBottom: '1px solid var(--da-border)',
  display: 'flex',
  alignItems: 'center',
  gap: 16,
};

const bodyStyle: React.CSSProperties = {
  padding: '20px 24px',
  overflow: 'auto',
  flex: 1,
  minHeight: 0,
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '9px 12px',
  borderRadius: 8,
  border: '1px solid var(--da-border-strong)',
  fontSize: '0.85rem',
  boxSizing: 'border-box',
};

const labelStyle: React.CSSProperties = {
  display: 'block',
  fontSize: '0.8rem',
  fontWeight: 600,
  color: 'var(--da-text-2)',
  marginBottom: 6,
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
          <span style={{ fontSize: '1.1rem', fontWeight: 700, color: 'var(--da-text)' }}>
            数据源管理
          </span>
          <span style={{ flex: 1 }} />
          <button className="da-btn" onClick={onClose}>
            关闭
          </button>
        </div>
        <div style={bodyStyle}>
          {error && (
            <div
              style={{
                color: 'var(--da-danger)',
                fontSize: '0.85rem',
                marginBottom: 12,
                padding: '10px 14px',
                background: 'rgba(225, 29, 72, 0.06)',
                borderRadius: 8,
                border: '1px solid rgba(225, 29, 72, 0.18)',
              }}
            >
              {error}
            </div>
          )}

          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              marginBottom: 16,
            }}
          >
            <div style={{ fontSize: '0.85rem', color: 'var(--da-text-3)' }}>
              共 {items.length} 个数据源
            </div>
            <button
              className="da-btn da-btn-primary"
              onClick={() => {
                setFormOpen(o => !o);
                setEditingId(null);
                setForm(emptyForm);
              }}
              disabled={busy}
              style={{ padding: '8px 18px', fontSize: '0.85rem' }}
            >
              + 添加数据源
            </button>
          </div>

          {formOpen && (
            <div
              className="da-card"
              style={{ marginBottom: 20, padding: 20 }}
            >
              <div
                style={{
                  fontSize: '0.9rem',
                  fontWeight: 600,
                  color: 'var(--da-text)',
                  marginBottom: 16,
                }}
              >
                {editingId ? '编辑数据源' : '添加新数据源'}
              </div>
              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: '1fr 1fr',
                  gap: '16px 20px',
                }}
              >
                <div>
                  <label style={labelStyle}>数据源名称 *</label>
                  <input
                    className="da-input"
                    value={form.name}
                    placeholder="字母/数字/下划线/中文，1-64 字符"
                    onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                    style={{ padding: '10px 12px' }}
                  />
                </div>
                <div>
                  <label style={labelStyle}>类型</label>
                  <select
                    className="da-input"
                    value={form.kind}
                    onChange={e => setForm(f => ({ ...f, kind: e.target.value }))}
                    style={{ padding: '10px 12px' }}
                  >
                    <option value="mysql">MySQL</option>
                    <option value="postgresql">PostgreSQL</option>
                  </select>
                </div>
                <div style={{ gridColumn: '1 / -1' }}>
                  <label style={labelStyle}>JDBC 地址 *</label>
                  <input
                    className="da-input"
                    value={form.jdbcUrl}
                    placeholder="jdbc:mysql://host:port/dbname"
                    onChange={e => setForm(f => ({ ...f, jdbcUrl: e.target.value }))}
                    style={{ padding: '10px 12px' }}
                  />
                </div>
                <div>
                  <label style={labelStyle}>用户名</label>
                  <input
                    className="da-input"
                    value={form.username}
                    placeholder="数据库用户名"
                    onChange={e => setForm(f => ({ ...f, username: e.target.value }))}
                    style={{ padding: '10px 12px' }}
                  />
                </div>
                <div>
                  <label style={labelStyle}>密码</label>
                  <input
                    className="da-input"
                    type="password"
                    value={form.password}
                    placeholder="数据库密码"
                    onChange={e => setForm(f => ({ ...f, password: e.target.value }))}
                    style={{ padding: '10px 12px' }}
                  />
                </div>
              </div>
              <div style={{ marginTop: 16 }}>
                <label
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    fontSize: '0.85rem',
                    color: 'var(--da-text-2)',
                    cursor: 'pointer',
                  }}
                >
                  <input
                    type="checkbox"
                    checked={form.sampling}
                    onChange={e => setForm(f => ({ ...f, sampling: e.target.checked }))}
                    style={{ width: 16, height: 16 }}
                  />
                  数据采样（低基数列自动采样示例值，提升 Agent 效果）
                </label>
              </div>
              <div style={{ display: 'flex', gap: 10, marginTop: 20 }}>
                <button className="da-btn da-btn-primary" onClick={handleSave} disabled={busy}>
                  {editingId ? '保存' : '确认添加'}
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

          <div
            style={{
              borderRadius: 10,
              border: '1px solid var(--da-border)',
              overflow: 'hidden',
              background: 'var(--da-surface)',
            }}
          >
            <table className="da-table">
              <thead>
                <tr>
                  <th style={{ padding: '12px 16px', fontSize: '0.8rem' }}>数据源名称</th>
                  <th style={{ padding: '12px 16px', fontSize: '0.8rem' }}>类型</th>
                  <th style={{ padding: '12px 16px', fontSize: '0.8rem' }}>连通性</th>
                  <th style={{ padding: '12px 16px', fontSize: '0.8rem', textAlign: 'right' }}>
                    操作
                  </th>
                </tr>
              </thead>
              <tbody>
                {items.map(d => (
                  <tr key={d.id}>
                    <td
                      style={{
                        padding: '14px 16px',
                        fontWeight: 500,
                        color: 'var(--da-text)',
                      }}
                    >
                      {d.name}
                    </td>
                    <td style={{ padding: '14px 16px' }}>
                      <span
                        style={{
                          display: 'inline-block',
                          padding: '3px 10px',
                          borderRadius: 6,
                          fontSize: '0.75rem',
                          fontWeight: 600,
                          background:
                            d.kind === 'mysql'
                              ? 'rgba(37, 99, 235, 0.1)'
                              : 'rgba(124, 58, 237, 0.1)',
                          color: d.kind === 'mysql' ? '#2563eb' : '#7c3aed',
                        }}
                      >
                        {d.kind.toUpperCase()}
                      </span>
                    </td>
                    <td style={{ padding: '14px 16px' }}>
                      {statuses[d.id] === undefined ? (
                        <span style={{ color: 'var(--da-text-muted)', fontSize: '0.85rem' }}>
                          检测中…
                        </span>
                      ) : statuses[d.id] ? (
                        <span
                          style={{
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 6,
                            padding: '4px 12px',
                            borderRadius: 6,
                            fontSize: '0.8rem',
                            fontWeight: 500,
                            background: 'rgba(22, 163, 74, 0.1)',
                            color: '#16a34a',
                          }}
                        >
                          <span
                            style={{
                              width: 6,
                              height: 6,
                              borderRadius: '50%',
                              background: '#16a34a',
                            }}
                          />
                          已连通
                        </span>
                      ) : (
                        <span
                          style={{
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 6,
                            padding: '4px 12px',
                            borderRadius: 6,
                            fontSize: '0.8rem',
                            fontWeight: 500,
                            background: 'rgba(225, 29, 72, 0.1)',
                            color: '#e11d48',
                          }}
                        >
                          <span
                            style={{
                              width: 6,
                              height: 6,
                              borderRadius: '50%',
                              background: '#e11d48',
                            }}
                          />
                          连接失败
                        </span>
                      )}
                    </td>
                    <td
                      style={{
                        padding: '14px 16px',
                        textAlign: 'right',
                        display: 'flex',
                        gap: 8,
                        justifyContent: 'flex-end',
                      }}
                    >
                      <button
                        className="da-btn da-btn-sm"
                        onClick={() => setDetail(d)}
                        style={{ padding: '5px 12px' }}
                      >
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
                        style={{ padding: '5px 12px' }}
                      >
                        配置
                      </button>
                      <button
                        className="da-btn da-btn-danger da-btn-sm"
                        onClick={() => handleDelete(d.id)}
                        style={{ padding: '5px 12px' }}
                      >
                        删除
                      </button>
                    </td>
                  </tr>
                ))}
                {items.length === 0 && (
                  <tr>
                    <td
                      style={{
                        padding: '48px 16px',
                        color: 'var(--da-text-muted)',
                        textAlign: 'center',
                        fontSize: '0.9rem',
                      }}
                      colSpan={4}
                    >
                      暂无数据源，点击上方按钮添加
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>
      {detail && <DataSourceDetailModal dataSource={detail} onClose={() => setDetail(null)} />}
    </div>
  );
}
