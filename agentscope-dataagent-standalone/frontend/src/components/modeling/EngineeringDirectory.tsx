import { useState } from 'react';

export default function EngineeringDirectory({ paths, current, onSelect, labels }: {
  paths: readonly string[]; current?: string; onSelect: (path: string) => void; labels?: ReadonlyMap<string, string>;
}) {
  const [filter, setFilter] = useState('');
  const matches = paths.filter(path => (labels?.get(path) ?? path).toLowerCase().includes(filter.trim().toLowerCase()));
  return <aside className="eng-directory" aria-label={labels ? '示例目录' : '文件目录'}>
    <input type="search" className="da-input" aria-label={labels ? '搜索示例' : '搜索文件'} placeholder={labels ? '搜索示例' : '搜索文件'} value={filter} onChange={event => setFilter(event.target.value)} />
    <div className="am-file-list">{matches.length ? matches.map(path => {
      const slash = labels ? -1 : path.lastIndexOf('/');
      return <button key={path} type="button" aria-current={current === path} title={path} onClick={() => onSelect(path)}>
        <span><strong>{labels?.get(path) ?? path.slice(slash + 1)}</strong>{slash >= 0 && <small>{path.slice(0, slash)}</small>}</span>
      </button>;
    }) : <p className="am-empty">没有匹配的内容。</p>}</div>
  </aside>;
}
