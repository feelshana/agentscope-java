import { getToken } from '../api/auth';
import { dashboardThumbnailValid } from './dashboardState';

/** Bounded, login-scoped, memory-only previews; never run dashboard queries from the library. */
const images = new Map<string, string>();
let credential: string | null = null;
function currentLogin() {
  const next = getToken();
  if (next !== credential) { images.clear(); credential = next; }
  return Boolean(next);
}

export function dashboardThumbnail(id: string, revision: string | null): string | undefined {
  return currentLogin() && revision ? images.get(`${id}:${revision}`) : undefined;
}

export function rememberDashboardThumbnail(id: string, revision: string, image: string) {
  if (!currentLogin() || !revision || !dashboardThumbnailValid(image)) return;
  images.set(`${id}:${revision}`, image);
  while (images.size > 18) images.delete(images.keys().next().value!);
}

export function forgetDashboardThumbnails(id: string) {
  for (const key of images.keys()) if (key.startsWith(`${id}:`)) images.delete(key);
}
