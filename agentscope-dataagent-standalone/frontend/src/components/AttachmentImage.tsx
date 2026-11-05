import React, { useEffect, useState } from 'react';
import { fetchFile, fileUrl } from '../api/artifacts';
import { acquireImage, cachedImageUrl } from '../api/imageCache';

function acquirePreview(path: string) {
  if (path.startsWith('/api/artifacts/')) {
    return acquireImage(path, signal => fetchFile(path, signal).then(r => r.blob()));
  }
  // Workspace files can change in place; reload them when a new view opens.
  const controller = new AbortController();
  let objectUrl: string | undefined;
  return {
    promise: fetchFile(path, controller.signal).then(r => r.blob()).then(blob => {
      if (controller.signal.aborted) throw new DOMException('Image load cancelled', 'AbortError');
      objectUrl = URL.createObjectURL(blob);
      return objectUrl;
    }),
    release() {
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    },
  };
}

export default function AttachmentImage({ src = '', alt = '', ...props }: React.ImgHTMLAttributes<HTMLImageElement>) {
  const protectedUrl = fileUrl(src);
  const [loaded, setLoaded] = useState<{ path: string; url: string } | null>(null);
  const [failure, setFailure] = useState<{ path: string; message: string } | null>(null);
  useEffect(() => {
    if (!protectedUrl) return;
    let active = true;
    const image = acquirePreview(protectedUrl);
    setFailure(null);
    image.promise.then(url => {
      if (active) setLoaded({ path: protectedUrl, url });
    }).catch(error => {
      if (active) setFailure({ path: protectedUrl, message: error.message });
    });
    return () => { active = false; image.release(); };
  }, [protectedUrl]);
  if (protectedUrl && failure?.path === protectedUrl)
    return <span role="status" title={failure.message}>{alt || '图片'}：{failure.message}</span>;
  const url = protectedUrl ? (cachedImageUrl(protectedUrl) ?? (loaded?.path === protectedUrl ? loaded.url : undefined)) : src;
  if (!url) return <span role="status">图片加载中…</span>;
  return <img {...props} src={url} alt={alt} />;
}
