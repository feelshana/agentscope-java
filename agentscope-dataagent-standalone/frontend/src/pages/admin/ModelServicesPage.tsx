import React, { useEffect, useRef, useState } from 'react';
import { KnowledgeBreadcrumb } from '../../components/KnowledgeWorkspace';
import Dialog from '../../components/DataSourceDialog';
import Icon from '../../components/Icon';
import { activateModelService, deleteModelService, listModelServices, ModelProvider, ModelService, ModelServiceRequest, ModelServicesView, saveModelService, testModelService } from '../../api/modelServices';
import '../../secondary-pages.css';
import './model-services.css';

const providers: Record<ModelProvider, string> = { OPENAI: 'OpenAI 兼容', DEEPSEEK: 'DeepSeek', QWEN: '千问（百炼兼容）' };
const presets: Record<ModelProvider, string> = { OPENAI: '', DEEPSEEK: 'https://api.deepseek.com', QWEN: 'https://dashscope.aliyuncs.com/compatible-mode' };
const empty: ModelServiceRequest = { name: '', provider: 'OPENAI', baseUrl: '', endpointPath: '/v1/chat/completions', modelName: '', apiKey: '', stream: true, timeoutSeconds: 60, temperature: 0, disableThinking: false, external: false };
const message = (e: unknown) => e instanceof Error ? e.message : '操作失败，请稍后重试。';
const time = (text: string | null) => text ? new Date(text).toLocaleString() : '—';
function endpointPreview(value: ModelServiceRequest): string {
  const base = value.baseUrl.trim().replace(/\/+$/, '');
  const path = value.endpointPath.trim() || '/v1/chat/completions';
  return base ? base + (/\/v\d+$/.test(base) && path.startsWith('/v1/') ? path.substring(3) : path) : '请先填写 Base URL';
}

