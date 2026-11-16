import { useState } from 'react';
import type { MdlPreview } from '../../api/semanticModeling';
import Icon from '../Icon';
import ReadableCode from '../ReadableCode';

const fileOrder = (path: string) => path.endsWith('/.gitkeep') ? 9 : path.startsWith('models/') ? 0 : path === 'relationships.yml' ? 1 : path.startsWith('views/') ? 2 : path.startsWith('cubes/') ? 3 : 4;

export default function ModelSnapshotPanel({ preview }: { preview: MdlPreview | null }) {
  const [version, setVersion] = useState<'draft' | 'published'>('draft');
  const [selected, setSelected] = useState('');
  const files = ((version === 'draft' ? preview?.files : preview?.publishedFiles) ?? [])
    .filter(f => /^(models|views|cubes|knowledge)\//.test(f.path) || f.path === 'relationships.yml')
    .sort((a, b) => fileOrder(a.path) - fileOrder(b.path) || a.path.localeCompare(b.path, 'zh-CN'));
  const file = files.find(f => f.path === selected)
    ?? files.find(f => f.path.startsWith('models/') && f.path.endsWith('metadata.yml')) ?? files[0];
  const previous = preview?.publishedFiles.find(f => f.path === file?.path);
  return <section className="am-section">
    <header className="am-section-head"><div><h3>工程定义与发布快照</h3><p>草稿用于建模验证，已发布版本用于问数。这里仅查看工程原文。</p></div>
    <label className="am-version">工程版本<select className="da-input" aria-label="工程版本" value={version} onChange={e => setVersion(e.target.value as 'draft' | 'published')}>
      <option value="draft">当前草稿</option><option value="published">已发布 v{preview?.mdlVersion ?? 0}</option>
    </select></label></header>
    {!files.length ? <p className="am-empty" role="status">{preview ? version === 'published' && !preview.mdlVersion ? '还没有已发布的工程快照。' : '暂无工程文件。' : '正在读取工程文件…'}</p> : <div className="am-browser"><nav className="am-file-list" aria-label="工程文件"><div className="am-list-caption">{files.length} 个文件</div>{files.map(f => <button type="button" key={f.path} aria-current={file?.path === f.path ? 'true' : undefined} title={f.path} onClick={() => setSelected(f.path)}><Icon name="file" size="sm" /><span><strong>{f.path.split('/').slice(-1)[0]}</strong><small>{f.path.split('/').slice(0, -1).join('/') || '工程根目录'}</small></span></button>)}</nav><div className="am-viewer">
      {file && <><ReadableCode key={`${version}:${file.path}`} text={file.content} language={file.path.endsWith('.sql') ? 'sql' : file.path.endsWith('.json') ? 'json' : 'yaml'} title={file.path} />
      {version === 'draft' && previous?.content !== file.content && <details className="am-compare"><summary><Icon name="code" size="sm" /><strong>查看已发布定义</strong><span>此文件有草稿差异</span><Icon name="chevron" size="sm" /></summary>
        {previous ? <ReadableCode text={previous.content} title={previous.path} language={previous.path.endsWith('.sql') ? 'sql' : previous.path.endsWith('.json') ? 'json' : 'yaml'} /> : <p className="am-empty">此文件尚未发布。</p>}
      </details>}</>}
    </div></div>}
  </section>;
}
