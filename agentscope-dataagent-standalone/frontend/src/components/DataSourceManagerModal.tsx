import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  createDataSource,
  DataSourceRequest,
  DataSourceStatus,
  deleteDataSource,
  ExternalDataSource,
  getDataSourceStatus,
  listDataSources,
  testDataSourceConnection,
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
  host: string;
  port: string;
  database: string;
  sslMode: string;
  username: string;
  password: string;
  sampling: boolean;
}

const emptyForm: FormState = {
  name: '',
  kind: 'mysql',
  host: '',
  port: '3306',
  database: '',
  sslMode: 'DISABLED',
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
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<DataSourceStatus | null>(null);
  const testRevision = useRef(0);

  function updateForm(patch: Partial<FormState>) {
    testRevision.current += 1;
    setForm(f => ({ ...f, ...patch }));
    setTestResult(null);
  }

  function resetForm() {
    testRevision.current += 1;
    setFormOpen(false);
    setEditingId(null);
    setForm(emptyForm);
    setTestResult(null);
  }

  function formRequest(): DataSourceRequest {
    return {
      name: form.name.trim(),
      kind: form.kind,
      host: form.host.trim(),
      port: Number(form.port),
      database: form.database.trim(),
      sslMode: form.sslMode,
      username: form.username,
      password: form.password,
      sampling: form.sampling,
    };
  }

  function validForm(): boolean {
    if (!form.name.trim() || !form.host.trim() || !/^\d+$/.test(form.port)) {
      setError('请填写名称、数据库主机和有效端口');
      return false;
    }
    return true;
  }

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
    if (!validForm()) return;
    if (!testResult?.connected) {
      setError('请先测试连接，连接成功后再保存');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const req = formRequest();
      if (editingId) await updateDataSource(editingId, req);
      else await createDataSource(req);
      resetForm();
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function handleTest() {
    if (!validForm()) return;
    const revision = ++testRevision.current;
    setTesting(true);
    setTestResult(null);
    setError(null);
    try {
      const result = await testDataSourceConnection(formRequest(), editingId);
      if (revision === testRevision.current) setTestResult(result);
    } catch (e) {
      if (revision === testRevision.current) {
        setTestResult({ connected: false, error: e instanceof Error ? e.message : String(e) });
      }
    } finally {
      setTesting(false);
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
                const nextOpen = !formOpen;
                resetForm();
                setFormOpen(nextOpen);
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
                    onChange={e => updateForm({ name: e.target.value })}
                    style={{ padding: '10px 12px' }}
                  />
                </div>
                <div>
                  <label style={labelStyle}>类型</label>
                  <select
                    className="da-input"
                    value={form.kind}
                    onChange={e => updateForm({ kind: e.target.value, port: e.target.value === 'mysql' ? '3306' : '5432', sslMode: e.target.value === 'mysql' ? 'DISABLED' : 'disable' })}
                    style={{ padding: '10px 12px' }}
                  >
                    <option value="mysql">MySQL</option>
                    <option value="postgresql">PostgreSQL</option>
                  </select>
                </div>
                <div>
                  <label style={labelStyle}>数据库主机 *</label>
                  <input className="da-input" value={form.host} placeholder="mysql.example.internal"
                    onChange={e => updateForm({ host: e.target.value })} />
                </div>
                <div>
                  <label style={labelStyle}>端口 *</label>
                  <input className="da-input" value={form.port} inputMode="numeric"
                    onChange={e => updateForm({ port: e.target.value })} />
                </div>
                <div>
                  <label style={labelStyle}>数据库名（可选）</label>
                  <input className="da-input" value={form.database}
                    onChange={e => updateForm({ database: e.target.value })} />
                </div>
                <div>
                  <label style={labelStyle}>连接加密</label>
                  <select className="da-input" value={form.sslMode}
                    onChange={e => updateForm({ sslMode: e.target.value })}>
                    {(form.kind === 'mysql'
                      ? [['REQUIRED', '要求加密'], ['VERIFY_IDENTITY', '加密并校验身份'], ['VERIFY_CA', '加密并校验证书'], ['PREFERRED', '优先加密'], ['DISABLED', '不加密']]
                      : [['require', '要求加密'], ['verify-full', '加密并校验身份'], ['verify-ca', '加密并校验证书'], ['disable', '不加密']])
                      .map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                  </select>
                  <div style={{ marginTop: 6, fontSize: '0.75rem', color: 'var(--da-text-3)' }}>
                    不加密适用于可信内网；跨网络区域或敏感数据建议选择加密并校验身份。
                  </div>
                </div>
                <div>
                  <label style={labelStyle}>用户名</label>
                  <input
                    className="da-input"
                    value={form.username}
                    placeholder="数据库用户名"
                    onChange={e => updateForm({ username: e.target.value })}
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
                    onChange={e => updateForm({ password: e.target.value })}
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
                    onChange={e => updateForm({ sampling: e.target.checked })}
                    style={{ width: 16, height: 16 }}
                  />
                  数据采样（低基数列自动采样示例值，提升 Agent 效果）
                </label>
              </div>
              {testResult && (
                <div role={testResult.connected ? 'status' : 'alert'} style={{ marginTop: 16, fontSize: '0.85rem', color: testResult.connected ? 'var(--da-success)' : 'var(--da-danger)' }}>
                  {testResult.connected ? '连接成功，可以保存' : `连接失败：${testResult.error || '请检查连接配置'}`}
                </div>
              )}
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10, marginTop: 20 }}>
                <button className="da-btn" onClick={handleTest} disabled={busy || testing}>
                  {testing ? '正在测试...' : '测试连接'}
                </button>
                <div style={{ display: 'flex', gap: 10 }}>
                  <button className="da-btn" onClick={resetForm} disabled={busy}>取消</button>
                  <button className="da-btn da-btn-primary" onClick={handleSave} disabled={busy || testing || !testResult?.connected}>
                    {editingId ? '保存' : '确认添加'}
                  </button>
                </div>
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
                          let url: URL;
                          try {
                            url = new URL(d.jdbcUrl.replace(/^jdbc:/, ''));
                            if (!['mysql:', 'postgresql:'].includes(url.protocol) || !url.hostname) throw new Error();
                          } catch {
                            setError('旧连接地址格式不受支持，请重新创建 MySQL 或 PostgreSQL 数据源');
                            return;
                          }
                          setEditingId(d.id);
                          setFormOpen(true);
                          testRevision.current += 1;
                          setTestResult(null);
                          setForm({
                            name: d.name,
                            kind: d.kind,
                            host: url.hostname,
                            port: url.port || (d.kind === 'mysql' ? '3306' : '5432'),
                            database: url.pathname.replace(/^\//, ''),
                            sslMode: d.kind === 'mysql'
                              ? (url.searchParams.get('sslMode') || (url.searchParams.get('useSSL') === 'false' ? 'DISABLED' : 'PREFERRED'))
                              : (url.searchParams.get('sslmode') || 'require'),
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