export default function ModelServicesPage() {
  const [data, setData] = useState<ModelServicesView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState('');
  const lock = useRef(false);
  const [editing, setEditing] = useState<{ id: string | null; value: ModelServiceRequest } | null>(null);
  const [confirm, setConfirm] = useState<{ action: 'activate' | 'delete'; service: ModelService } | null>(null);
  const [modalError, setModalError] = useState('');
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(''); setNotice('');
    listModelServices(controller.signal).then(setData).catch(e => { if (!controller.signal.aborted) setError(message(e)); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [reload]);

  async function run(label: string, task: () => Promise<void>, modal = false) {
    if (lock.current) return;
    lock.current = true; setBusy(label); setError(''); setNotice(''); setModalError('');
    try { await task(); } catch (e) { if (modal) setModalError(message(e)); else setError(message(e)); }
    finally { lock.current = false; setBusy(''); }
  }
  function edit(service?: ModelService) {
    setModalError('');
    setEditing({ id: service?.id ?? null, value: service ? {
      revision: service.revision, name: service.name, provider: service.provider, baseUrl: service.baseUrl,
      endpointPath: service.endpointPath, modelName: service.modelName, apiKey: '', stream: service.stream,
      timeoutSeconds: service.timeoutSeconds, temperature: service.temperature, disableThinking: service.disableThinking, external: service.external,
    } : { ...empty } });
  }
  const field = <K extends keyof ModelServiceRequest>(key: K, value: ModelServiceRequest[K]) => setEditing(old => old && ({ ...old, value: { ...old.value, [key]: value } }));

  return <div className="kw-page kw-secondary-page ms-page ds-page">
    <KnowledgeBreadcrumb items={[{ label: '对话', to: '/chat' }, { label: '更多', to: '/more' }, { label: '大模型服务' }]} />
    <div className="kw-scroll"><div className="kw-content">
      <div className="kw-pagehead"><div className="kw-heading"><h1>大模型服务</h1><p>维护模型连接，手动选择问数与建模使用的服务。</p></div><button className="da-btn da-btn-primary" disabled={!!busy || loading || !data} onClick={() => edit()}><Icon name="plus" />添加服务</button></div>
      {error && <div role="alert" className="secondary-alert"><span>{error}</span><button className="da-btn da-btn-ghost da-btn-sm" disabled={!!busy} onClick={() => setReload(v => v + 1)}>刷新</button></div>}
      {notice && <p role="status" className="ms-notice">{notice}</p>}
      {loading ? <p role="status">正在加载模型服务…</p> : data && <>
        <section className="ms-current" aria-label="当前使用的模型服务"><div><span className="ms-eyebrow">当前使用</span><h2>{data.active.name} <span className="ds-status ds-connected">{data.active.id ? `已生效 · v${data.active.revision}` : '兼容启动配置'}</span></h2><p>{data.active.id ? `${data.active.modelName} · ${data.active.external ? '公网服务' : '公司 / 内网服务'}` : '尚未应用页面连接；当前沿用启动时的大模型配置。'}</p></div><div className="ms-current-meta">{data.active.id ? <>切换时间：{time(data.active.appliedAt)}<br />操作人：{data.active.actorId}</> : '添加、测试并设为当前后生效'}</div></section>
        <div className="secondary-notice"><Icon name="info" /><p>切换无需重启；新的一轮请求使用所选服务，正在执行的轮次继续原服务。故障时由管理员手动切换，不自动转用其他服务。</p></div>
        <div className="ms-listhead"><h2>服务连接 <span>{data.services.length}</span></h2><button className="da-btn da-btn-ghost da-btn-sm" disabled={!!busy} onClick={() => setReload(v => v + 1)}>刷新</button></div>
        <div className="kw-tablebox ms-tablebox"><table className="kw-table ms-table"><thead><tr><th>连接名称</th><th>服务类型 / 模型</th><th>最近测试</th><th>操作</th></tr></thead><tbody>
          {data.services.map(service => <tr key={service.id}>
            <td><strong>{service.name}</strong> {service.current && <span className="ds-status ds-connected">当前使用</span>}{service.pendingChanges && <div className="ms-pending">有新版本待应用</div>}<div className="ds-meta">{service.external ? '公网服务' : '公司 / 内网服务'} · v{service.revision} · Key 已配置</div></td>
            <td>{providers[service.provider]}<div className="ds-meta" title={service.baseUrl}>{service.modelName}</div></td>
            <td>{service.lastTest ? <><span className={`ds-status ${service.lastTest.success ? 'ds-connected' : 'ds-failed'}`}>{service.lastTest.success ? '测试通过' : '测试失败'}</span><div className="ds-meta">{time(service.lastTest.testedAt)} · {service.lastTest.durationMs} ms</div>{!service.lastTest.success && <div className="ms-test-error">{service.lastTest.message}</div>}</> : <span className="ds-status">未测试</span>}</td>
            <td><div className="ds-row-actions"><button className="da-btn da-btn-ghost da-btn-sm" disabled={!!busy} onClick={() => run(`test:${service.id}`, async () => { const result = await testModelService(service); setData(await listModelServices()); if (result.success) setNotice(result.message); else setError(result.message); })}>{busy === `test:${service.id}` ? '测试中…' : '测试'}</button><button className="da-btn da-btn-ghost da-btn-sm" disabled={!!busy} onClick={() => edit(service)}>编辑</button><button className="da-btn da-btn-ghost da-btn-sm" disabled={!!busy || (service.current && !service.pendingChanges) || !service.lastTest?.success || service.lastTest.revision !== service.revision} onClick={() => { setModalError(''); setConfirm({ action: 'activate', service }); }}>{service.current && service.pendingChanges ? '应用新版本' : '设为当前'}</button><button className="da-btn da-btn-ghost da-btn-sm" disabled={!!busy || service.current} onClick={() => { setModalError(''); setConfirm({ action: 'delete', service }); }}>删除</button></div></td>
          </tr>)}
          {!data.services.length && <tr><td colSpan={4} className="ds-empty"><Icon name="settings" /><h2>还没有服务连接</h2><p>添加公司服务或公网备用，完成测试后即可设为当前。</p><button className="da-btn da-btn-primary da-btn-sm" onClick={() => edit()}>添加第一个服务</button></td></tr>}
        </tbody></table></div>
        <p className="ds-page-note">API Key 加密保存，不回显明文；编辑时留空保留原 Key。保存仅更新连接资料，测试通过并显式应用后才影响新请求。</p>
        {!!data.history.length && <details className="ms-history"><summary>最近切换记录</summary><ul>{[...data.history].reverse().slice(0, 10).map((event, i) => <li key={`${event.appliedAt}-${i}`}>{time(event.appliedAt)} · {event.actorId} · {data.services.find(s => s.id === event.toId)?.name ?? event.toId} · v{event.revision}</li>)}</ul></details>}
      </>}
    </div></div>
    {editing && <Dialog title={editing.id ? '编辑模型服务' : '添加模型服务'} description="保存后先测试连接，再设为当前使用。" busy={!!busy} onClose={() => setEditing(null)}>
      <form onSubmit={event => { event.preventDefault(); const draft = editing; run('save', async () => { setData(await saveModelService(draft.id, draft.value)); setEditing(null); setNotice('连接已保存，请测试当前版本后再应用。'); }, true); }}>
        <div className="ds-dialog-body"><fieldset className="ds-fields" disabled={!!busy}>
          <label className="ds-field">连接名称<input required maxLength={64} value={editing.value.name} onChange={e => field('name', e.target.value)} placeholder="例如：公司模型服务" /></label>
          <label className="ds-field">服务类型<select value={editing.value.provider} onChange={e => { const provider = e.target.value as ModelProvider; setEditing(old => old && ({ ...old, value: { ...old.value, provider, baseUrl: presets[provider], disableThinking: provider !== 'OPENAI', external: provider !== 'OPENAI' } })); }}>{Object.entries(providers).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
          <label className="ds-field ds-full">Base URL<input required type="url" maxLength={2048} value={editing.value.baseUrl} onChange={e => field('baseUrl', e.target.value)} placeholder="https://model.example.com" /><small>填写服务根地址；高级设置可调整接口路径。公司网关选择 OpenAI 兼容。</small></label>
          <label className="ds-field ds-full">模型名称<input required maxLength={128} value={editing.value.modelName} onChange={e => field('modelName', e.target.value)} placeholder="提供方要求的 model name" /></label>
          <label className="ds-field ds-full">API Key<input required={!editing.id} type="password" autoComplete="new-password" maxLength={16384} value={editing.value.apiKey} onChange={e => field('apiKey', e.target.value)} placeholder={editing.id ? '已配置，留空保留原 Key' : '填写提供方凭据'} /><small>公司要求的完整签名串按凭据原样填写；不填 Supersonic 数据库密文。</small></label>
          <details className="ds-advanced ds-full"><summary>高级设置</summary><div className="ds-fields">
            <label className="ds-field ds-full">接口路径<input required maxLength={256} value={editing.value.endpointPath} onChange={e => field('endpointPath', e.target.value)} /><small>默认 /v1/chat/completions；请求地址：{endpointPreview(editing.value)}</small></label>
            <label className="ds-field">请求超时（秒）<input type="number" min={5} max={300} required value={editing.value.timeoutSeconds} onChange={e => field('timeoutSeconds', Number(e.target.value))} /></label>
            <label className="ds-field">Temperature<input type="number" min={0} max={2} step={0.1} required value={editing.value.temperature} onChange={e => field('temperature', Number(e.target.value))} /></label>
            <label className="ms-check ds-full"><input type="checkbox" checked={editing.value.stream} onChange={e => field('stream', e.target.checked)} />使用流式响应（SSE）</label>
            <label className="ms-check ds-full"><input type="checkbox" checked={editing.value.disableThinking} onChange={e => field('disableThinking', e.target.checked)} />关闭思考模式（DeepSeek / 千问适用）</label>
            <label className="ms-check ds-full"><input type="checkbox" checked={editing.value.external} onChange={e => field('external', e.target.checked)} />标记为公网服务（请确认业务数据允许发送）</label>
          </div></details>
        </fieldset>{modalError && <p role="alert" className="ds-feedback ds-failed">{modalError} 草稿已保留；版本冲突时请关闭弹窗并刷新。</p>}</div>
        <footer className="ds-dialog-foot"><button type="button" className="da-btn da-btn-outline" disabled={!!busy} onClick={() => setEditing(null)}>取消</button><button type="submit" className="da-btn da-btn-primary" disabled={!!busy}>{busy ? '保存中…' : '保存连接'}</button></footer>
      </form>
    </Dialog>}
    {confirm && <Dialog title={confirm.action === 'activate' ? '切换当前模型服务' : '删除服务连接'} busy={!!busy} onClose={() => setConfirm(null)}>
      <div className="ds-dialog-body"><p>{confirm.action === 'activate' ? `确认使用“${confirm.service.name}”的 v${confirm.service.revision} 版本？` : `确认删除“${confirm.service.name}”？`}</p>{confirm.action === 'activate' && <p>新轮次将使用此服务，在途轮次保持原服务。{confirm.service.external && '这是公网服务，后续模型请求会发送至该服务，请确认业务数据允许发送。'}</p>}{modalError && <p role="alert" className="ds-feedback ds-failed">{modalError}</p>}</div>
      <footer className="ds-dialog-foot"><button className="da-btn da-btn-outline" disabled={!!busy} onClick={() => setConfirm(null)}>取消</button><button className="da-btn da-btn-primary" disabled={!!busy} onClick={() => { const action = confirm; run(action.action, async () => { setData(await (action.action === 'activate' ? activateModelService(action.service) : deleteModelService(action.service))); setConfirm(null); setNotice(action.action === 'activate' ? '已切换，新的一轮请求立即使用所选服务。' : '服务连接已删除。'); }, true); }}>{busy ? '处理中…' : confirm.action === 'activate' ? '确认切换' : '确认删除'}</button></footer>
    </Dialog>}
  </div>;
}
