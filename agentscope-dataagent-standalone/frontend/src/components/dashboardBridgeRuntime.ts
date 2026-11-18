/** Injected into the opaque generated frame; keep every dependency inside this function. */
export function dashboardBridgeRuntime(channel: string, editable: boolean) {
  let sequence = 0;
  const pending = new Map<number, { resolve: (value: unknown) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }>();
  const post = (data: object) => parent.postMessage({ ...data, channel }, '*');
  const validId = (id: string) => /^[a-zA-Z0-9_-]{1,80}$/.test(id);
  const registry = new Map<string, { title: string; state: string; generation?: number; timer?: ReturnType<typeof setTimeout> }>();
  const panelState = (id: string, title: string, state: string, error = '') => { registry.set(id, { ...registry.get(id), title, state }); post({ kind: 'panel-state', panelId: id, title: title.slice(0, 120), state, error: error.slice(0, 300) }); };
  function query(sql: string, options?: { panelId?: string }): Promise<unknown> {
    if (typeof sql !== 'string' || !sql.trim() || sql.length > 20000) return Promise.reject(new Error('查询SQL无效'));
    const id = ++sequence;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { pending.delete(id); reject(new Error('查询超时，请刷新重试')); }, 180000);
      pending.set(id, { resolve, reject, timer });
      post({ kind: 'query', id, sql, panelId: options?.panelId });
    });
  }
  function registerPanels(panels: { id: string; title: string }[]) {
    for (const item of panels.slice(0, 40)) {
      if (!validId(item.id) || registry.has(item.id)) continue;
      panelState(item.id, item.title, 'pending');
      const timer = setTimeout(() => { if (registry.get(item.id)?.state === 'pending') panelState(item.id, item.title, 'error', '面板未开始加载，请检查面板与渲染函数的绑定'); }, 15000);
      registry.set(item.id, { ...registry.get(item.id)!, timer });
    }
  }
  async function panel(options: { id: string; title: string; element: HTMLElement; kind?: 'chart' | 'metric' | 'table'; load: (context: { query: (sql: string) => Promise<unknown> }) => Promise<unknown>; render: (data: any, element: HTMLElement) => unknown }) {
    const { id, title, element } = options;
    if (!validId(id)) throw new Error('面板ID仅支持80位以内的字母、数字、下划线及短横线');
    clearTimeout(registry.get(id)?.timer);
    if (!(element instanceof HTMLElement)) { panelState(id, title, 'error', '找不到面板容器'); return; }
    const generation = (registry.get(id)?.generation || 0) + 1;
    registry.set(id, { ...registry.get(id), title, state: 'loading', generation });
    const current = () => registry.get(id)?.generation === generation;
    element.querySelector('[data-dataagent-status]')?.remove();
    element.dataset.panelId = id;
    if (getComputedStyle(element).position === 'static') element.style.position = 'relative';
    let overlay: HTMLDivElement | null = null;
    let select: HTMLButtonElement | null = null;
    const status = (message: string, retry = false) => {
      overlay?.remove(); overlay = document.createElement('div'); overlay.dataset.dataagentStatus = 'true';
      Object.assign(overlay.style, { position: 'absolute', inset: '0', zIndex: '8', display: 'flex', alignItems: 'center', justifyContent: 'center', flexDirection: 'column', gap: '10px', padding: '14px', boxSizing: 'border-box', background: '#fafbfeee', color: '#657086', font: '13px system-ui', textAlign: 'center', borderRadius: 'inherit' });
      const label = document.createElement('span'); label.textContent = message; overlay.append(label);
      if (retry) { const button = document.createElement('button'); button.type = 'button'; button.textContent = '重新取数'; Object.assign(button.style, { padding: '6px 12px', border: '1px solid #ddd8ee', borderRadius: '8px', background: 'white', color: '#6551c7', cursor: 'pointer' }); button.onclick = () => { void run(); }; overlay.append(button); }
      element.append(overlay);
    };
    let running = false;
    const run = async () => {
      if (running || !current()) return; running = true; panelState(id, title, 'loading'); status('正在加载…');
      try {
        const data = await options.load({ query: sql => query(sql, { panelId: id }) });
        if (!current()) return;
        if (data && typeof data === 'object' && Array.isArray((data as { rows?: unknown[] }).rows) && !(data as { rows: unknown[] }).rows.length) { status('当前条件下暂无数据'); panelState(id, title, 'empty'); return; }
        overlay?.remove(); await options.render(data, element);
        await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
        if (!current()) return;
        if (options.kind === 'chart' && (!element.clientWidth || !element.clientHeight || !element.querySelector('canvas,svg'))) throw new Error('图表未完成渲染，请检查容器尺寸与ECharts初始化');
        panelState(id, title, 'ready');
      } catch (error) { if (current()) { const message = error instanceof Error ? error.message : String(error); status(message, true); panelState(id, title, 'error', message); } }
      finally { running = false; if (current() && select) element.append(select); }
    };
    if (editable) {
      element.querySelector('[data-dataagent-select]')?.remove();
      select = document.createElement('button'); select.dataset.dataagentSelect = 'true'; select.type = 'button'; select.textContent = '修改此面板'; select.setAttribute('aria-label', `修改面板：${title}`);
      Object.assign(select.style, { position: 'absolute', right: '6px', top: '6px', zIndex: '9', padding: '4px 7px', border: '1px solid #ded8f3', borderRadius: '7px', background: '#ffffffec', color: '#6d55c8', font: '11px system-ui', cursor: 'pointer' });
      select.onclick = event => { event.stopPropagation(); post({ kind: 'panel-select', panelId: id, title: title.slice(0, 120) }); };
      element.append(select);
    }
    await run();
  }
  Object.defineProperty(window, 'dataagent', { value: Object.freeze({ query, panel, registerPanels }), writable: false, configurable: false });
  addEventListener('message', event => {
    if (event.source !== parent || event.data?.channel !== channel) return;
    if (event.data.kind === 'capture') { void capture(); return; }
    if (event.data.kind !== 'result') return;
    const task = pending.get(event.data.id as number); if (!task) return;
    pending.delete(event.data.id as number); clearTimeout(task.timer);
    if (event.data.error) task.reject(new Error(String(event.data.error))); else task.resolve(event.data.data);
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
      post({ kind: 'thumbnail', image: canvas.toDataURL('image/png') });
    } catch { /* An unexportable chart has no cover. */ }
  }
  const fail = (error: unknown) => post({ kind: 'runtime-error', error: String(error).slice(0, 300) });
  addEventListener('error', event => fail(event.message));
  addEventListener('unhandledrejection', event => fail(event.reason));
}
