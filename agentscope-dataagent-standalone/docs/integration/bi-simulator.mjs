import { mountDataAgent } from './bi-login.mjs';

const SELECTED = 'dataagent:bi-simulator:selected-user';
const ENTRY_USER = 'dataagent:bi-simulator:entry-user';

export function normalizeUser(value) {
  const username = String(value ?? '').trim();
  if (!username || username.length > 128 || /[\u0000-\u001f\u007f]/.test(username))
    throw new Error('请输入有效的用户名（最多128个字符）。');
  return username;
}

export function validateConfig(value) {
  if (!value || typeof value.sharedKey !== 'string') throw new Error('缺少模拟入口配置。');
  let bytes;
  try { bytes = Uint8Array.from(atob(value.sharedKey), c => c.charCodeAt(0)); }
  catch { throw new Error('模拟入口密钥格式不正确，请检查外置配置。'); }
  if (bytes.length !== 32) throw new Error('请在外置配置中填写与后端一致的32字节Base64密钥。');
  const basePath = String(value.basePath ?? '/dataagent').replace(/\/+$/, '');
  if (basePath && !/^\/(?:[A-Za-z0-9_-]+\/)*[A-Za-z0-9_-]+$/.test(basePath))
    throw new Error('应用路径格式不正确。');
  const seen = new Set();
  const users = (Array.isArray(value.users) ? value.users : []).map(user => ({
    username: normalizeUser(user.username), label: String(user.label || user.username),
  })).filter(user => { if (seen.has(user.username)) return false; seen.add(user.username); return true; });
  if (!users.length) throw new Error('请至少配置一个测试用户。');
  return { sharedKey: value.sharedKey, basePath, users };
}

export function entryUsername({ reload, cached, selected, fallback }) {
  return normalizeUser((reload && cached) || selected || fallback);
}

async function boot() {
  const error = document.querySelector('#bi-error');
  const showError = message => { error.textContent = message; error.hidden = false; };
  try {
    if (!globalThis.isSecureContext || !globalThis.crypto?.subtle)
      throw new Error('请使用HTTPS或localhost访问此页面，以启用浏览器加密。');
    const response = await fetch(new URL('./bi-simulator.config.json', import.meta.url), { cache: 'no-store' });
    if (!response.ok) throw new Error('无法读取模拟入口配置，请检查本地服务或Nginx静态目录。');
    const config = validateConfig(await response.json());
    const select = document.querySelector('#bi-user');
    const options = [...config.users];
    const addOption = username => {
      if (!options.some(user => user.username === username)) options.push({ username, label: '自定义体验用户' });
      select.replaceChildren(...options.map(user => {
        const item = document.createElement('option'); item.value = user.username;
        item.textContent = `${user.label} · ${user.username}`; return item;
      }));
      select.value = username;
    };
    let selected = entryUsername({
      reload: document.body.dataset.page === 'entry' && performance.getEntriesByType('navigation')[0]?.type === 'reload',
      cached: sessionStorage.getItem(ENTRY_USER), selected: localStorage.getItem(SELECTED),
      fallback: config.users[0].username,
    });
    addOption(selected); select.disabled = false;
    if (document.body.dataset.page === 'portal') {
      document.querySelector('#bi-date').textContent = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(new Date());
      const launch = document.querySelector('#bi-launch'); launch.removeAttribute('aria-disabled');
      const choose = username => { selected = normalizeUser(username); addOption(selected); localStorage.setItem(SELECTED, selected); error.hidden = true; };
      choose(selected);
      select.addEventListener('change', () => choose(select.value));
      launch.addEventListener('click', () => localStorage.setItem(SELECTED, selected));
      document.querySelector('#bi-add-user').addEventListener('submit', event => {
        event.preventDefault();
        try { choose(document.querySelector('#bi-username').value); }
        catch (failure) { showError(failure.message); }
      });
      return;
    }
    const status = document.querySelector('#bi-status');
    const host = document.querySelector('#bi-frame-host');
    const reenter = document.querySelector('#bi-reenter'); reenter.disabled = false;
    let dispose = () => {};
    let expiryTimer;
    let frame;
    const mount = newEntry => {
      dispose(); window.clearTimeout(expiryTimer); error.hidden = true;
      sessionStorage.setItem(ENTRY_USER, selected);
      status.textContent = `正在以 ${selected} 进入…`;
      frame = document.createElement('iframe'); frame.title = '红海DataAgent';
      host.replaceChildren(frame);
      dispose = mountDataAgent({ iframe: frame, username: selected, sharedKey: config.sharedKey,
        basePath: config.basePath, newEntry, onError: message => { showError(message); status.textContent = '进入失败'; } });
    };
    const accepted = event => {
      if (event.origin !== location.origin || event.source !== frame?.contentWindow || event.data?.type !== 'dataagent:accepted') return;
      status.textContent = `已进入 · ${selected}`;
      try {
        const cached = JSON.parse(sessionStorage.getItem(`dataagent:bi-entry:${config.basePath}`) || 'null');
        if (cached?.expiresAt) expiryTimer = window.setTimeout(() => {
          status.textContent = '登录已过期，请重新进入';
        }, Math.max(0, cached.expiresAt - Date.now()));
      } catch { /* The app verifies its credential independently. */ }
    };
    window.addEventListener('message', accepted);
    select.addEventListener('change', () => { selected = select.value; localStorage.setItem(SELECTED, selected); mount(true); });
    reenter.addEventListener('click', () => mount(true));
    window.addEventListener('pagehide', () => { dispose(); window.clearTimeout(expiryTimer); window.removeEventListener('message', accepted); }, { once: true });
    mount(performance.getEntriesByType('navigation')[0]?.type !== 'reload');
  } catch (failure) { showError(failure instanceof Error ? failure.message : '入口初始化失败。'); }
}

if (typeof document !== 'undefined') void boot();
