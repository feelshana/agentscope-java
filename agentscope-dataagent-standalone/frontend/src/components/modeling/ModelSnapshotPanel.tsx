import { useEffect, useState } from 'react';
import { getMdlPreview } from '../../api/semanticModeling';
import type { MdlPreview } from '../../api/semanticModeling';

export default function ModelSnapshotPanel({ groupId }: { groupId: string }) {
  const [preview, setPreview] = useState<MdlPreview | null>(null);
  const [error, setError] = useState('');
  const [version, setVersion] = useState<'draft' | 'published'>('draft');
  const [selected, setSelected] = useState('');
  useEffect(() => {
    let cancelled = false;
    setPreview(null); setError(''); setSelected('');
    const refresh = () => { void getMdlPreview(groupId).then(value => {
      if (!cancelled) setPreview(value);
    }).catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : '读取失败'); }); };
    refresh();
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) refresh();
    };
    window.addEventListener('modeling:updated', update);
    return () => { cancelled = true; window.removeEventListener('modeling:updated', update); };
  }, [groupId]);
  const files = ((version === 'draft' ? preview?.files : preview?.publishedFiles) ?? [])
    .filter(f => /^(models|views|knowledge)\//.test(f.path) || f.path === 'relationships.yml');
  const file = files.find(f => f.path === selected)
    ?? files.find(f => f.path.startsWith('models/') && f.path.endsWith('metadata.yml')) ?? files[0];
  const previous = preview?.publishedFiles.find(f => f.path === file?.path);
  return <section className="da-card">
    <h3>工程定义与发布快照</h3>
    <p className="da-small">上方为当前草稿的 MDL 结构可视化。下方展示工程 YAML；草稿用于建模验证，已发布版本用于问数。</p>
    <select className="da-input" aria-label="工程版本" value={version} onChange={e => setVersion(e.target.value as 'draft' | 'published')}>
      <option value="draft">当前草稿</option><option value="published">已发布 v{preview?.mdlVersion ?? 0}</option>
    </select>
    {error && <p role="alert">{error}</p>}
    <select className="da-input" aria-label="工程文件" value={file?.path ?? ''} onChange={e => setSelected(e.target.value)} style={{ marginLeft: 8, maxWidth: '100%' }}>
      {!files.length && <option value="">暂无文件</option>}
      {files.map(f => <option key={f.path} value={f.path}>{f.path}</option>)}
    </select>
    {file && <><pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', maxHeight: 420, overflowY: 'auto' }}>{file.content}</pre>
      {version === 'draft' && previous?.content !== file.content && <details><summary>查看此文件的已发布定义（有草稿差异）</summary>
        <pre style={{ whiteSpace: 'pre-wrap' }}>{previous?.content ?? '此文件尚未发布'}</pre>
      </details>}</>}
  </section>;
}
