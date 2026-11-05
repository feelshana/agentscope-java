interface ImageEntry {
  promise: Promise<string>;
  controller: AbortController;
  references: number;
  bytes: number;
  url?: string;
}

const entries = new Map<string, ImageEntry>();
const MAX_IMAGES = 32;
const MAX_BYTES = 24 * 1024 * 1024;

function discard(path: string, entry: ImageEntry) {
  if (entries.get(path) !== entry) return;
  entries.delete(path);
  entry.controller.abort();
  if (entry.url) URL.revokeObjectURL(entry.url);
}

function trim() {
  let bytes = [...entries.values()].reduce((sum, entry) => sum + entry.bytes, 0);
  for (const [path, entry] of entries) {
    if (entries.size <= MAX_IMAGES && bytes <= MAX_BYTES) break;
    // Images currently displayed must keep their object URLs until released.
    if (entry.references > 0) continue;
    bytes -= entry.bytes;
    discard(path, entry);
  }
}

export function cachedImageUrl(path: string): string | undefined {
  return entries.get(path)?.url;
}

/** Share downloads and object URLs within the current browser login. */
export function acquireImage(path: string, load: (signal: AbortSignal) => Promise<Blob>) {
  let entry = entries.get(path);
  if (!entry) {
    const controller = new AbortController();
    const created: ImageEntry = {
      controller,
      references: 0,
      bytes: 0,
      promise: Promise.resolve().then(async () => {
        const blob = await load(controller.signal);
        if (controller.signal.aborted) throw new DOMException('Image load cancelled', 'AbortError');
        created.url = URL.createObjectURL(blob);
        created.bytes = blob.size;
        trim();
        return created.url;
      }).catch(error => {
        discard(path, created);
        throw error;
      }),
    };
    entry = created;
  }
  entries.delete(path);
  entries.set(path, entry);
  entry.references++;
  trim();
  let released = false;
  return {
    promise: entry.promise,
    release() {
      if (released) return;
      released = true;
      entry.references--;
      trim();
    },
  };
}

/** A credential change must discard downloaded files and pending requests. */
export function clearImageCache() {
  for (const [path, entry] of entries) discard(path, entry);
}
