import { useMemo, useState } from 'react';
import type { MdlPreview } from '../../api/semanticModeling';
import ReadableCode from '../ReadableCode';
import EngineeringDirectory from './EngineeringDirectory';
import type { EngineeringVersion } from './EngineeringDialog';

const order = (path: string) => path.endsWith('/.gitkeep') ? 9 : path.startsWith('models/') ? 0 : path === 'relationships.yml' ? 1 : path.startsWith('views/') ? 2 : path.startsWith('cubes/') ? 3 : 4;
export const fileLanguage = (path: string) => path.endsWith('.sql') ? 'sql' : path.endsWith('.json') ? 'json' : /\.ya?ml$/.test(path) ? 'yaml' : 'text';
const visible = (path: string) => /^(models|views|cubes|knowledge)\//.test(path) || path === 'relationships.yml';

export default function ModelSnapshotPanel({ preview, version, compare, initialPath, initialDirectory, showChangedPath = false }: {
  preview: MdlPreview | null; version: EngineeringVersion; compare: boolean; initialPath?: string; initialDirectory?: string; showChangedPath?: boolean;
}) {
  const [selected, setSelected] = useState('');
  const draft = useMemo(() => new Map(preview?.files.map(file => [file.path, file.content])), [preview?.files]);
  const published = useMemo(() => new Map(preview?.publishedFiles.map(file => [file.path, file.content])), [preview?.publishedFiles]);
  const current = version === 'draft' ? draft : published;
  const paths = (compare ? [...new Set([...draft.keys(), ...published.keys()])] : [...current.keys()])
    .filter(path => visible(path) || (showChangedPath && path === initialPath))
    .sort((a, b) => order(a) - order(b) || a.localeCompare(b, 'zh-CN'));
  if (showChangedPath && compare && initialPath && !selected && !paths.includes(initialPath)) {
    return <p className="am-empty" role="status">{preview ? `当前快照中找不到 ${initialPath}。` : '正在读取工程文件…'}</p>;
  }
  const path = paths.find(candidate => candidate === (selected || initialPath))
    ?? paths.find(candidate => candidate.startsWith(initialDirectory || 'models/') && candidate.endsWith('/metadata.yml'))
    ?? paths[0];
  if (!path) return <p className="am-empty" role="status">{preview ? '当前版本暂无工程文件。' : '正在读取工程文件…'}</p>;
  const old = published.get(path);
  const next = draft.get(path);
  const language = fileLanguage(path);
  return <div className="eng-browser">
    <EngineeringDirectory paths={paths} current={path} onSelect={setSelected} />
    <section className="eng-document">{compare ? <>
      <div className="eng-filebar"><strong title={path}>{path}</strong><span className="da-small">{old === next ? '两版内容相同' : '两版内容有差异'}</span></div>
      <div className="eng-compare">
        <div className="eng-document">{old === undefined ? <p className="am-empty">线上版本没有此文件。</p> : <ReadableCode fill language={language} text={old} title={`线上 v${preview?.mdlVersion}`} />}</div>
        <div className="eng-document">{next === undefined ? <p className="am-empty">当前草稿没有此文件。</p> : <ReadableCode fill language={language} text={next} title="当前草稿" />}</div>
      </div>
    </> : <ReadableCode key={`${version}:${path}`} fill language={language} text={current.get(path) ?? ''} title={path} />}</section>
  </div>;
}
