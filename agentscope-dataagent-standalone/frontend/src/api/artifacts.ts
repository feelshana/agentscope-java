import { getToken } from './auth';
import { ACTIVE_AGENT_ID } from './activeAgent';

/** Only attach credentials to the two local file endpoints. */
export function fileUrl(path: string): string | null {
  if (path.startsWith('/workspace/'))
    return `/api/agents/${ACTIVE_AGENT_ID}/workspace/file/binary?path=${encodeURIComponent(path)}`;
  if (!path.startsWith('/') || path.startsWith('//')) return null;
  const url = new URL(path, window.location.origin);
  if (!/^\/api\/artifacts\/[0-9a-f-]{36}\/content$/.test(url.pathname)
      && !/^\/api\/agents\/[^/]+\/workspace\/file\/binary$/.test(url.pathname)) return null;
  url.searchParams.delete('token');
  return url.pathname + url.search;
}

export async function fetchFile(path: string, signal?: AbortSignal): Promise<Response> {
  const url = fileUrl(path);
  if (!url) throw new Error('不支持的附件地址');
  const token = getToken();
  if (!token) throw new Error('请先登录后再访问附件');
  const response = await fetch(url, {
    headers: { Authorization: `Bearer ${token}` }, signal, cache: 'no-store', redirect: 'error',
  });
  if (!response.ok) {
    if (response.status === 401) throw new Error('登录已过期，请重新登录');
    if ([403, 404, 410].includes(response.status)) throw new Error('附件不存在、已删除或无访问权限');
    throw new Error('附件读取失败，请稍后重试');
  }
  return response;
}

export function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = name.replace(/[\\/\x00-\x1f]/g, '_');
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
}

export async function downloadFile(path: string, name?: string) {
  const response = await fetchFile(path);
  const disposition = response.headers.get('Content-Disposition') ?? '';
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const plain = disposition.match(/filename="([^"]+)"/i)?.[1];
  let filename = name ?? plain ?? '附件';
  if (!name && encoded) {
    try { filename = decodeURIComponent(encoded); } catch { /* use fallback */ }
  }
  saveBlob(await response.blob(), filename);
}
