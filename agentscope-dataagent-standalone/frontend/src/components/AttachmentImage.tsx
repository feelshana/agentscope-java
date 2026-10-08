import React, { useEffect, useState } from 'react';
import { fetchFile, fileUrl } from '../api/artifacts';

export default function AttachmentImage({ src = '', alt = '', ...props }: React.ImgHTMLAttributes<HTMLImageElement>) {
  const protectedUrl = fileUrl(src);
  const [loaded, setLoaded] = useState<{ path: string; url: string } | null>(null);
  const [failure, setFailure] = useState<{ path: string; message: string } | null>(null);
  useEffect(() => {
    if (!protectedUrl) return;
    const controller = new AbortController();
    let objectUrl: string | undefined;
    fetchFile(protectedUrl, controller.signal).then(r => r.blob()).then(blob => {
      if (controller.signal.aborted) return;
      objectUrl = URL.createObjectURL(blob);
      setLoaded({ path: protectedUrl, url: objectUrl });
    }).catch(error => {
      if (!controller.signal.aborted) setFailure({ path: protectedUrl, message: error.message });
    });
    return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [protectedUrl]);
  if (protectedUrl && failure?.path === protectedUrl)
    return <span role="status" title={failure.message}>{alt || '图片'}：{failure.message}</span>;
  const url = protectedUrl ? (loaded?.path === protectedUrl ? loaded.url : undefined) : src;
  if (!url) return <span role="status">图片加载中…</span>;
  return <img {...props} src={url} alt={alt} />;
}
