import { useState } from 'react';
import type { MdlPreview } from '../../api/semanticModeling';

export default function ModelSnapshotPanel({ preview }: { preview: MdlPreview | null }) {
  const [version, setVersion] = useState<'draft' | 'published'>('draft');
  const [selected, setSelected] = useState('');
  const files = ((version === 'draft' ? preview?.files : preview?.publishedFiles) ?? [])
    .filter(f => /^(models|views|cubes|knowledge)\//.test(f.path) || f.path === 'relationships.yml');
  const file = files.find(f => f.path === selected)
    ?? files.find(f => f.path.startsWith('models/') && f.path.endsWith('metadata.yml')) ?? files[0];
  const previous = preview?.publishedFiles.find(f => f.path === file?.path);
  return <section className="da-card">
    <h3>工程定义与发布快照</h3>
    <p className="da-small">这里展示工程 YAML；草稿用于建模验证，已发布版本用于问数。</p>
    <select className="da-input" aria-label="工程版本" value={version} onChange={e => setVersion(e.target.value as 'draft' | 'published')}>
      <option value="draft">当前草稿</option><option value="published">已发布 v{preview?.mdlVersion ?? 0}</option>
    </select>
    <select className="da-input" aria-label="工程文件" value={file?.path ?? ''} onChange={e => setSelected(e.target.value)} style={{ marginLeft: 8, maxWidth: '100%' }}>
      {!files.length && <option value="">{preview ? '暂无文件' : '正在读取文件…'}</option>}
      {files.map(f => <option key={f.path} value={f.path}>{f.path}</option>)}
    </select>
    {file && <><pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', maxHeight: 420, overflowY: 'auto' }}>{file.content}</pre>
      {version === 'draft' && previous?.content !== file.content && <details><summary>查看此文件的已发布定义（有草稿差异）</summary>
        <pre style={{ whiteSpace: 'pre-wrap' }}>{previous?.content ?? '此文件尚未发布'}</pre>
      </details>}</>}
  </section>;
}
