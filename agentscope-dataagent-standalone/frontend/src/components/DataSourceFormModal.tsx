import React, { useEffect, useId, useRef, useState } from 'react';
import { createDataSource, DataSourceRequest, DataSourceStatus, ExternalDataSource, testDataSourceConnection, updateDataSource } from '../api/datasources';
import DataSourceDialog from './DataSourceDialog';
import { dataSourceConnection } from './dataSourceConnection';
import Icon from './Icon';
import ErrorNotice from './ErrorNotice';

interface FormState {
  name: string; kind: string; host: string; port: string; database: string;
  sslMode: string; username: string; password: string; sampling: boolean;
}

function initialForm(source: ExternalDataSource | null): FormState {
  const connection = source ? dataSourceConnection(source) : null;
  return {
    name: source?.name ?? '', kind: source?.kind ?? 'mysql', host: connection?.host ?? '',
    port: connection?.port ?? '3306', database: connection?.database ?? '',
    sslMode: connection?.sslMode ?? 'DISABLED', username: source?.username ?? '',
    password: '', sampling: source?.sampling ?? true,
  };
}

export default function DataSourceFormModal({ source, onClose, onSaved }: {
  source: ExternalDataSource | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form, setForm] = useState(() => initialForm(source));
  const [error, setError] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<DataSourceStatus | null>(null);
  const [testing, setTesting] = useState(false);
  const [busy, setBusy] = useState(false);
  const revision = useRef(0);
  const active = useRef(true);
  const saving = useRef(false);
  const id = useId();
  useEffect(() => {
    active.current = true;
    return () => { active.current = false; revision.current += 1; };
  }, []);

  function update(patch: Partial<FormState>) {
    revision.current += 1;
    setForm(previous => ({ ...previous, ...patch }));
    setTestResult(null);
    setError(null);
  }

  function request(): DataSourceRequest {
    return { ...form, name: form.name.trim(), host: form.host.trim(), database: form.database.trim(), port: Number(form.port) };
  }

  function valid() {
    if (!form.name.trim() || !form.host.trim() || !/^\d+$/.test(form.port) || Number(form.port) < 1 || Number(form.port) > 65535) {
      setError('请填写数据源名称、主机地址和有效端口（1–65535）');
      return false;
    }
    return true;
  }

  async function test() {
    if (testing || !valid()) return;
    const current = ++revision.current;
    setTesting(true); setTestResult(null); setError(null);
    try {
      const result = await testDataSourceConnection(request(), source?.id);
      if (active.current && revision.current === current) setTestResult(result);
    } catch (e) {
      if (active.current && revision.current === current) setTestResult({ connected: false, error: e instanceof Error ? e.message : String(e) });
    } finally {
      if (active.current) setTesting(false);
    }
  }

  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (saving.current || !valid()) return;
    if (!testResult?.connected || testing) { setError('请先检测连接，连接成功后再保存'); return; }
    saving.current = true; setBusy(true); setError(null);
    try {
      if (source) await updateDataSource(source.id, request());
      else await createDataSource(request());
      if (active.current) onSaved();
    } catch (e) {
      if (active.current) setError(e instanceof Error ? e.message : String(e));
    } finally {
      saving.current = false;
      if (active.current) setBusy(false);
    }
  }

  return <DataSourceDialog title={source ? '配置数据源' : '添加数据源'} description="填写连接参数，检测通过后保存。" onClose={onClose} busy={busy} className="ds-form-dialog">
    <form onSubmit={save} noValidate>
      <div className="ds-dialog-body">
        <fieldset className="ds-form-fields" disabled={busy}>
          <section className="ds-form-section" aria-labelledby={`${id}-connection`}><h3 id={`${id}-connection`}>连接信息</h3><div className="ds-fields">
          <label className="ds-field ds-full">数据源名称<input autoFocus value={form.name} placeholder="例如：业务报表数据库" maxLength={64} required onChange={e => update({ name: e.target.value })} /></label>
          <label className="ds-field ds-full">接入方式<select value={form.kind} onChange={e => update({ kind: e.target.value, port: e.target.value === 'mysql' ? '3306' : '5432', sslMode: e.target.value === 'mysql' ? 'DISABLED' : 'disable' })}>
            <option value="mysql">MySQL（含 Doris MySQL 协议）</option><option value="postgresql">PostgreSQL</option>
          </select></label>
          <div className="ds-hostport ds-full"><label className="ds-field">主机地址<input value={form.host} placeholder="mysql.example.internal" aria-describedby={`${id}-server-hint`} required onChange={e => update({ host: e.target.value })} /></label>
          <label className="ds-field">端口<input value={form.port} inputMode="numeric" required onChange={e => update({ port: e.target.value })} /></label></div>
          <p id={`${id}-server-hint`} className="ds-host-hint ds-full"><Icon name="info" size="sm" /><span>由部署 DataAgent 的服务器发起连接，localhost 指服务器自身。</span></p>
          <label className="ds-field ds-full">数据库<span className="ds-label-hint">可选</span><input value={form.database} placeholder="可不填，关联表时再选择" onChange={e => update({ database: e.target.value })} /></label>
          </div></section>
          <section className="ds-form-section ds-credentials" aria-labelledby={`${id}-credentials`}><h3 id={`${id}-credentials`}>访问凭据</h3><div className="ds-fields">
          <label className="ds-field">用户名<input value={form.username} aria-label="用户名" placeholder="例如：demo_reader" autoComplete="off" aria-describedby={`${id}-reader-hint`} onChange={e => update({ username: e.target.value })} /><small id={`${id}-reader-hint`}>建议使用仅有业务表读取权限的账号。</small></label>
          <label className="ds-field">密码<input type="password" value={form.password} autoComplete="new-password" placeholder={source ? '留空保留原密码' : '请输入数据库密码'} onChange={e => update({ password: e.target.value })} /></label>
          </div></section>
          <details className="ds-advanced ds-full"><summary>更多连接选项<span>连接加密与数据采样</span></summary><div className="ds-fields">
          <label className="ds-field ds-full">连接加密<select value={form.sslMode} onChange={e => update({ sslMode: e.target.value })}>
            {(form.kind === 'mysql'
              ? [['REQUIRED', '要求加密'], ['VERIFY_IDENTITY', '加密并校验身份'], ['VERIFY_CA', '加密并校验证书'], ['PREFERRED', '优先加密'], ['DISABLED', '不加密']]
              : [['require', '要求加密'], ['verify-full', '加密并校验身份'], ['verify-ca', '加密并校验证书'], ['disable', '不加密']])
              .map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select><small>不加密适用于可信内网；跨网络区域建议加密并校验身份。</small></label>
          <label className="ds-sampling ds-full"><input type="checkbox" checked={form.sampling} onChange={e => update({ sampling: e.target.checked })} /><span>数据采样<small>每表最多 1000 行，用于观察列样例，非完整枚举。</small></span></label>
          </div></details>
        </fieldset>
        {error && <ErrorNotice error={error} context="datasource" secrets={[form.password]} />}
        {testResult && (testResult.connected
          ? <div className="ds-feedback ds-connected" role="status"><Icon name="check" size="sm" />连接成功，可以保存</div>
          : <ErrorNotice error={testResult.error || '请检查连接配置'} context="datasource" secrets={[form.password]}>
            <span>没有平台能访问到的数据库时，可以先在知识库里上传 Excel / CSV 文件体验问数。</span>
          </ErrorNotice>)}
      </div>
      <footer className="ds-dialog-foot"><span className={`ds-test-state${testResult?.connected ? ' is-connected' : ''}`} role="status">{testing ? '正在检测连接…' : testResult?.connected ? '连接成功，可以保存' : testResult ? '连接未成功' : '尚未检测连接'}</span><button type="button" className="da-btn" onClick={test} disabled={busy || testing}><Icon name="refresh" size="sm" />{testing ? '检测中…' : '检测连接'}</button>
        <button type="button" className="da-btn" onClick={onClose} disabled={busy}>取消</button><button className="da-btn da-btn-primary" type="submit" disabled={busy || testing || !testResult?.connected}>{busy ? '保存中…' : '保存'}</button>
      </footer>
    </form>
  </DataSourceDialog>;
}
