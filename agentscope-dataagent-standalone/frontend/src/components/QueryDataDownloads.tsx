import { useState } from 'react';
import { downloadFile, fileUrl } from '../api/artifacts';
import Icon from './Icon';
import type { QueryDataFile } from './answerRefs';

export default function QueryDataDownloads({ files }: { files: QueryDataFile[] }) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  if (files.length === 0) return null;
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8, marginTop: 10 }}>
      {files.map((f, i) => {
        const url = fileUrl(f.href);
        if (!url) return null;
        return <button key={f.href} type="button" className="da-btn da-btn-sm" disabled={busy !== null}
          onClick={() => {
            setBusy(f.href); setError(null);
            void downloadFile(url).catch(e => setError(e instanceof Error ? e.message : '下载失败，请重试')).finally(() => setBusy(null));
          }}>
          <Icon name="table" size="sm" />
          {busy === f.href ? '下载中…' : `下载查询数据${files.length > 1 ? ` ${i + 1}` : ''}${f.rows ? `（${f.rows} 行）` : ''}`}
        </button>;
      })}
      {error && <span role="alert" style={{ fontSize: 12, color: 'var(--da-warn)' }}>{error}</span>}
    </div>
  );
}
