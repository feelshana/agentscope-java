import React, { useEffect, useMemo, useRef, useState } from 'react';
import chartRuntime from 'echarts/dist/echarts.min.js?raw';
import { Dashboard, queryDashboard } from '../api/dashboards';
import { dashboardBridgeMessage } from './dashboardState';
import { rememberDashboardThumbnail } from './dashboardThumbnails';
import { getToken } from '../api/auth';
import { dashboardBridgeRuntime } from './dashboardBridgeRuntime';
import { DashboardQueryQueue, previewSummary, PanelProgress, PanelReference, PreviewIssue, PreviewReport, QueryProgress } from './dashboardRuntime';

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
  const bridge=doc.createElement('script');bridge.textContent='('+${JSON.stringify(dashboardBridgeRuntime.toString())}+')('+JSON.stringify(channel)+','+JSON.stringify(!!event.data.editable)+')';
  policy.after(bridge,runtime);
  frame=document.createElement('iframe');frame.sandbox='allow-scripts';frame.title='生成的仪表盘';
  frame.srcdoc='<!doctype html>'+doc.documentElement.outerHTML;
  document.body.append(frame);
 }else if(frame&&event.source===frame.contentWindow&&event.data&&event.data.channel===channel){
  if(event.data.kind==='query'||event.data.kind==='runtime-error'||event.data.kind==='thumbnail'||event.data.kind==='panel-state'||event.data.kind==='panel-select')parent.postMessage(event.data,'*');
 }else if(frame&&event.source===parent&&event.data&&event.data.channel===channel&&(event.data.kind==='result'||event.data.kind==='capture')){
  frame.contentWindow.postMessage(event.data,'*');
 }
});parent.postMessage({kind:'host-ready'},'*');
})();</script></body></html>`;

export default function DashboardPreview({ dashboard, draft, onReport, onSelect, onRepair, repairDisabled = false }: {
  dashboard: Dashboard; draft: boolean; onReport?: (report: PreviewReport) => void;
  onSelect?: (panel: PanelReference, revision: string) => void; onRepair?: (report: PreviewReport) => void; repairDisabled?: boolean;
}) {
  const frame = useRef<HTMLIFrameElement>(null);
  const channel = useMemo(() => crypto.randomUUID(), [dashboard.id, dashboard.revision, draft]);
  const [report, setReport] = useState<PreviewReport>({ revision: dashboard.revision, initialized: false, settled: false, queries: { active: 0, queued: 0, succeeded: 0, failed: 0, total: 0 }, panels: [], issues: [] });
  const callbacks = useRef({ onReport, onSelect }); callbacks.current = { onReport, onSelect };
  useEffect(() => {
    const controller = new AbortController();
    const handled = new Set<number>();
    const panels = new Map<string, PanelProgress>();
    const issues = new Map<string, PreviewIssue>();
    const epochs = new Map<string, number>();
    let queries: QueryProgress = { active: 0, queued: 0, succeeded: 0, failed: 0, total: 0 };
    let initialized = false, thumbnailSaved = false;
    let settleTimer: ReturnType<typeof setTimeout> | undefined;
    let stalledTimer: ReturnType<typeof setTimeout> | undefined;
    let captureTimer: ReturnType<typeof setTimeout> | undefined;
    const credential = getToken();
    const publish = (settled = false) => {
      if (controller.signal.aborted) return;
      const next: PreviewReport = { revision: dashboard.revision, initialized, settled, queries: { ...queries }, panels: [...panels.values()], issues: [...issues.values()] };
      setReport(next); callbacks.current.onReport?.(next);
    };
    const changed = () => {
      clearTimeout(stalledTimer);
      issues.delete('stalled');
      clearTimeout(settleTimer); clearTimeout(captureTimer); publish(false);
      settleTimer = setTimeout(() => {
        const idle = !queries.active && !queries.queued;
        const settled = initialized && idle && (!panels.size || [...panels.values()].every(p => !['pending', 'loading'].includes(p.state)) || [...issues.values()].some(issue => issue.kind === 'runtime'));
        publish(settled);
        if (!settled) stalledTimer = setTimeout(() => {
          issues.set('stalled', { kind: 'runtime', message: '预览长时间没有新进度，请刷新预览重试。' });
          publish(true);
        }, 90000);
        if (settled && queries.succeeded && !issues.size && !thumbnailSaved) captureTimer = setTimeout(() => frame.current?.contentWindow?.postMessage({ kind: 'capture', channel }, '*'), 500);
      }, 500);
    };
    const issue = (key: string, value: PreviewIssue) => { if (issues.size >= 12 && !issues.has(key)) issues.delete(issues.keys().next().value!); issues.set(key, value); changed(); };
    const queue = new DashboardQueryQueue(next => { queries = next; changed(); });
    changed();
    const onMessage = (event: MessageEvent) => {
      const source = frame.current?.contentWindow;
      const data = dashboardBridgeMessage(event, source, channel);
      if (!source || !data || controller.signal.aborted) return;
      if (data.kind === 'host-ready' && !initialized) {
        initialized = true;
        source.postMessage({ kind: 'init', channel, html: dashboard.html, editable: draft && dashboard.canEdit, runtime: chartRuntime.replace(/<\/script/gi, '<\\/script') }, '*');
        changed(); return;
      }
      if (data.kind === 'runtime-error') { issue('runtime', { kind: 'runtime', message: data.error || '页面运行错误' }); return; }
      if (data.kind === 'panel-select') { if (draft && dashboard.canEdit) callbacks.current.onSelect?.({ id: data.panelId!, title: data.title! }, dashboard.revision); return; }
      if (data.kind === 'panel-state') {
        if (!panels.has(data.panelId!) && panels.size >= 40) return;
        if (data.state === 'loading') {
          epochs.set(data.panelId!, (epochs.get(data.panelId!) || 0) + 1);
          for (const [key, value] of issues) if (value.panelId === data.panelId) issues.delete(key);
        }
        panels.set(data.panelId!, { id: data.panelId!, title: data.title!, state: data.state as PanelProgress['state'], error: data.error || undefined });
        if (data.state === 'error') issue('panel:' + data.panelId, { kind: 'panel', panelId: data.panelId, message: data.error || '面板未完成' }); else changed();
        return;
      }
      if (data.kind === 'thumbnail') {
        if (!thumbnailSaved && queries.succeeded && !queries.active && !queries.queued && !issues.size && data.image && getToken() === credential) { thumbnailSaved = true; rememberDashboardThumbnail(dashboard.id, dashboard.revision, data.image); }
        return;
      }
      if (data.kind !== 'query' || handled.has(data.id!)) return;
      if (handled.size >= 120) {
        const message = '本次预览查询次数超过120次，请刷新或减少重复查询';
        issue('budget', { kind: 'query', message });
        source.postMessage({ kind: 'result', channel, id: data.id, error: message }, '*');
        return;
      }
      handled.add(data.id!);
      const id = data.id!, epoch = data.panelId ? epochs.get(data.panelId) : undefined;
      const reply = (result: object) => { if (!controller.signal.aborted && getToken() === credential) source.postMessage({ kind: 'result', channel, id, ...result }, '*'); };
      void queue.enqueue(() => {
        if (getToken() !== credential) throw new Error('登录状态已变化，请重新进入看板');
        return queryDashboard(dashboard.id, draft, dashboard.revision, data.sql!, controller.signal);
      }).then(result => reply({ data: result })).catch(error => {
        if (controller.signal.aborted) return;
        const message = error instanceof Error ? error.message : '查询失败';
        if (!data.panelId || epochs.get(data.panelId) === epoch) issue('query:' + id, { kind: 'query', message, panelId: data.panelId, sql: data.sql?.slice(0, 4000) });
        reply({ error: message });
      });
    };
    window.addEventListener('message', onMessage);
    return () => { controller.abort(); queue.close(); clearTimeout(settleTimer); clearTimeout(stalledTimer); clearTimeout(captureTimer); window.removeEventListener('message', onMessage); };
  }, [channel, dashboard.id, dashboard.revision, dashboard.html, draft, dashboard.canEdit]);
  const bad = report.issues.length > 0 || report.panels.some(panel => panel.state === 'error');
  return <div className="dashboard-preview">
    {bad && <div className="dashboard-runtime-error" role="alert"><strong>预览发现问题</strong><span>{report.issues[0]?.message || '部分面板未完成'}</span>{draft && onRepair && <button type="button" disabled={repairDisabled || !report.settled} onClick={() => onRepair(report)}>让助手修复失败项</button>}</div>}
    <iframe key={channel} ref={frame} title={`${dashboard.title}预览`} sandbox="allow-scripts allow-same-origin" srcDoc={DASHBOARD_HOST} referrerPolicy="no-referrer" />
    <div className="dashboard-live-status" role="status"><span className={bad ? 'dot error' : 'dot'} />{previewSummary(report)}<details><summary>运行检查</summary><div><p>文件检查与页面自检分开；业务口径仍需核对。</p>{report.panels.map(panel => <p key={panel.id}>{panel.title} · {{ pending: '等待开始', loading: '加载中', ready: '已显示', empty: '暂无数据', error: '失败' }[panel.state]}{panel.error ? `：${panel.error}` : ''}</p>)}{report.issues.map((value, index) => <p key={index}>{value.panelId ? `${value.panelId}：` : ''}{value.message}</p>)}</div></details></div>
  </div>;
}
