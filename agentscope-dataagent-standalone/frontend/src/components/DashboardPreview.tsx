import React, { useEffect, useMemo, useRef, useState } from 'react';
import chartRuntime from 'echarts/dist/echarts.min.js?raw';
import { Dashboard, queryDashboard } from '../api/dashboards';
import { dashboardBridgeMessage } from './dashboardState';
import { rememberDashboardThumbnail } from './dashboardThumbnails';
import { getToken } from '../api/auth';

/** Trusted outer frame restricts navigation of the opaque-origin generated inner frame. */
export const DASHBOARD_HOST = `<!doctype html><html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; frame-src 'none'; base-uri 'none'; form-action 'none'">
<style>html,body,iframe{margin:0;width:100%;height:100%;border:0;display:block;background:#f7f8fb}</style></head><body>
<script>(function(){
let frame,channel,initialized=false;
addEventListener('message',function(event){
 if(event.source===parent&&!initialized&&event.data&&event.data.kind==='init'){
  initialized=true;channel=event.data.channel;
  const doc=new DOMParser().parseFromString(event.data.html,'text/html');
  const policy=doc.createElement('meta');policy.httpEquiv='Content-Security-Policy';
  policy.content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src 'none'; connect-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'; worker-src 'none'";
  doc.head.prepend(policy);
  const runtime=doc.createElement('script');runtime.textContent=event.data.runtime;
  const bridge=doc.createElement('script');bridge.textContent='('+${JSON.stringify(bridgeRuntime.toString())}+')('+JSON.stringify(channel)+')';
  policy.after(bridge,runtime);
  frame=document.createElement('iframe');frame.sandbox='allow-scripts';frame.title='生成的仪表盘';
  frame.srcdoc='<!doctype html>'+doc.documentElement.outerHTML;
  document.body.append(frame);
 }else if(frame&&event.source===frame.contentWindow&&event.data&&event.data.channel===channel){
  if(event.data.kind==='query'||event.data.kind==='runtime-error'||event.data.kind==='thumbnail')parent.postMessage(event.data,'*');
 }else if(frame&&event.source===parent&&event.data&&event.data.channel===channel&&(event.data.kind==='result'||event.data.kind==='capture')){
  frame.contentWindow.postMessage(event.data,'*');
 }
});parent.postMessage({kind:'host-ready'},'*');
})();</script></body></html>`;

function bridgeRuntime(channel: string) {
  let sequence = 0;
  const pending = new Map<number, { resolve: (data: unknown) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }>();
  Object.defineProperty(window, 'dataagent', { value: Object.freeze({
    query(sql: string): Promise<unknown> {
      if (typeof sql !== 'string' || !sql.trim() || sql.length > 20000) return Promise.reject(new Error('查询SQL无效'));
      const id = ++sequence;
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => { pending.delete(id); reject(new Error('查询超时，请刷新重试')); }, 180000);
        pending.set(id, { resolve, reject, timer });
        parent.postMessage({ kind: 'query', channel, id, sql }, '*');
      });
    },
  }), writable: false, configurable: false });
  addEventListener('message', event => {
    if (event.source !== parent || event.data?.channel !== channel) return;
    if (event.data.kind === 'capture') { void capture(); return; }
    if (event.data.kind !== 'result') return;
    const task = pending.get(event.data.id as number);
    if (!task) return;
    pending.delete(event.data.id as number); clearTimeout(task.timer);
    if (event.data.error) task.reject(new Error(String(event.data.error)));
    else task.resolve(event.data.data);
  });
  let captured = false;
  async function capture() {
    if (captured || pending.size) return;
    const engine = (window as unknown as { echarts?: { getInstanceByDom: (element: Element) => { renderToCanvas: (options: object) => HTMLCanvasElement } | undefined } }).echarts;
    if (!engine) return;
    const elements = Array.from(document.querySelectorAll('[_echarts_instance_]')).filter(el => el.clientWidth && el.clientHeight).slice(0, 3);
    if (!elements.length) return;
    captured = true;
    try {
      const images = elements.map(el => engine.getInstanceByDom(el)?.renderToCanvas({ pixelRatio: .8, backgroundColor: '#ffffff' })).filter((image): image is HTMLCanvasElement => Boolean(image));
      if (!images.length) return;
      const canvas = document.createElement('canvas'); canvas.width = 760; canvas.height = 430;
      const context = canvas.getContext('2d'); if (!context) return;
      context.fillStyle = '#f5f7fa'; context.fillRect(0, 0, 760, 430);
      images.forEach((image, index) => {
        const box = images.length === 1 ? [12, 12, 736, 406] : index === 0 ? [12, 12, 736, 226] : images.length === 2 ? [12, 250, 736, 168] : [12 + (index - 1) * 374, 250, 362, 168];
        const scale = Math.min(box[2] / image.width, box[3] / image.height);
        context.fillStyle = '#ffffff'; context.fillRect(...box as [number, number, number, number]);
        context.drawImage(image, box[0] + (box[2] - image.width * scale) / 2, box[1] + (box[3] - image.height * scale) / 2, image.width * scale, image.height * scale);
      });
      parent.postMessage({ kind: 'thumbnail', channel, image: canvas.toDataURL('image/png') }, '*');
    } catch { /* A chart without an exportable canvas simply has no cover. */ }
  }
  const fail = (error: unknown) => parent.postMessage({ kind: 'runtime-error', channel, error: String(error).slice(0, 300) }, '*');
  addEventListener('error', event => fail(event.message));
  addEventListener('unhandledrejection', event => fail(event.reason));
}

