import { useMemo, useState } from 'react';
import type { MdlPreview } from '../../api/semanticModeling';
import { diffLines } from '../../utils/diff';
import ReadableCode from '../ReadableCode';
import EngineeringDirectory from './EngineeringDirectory';
import type { EngineeringVersion } from './EngineeringDialog';
import Icon from '../Icon';

const order = (path: string) => path.startsWith('models/') ? 0 : path === 'relationships.yml' ? 1 : path.startsWith('views/') ? 2 : path.startsWith('cubes/') ? 3 : 4;
export const fileLanguage = (path: string) => path.endsWith('.sql') ? 'sql' : path.endsWith('.json') ? 'json' : /\.ya?ml$/.test(path) ? 'yaml' : 'text';
const visible = (path: string) => !path.endsWith('/.gitkeep') && (/^(models|views|cubes|knowledge)\//.test(path) || path === 'relationships.yml');
type EngineeringFile = { draft?: string; published?: string; status: 'added' | 'removed' | 'modified' | 'unchanged' };
const labels = { added: '新增', removed: '删除', modified: '修改', unchanged: '无改动' };

export function engineeringFiles(preview: MdlPreview | null): Map<string, EngineeringFile> {
  const draft = new Map(preview?.files.map(file => [file.path, file.content]));
  const published = new Map(preview?.publishedFiles.map(file => [file.path, file.content]));
  return new Map([...new Set([...draft.keys(), ...published.keys()])].filter(visible)
    .sort((a, b) => order(a) - order(b) || a.localeCompare(b, 'zh-CN'))
    .map(path => {
      const next = draft.get(path);
      const old = published.get(path);
      return [path, { draft: next, published: old,
        status: next === old ? 'unchanged' : old === undefined ? 'added' : next === undefined ? 'removed' : 'modified' }];
    }));
}

function ComparisonCode({ text, title, changed, type }: { text?: string; title: string; changed: ReadonlySet<number>; type: 'add' | 'del' }) {
  const lines = text?.split('\n') ?? [];
  return <section className="eng-document" aria-label={title}>
    <h3>{title}</h3>
    {text === undefined ? <p className="am-empty">这个版本没有此文件。</p>
      : <div className="rc-scroll" tabIndex={0} role="region" aria-label={title + '代码，可滚动'}>
        <div className="rc-code"><pre className="rc-gutter" aria-hidden="true">{lines.map((_, i) => i + 1).join('\n')}</pre>
          <pre className="rc-pre"><code>{lines.map((line, index) => <span key={index} data-diff={changed.has(index) ? type : undefined}>{line}</span>)}</code></pre></div>
      </div>}
  </section>;
}

export default function ModelSnapshotPanel({ files, loading, version, compare, onlyChanged, initialPath, initialDirectory, publishedLabel, onSelect }: {
  files: ReadonlyMap<string, EngineeringFile>; loading: boolean; version: EngineeringVersion; compare: boolean; onlyChanged: boolean;
  initialPath?: string; initialDirectory?: string; publishedLabel: string; onSelect: (path: string) => void;
}) {
  const [copyState, setCopyState] = useState<{ path: string; message: string }>();
  const paths = [...files].filter(([, file]) => (!onlyChanged || file.status !== 'unchanged')
    && (compare || onlyChanged || file[version] !== undefined)).map(([path]) => path);
  const path = (initialPath && paths.includes(initialPath) ? initialPath : undefined)
    ?? paths.find(candidate => candidate.startsWith(initialDirectory ?? 'models/') && candidate.endsWith('/metadata.yml')) ?? paths[0];
  const file = path ? files.get(path) : undefined;
  const status = new Map(paths.flatMap(path => {
    const type = compare || version === 'draft' || onlyChanged ? files.get(path)?.status : undefined;
    return type && type !== 'unchanged' ? [[path, labels[type]] as const] : [];
  }));
  const changes = useMemo(() => {
    const oldChanged = new Set<number>(), newChanged = new Set<number>();
    if (!compare || !file || file.status === 'unchanged') return { oldChanged, newChanged };
    const old = file.published ?? '', next = file.draft ?? '';
    const left = old.split('\n'), right = next.split('\n');
    if (left.length * right.length > 1_000_000) {
      left.forEach((line, index) => { if (line !== right[index]) oldChanged.add(index); });
      right.forEach((line, index) => { if (line !== left[index]) newChanged.add(index); });
    } else {
      let a = 0, b = 0;
      for (const line of diffLines(old, next)) {
        if (line.type === 'del') oldChanged.add(a++);
        else if (line.type === 'add') newChanged.add(b++);
        else { a++; b++; }
      }
    }
    return { oldChanged, newChanged };
  }, [compare, file]);
  const versionText = compare ? publishedLabel + '与当前草稿' : version === 'draft' ? '当前草稿' : publishedLabel;
  const badge = path && status.get(path) && <span className="eng-status">{status.get(path)}</span>;

  return <div className="eng-browser">
    <EngineeringDirectory paths={paths} current={path} onSelect={onSelect} statuses={status} />
    <section className="eng-document" aria-label={versionText}>
      {loading ? <p className="am-empty" role="status">正在读取工程文件…</p> : !path || !file
        ? <p className="am-empty">{onlyChanged ? '没有改动的工程文件。' : '当前版本暂无工程文件。'}</p>
        : compare ? <>
          <div className="eng-filebar"><strong title={path}>{path}</strong>{badge}
            <button className="da-btn da-btn-sm eng-copy" type="button" disabled={file.draft === undefined && file.published === undefined}
              aria-label="复制工程文件" onClick={async () => {
                try { await navigator.clipboard.writeText(file.draft ?? file.published ?? ''); setCopyState({ path, message: '已复制' }); }
                catch { setCopyState({ path, message: '复制失败' }); }
              }}><Icon name="copy" size="sm" /></button>{copyState?.path === path && <small role="status">{copyState.message}</small>}</div>
          <div className="eng-compare">
            <ComparisonCode text={file.published} title={publishedLabel} changed={changes.oldChanged} type="del" />
            <ComparisonCode text={file.draft} title="当前草稿" changed={changes.newChanged} type="add" />
          </div>
        </> : file[version] !== undefined ? <ReadableCode key={version + ':' + path} fill language={fileLanguage(path)} text={file[version] ?? ''} title={path}
          header={<><strong title={path}>{path}</strong>{badge}</>} />
        : <div className="eng-filebar"><strong title={path}>{path}</strong>{badge}<span>这个版本没有此文件。</span></div>}
    </section>
  </div>;
}