export default function DashboardPreview({ dashboard, draft }: { dashboard: Dashboard; draft: boolean }) {
  const frame = useRef<HTMLIFrameElement>(null);
  const channel = useMemo(() => crypto.randomUUID(), [dashboard.id, dashboard.revision, draft]);
  const [error, setError] = useState('');
  const [queries, setQueries] = useState(0);
  const [completedAt, setCompletedAt] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    const handled = new Set<number>();
    let active = 0;
    let initialized = false;
    let successful = 0;
    let failed = false;
    let thumbnailSaved = false;
    let captureTimer: ReturnType<typeof setTimeout> | undefined;
    const credential = getToken();
    setError(''); setQueries(0); setCompletedAt('');
    const onMessage = async (event: MessageEvent) => {
      const source = frame.current?.contentWindow;
      const data = dashboardBridgeMessage(event, source, channel);
      if (!source || !data) return;
      if (data.kind === 'host-ready' && !initialized) {
        initialized = true;
        source.postMessage({ kind: 'init', channel, html: dashboard.html, runtime: chartRuntime.replace(/<\/script/gi, '<\\/script') }, '*');
        return;
      }
      if (data.kind === 'runtime-error') { failed = true; setError(String(data.error || '页面运行错误').slice(0, 300)); return; }
      if (data.kind === 'thumbnail') {
        if (!thumbnailSaved && successful && !active && !failed && data.image && getToken() === credential) {
          thumbnailSaved = true; rememberDashboardThumbnail(dashboard.id, dashboard.revision, data.image);
        }
        return;
      }
      if (data.kind !== 'query' || !Number.isSafeInteger(data.id) || typeof data.sql !== 'string') return;
      const id = data.id as number;
      if (handled.has(id)) return;
      handled.add(id);
      const reply = (result: object) => { if (!controller.signal.aborted) source.postMessage({ kind: 'result', channel, id, ...result }, '*'); };
      if (active >= 6 || handled.size > 120) { reply({ error: '查询过于频繁，请刷新或减少图表' }); return; }
      clearTimeout(captureTimer); active++; setQueries(active);
      try {
        const result = await queryDashboard(dashboard.id, draft, dashboard.revision, data.sql, controller.signal);
        successful++;
        reply({ data: result });
      } catch (e) {
        if (!controller.signal.aborted) {
          const message = e instanceof Error ? e.message : '查询失败';
          failed = true; setError(message); reply({ error: message });
        }
      } finally {
        active--;
        if (!controller.signal.aborted) {
          setQueries(active);
          if (!active && successful && !failed) {
            setCompletedAt(new Date().toLocaleTimeString('zh-CN', { hour12: false }));
            if (!thumbnailSaved) captureTimer = setTimeout(() => source.postMessage({ kind: 'capture', channel }, '*'), 1400);
          }
        }
      }
    };
    window.addEventListener('message', onMessage);
    return () => { controller.abort(); clearTimeout(captureTimer); window.removeEventListener('message', onMessage); };
  }, [channel, dashboard.id, dashboard.revision, dashboard.html, draft]);
  return <div className="dashboard-preview">
    {error && <div className="dashboard-runtime-error" role="alert">{error}{draft && ' · 可在左侧告诉助手修正。'}</div>}
    <iframe key={channel} ref={frame} title={`${dashboard.title}预览`} sandbox="allow-scripts allow-same-origin" srcDoc={DASHBOARD_HOST} referrerPolicy="no-referrer" />
    <div className="dashboard-live-status" role="status"><span className={error ? 'dot error' : 'dot'} />{queries ? `正在查询 ${queries} 项数据…` : error ? '部分内容需要调整' : completedAt ? `按知识库权限查询 · 本次查询完成于 ${completedAt}` : '等待数据加载 · 按知识库权限查询'}</div>
  </div>;
}
