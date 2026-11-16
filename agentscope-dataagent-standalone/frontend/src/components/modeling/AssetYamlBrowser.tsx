import { useEffect, useRef, useState } from 'react';
import Icon from '../Icon';
import ReadableCode from '../ReadableCode';
import { readWorkspaceFile } from '../../api/semanticModeling';

/**
 * specs/030 (ADR 0041): the shared "asset list + YAML viewer" two-column browser used by the
 * models / cubes / views asset tabs. The workspace YAML is the single source of truth — the UI
 * is a projection of it — so selecting an entry streams its backing file straight from
 * `GET /workspace/file`. `dirty` entries carry the row-level "未发布" marker until publish.
 */
export interface AssetEntry {
  /** Unique entry key inside this browser (also the &selected= deep-link value). */
  key: string;
  title: string;
  subtitle?: string;
  /** Workspace-relative YAML path; null renders the fallback note instead of a file. */
  file: string | null;
  dirty?: boolean;
}

interface Props {
  groupId: string;
  entries: AssetEntry[];
  selectedKey: string | null;
  onSelect: (key: string) => void;
  emptyText: string;
  /** Shown when the selected entry has no backing file (e.g. unsynced workspace). */
  fallbackText?: string;
}

export default function AssetYamlBrowser({
  groupId,
  entries,
  selectedKey,
  onSelect,
  emptyText,
  fallbackText = '该资产尚未同步到 wren 工程工作区（发布或对话建模后生成）。',
}: Props) {
  const [content, setContent] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  /** In-memory cache keyed by groupId + path; workspace changes re-fetch via cache bust. */
  const cacheRef = useRef(new Map<string, string>());
  /** Bumped on entry re-click to force a refresh of the cached file. */
  const [bust, setBust] = useState(0);

  const selected = entries.find(e => e.key === selectedKey) ?? null;

  useEffect(() => {
    const file = selected?.file ?? null;
    if (!file) {
      setContent(null);
      setError(null);
      return;
    }
    const cacheKey = `${groupId}::${file}`;
    const cached = cacheRef.current.get(cacheKey);
    if (cached !== undefined && bust === 0) {
      setContent(cached);
      setError(null);
      return;
    }
    let alive = true;
    setContent(null);
    setError(null);
    readWorkspaceFile(groupId, file)
      .then(f => {
        if (!alive) return;
        cacheRef.current.set(cacheKey, f.content);
        setContent(f.content);
        setError(null);
      })
      .catch(e => {
        if (alive) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      alive = false;
    };
  }, [groupId, selected?.file, bust]);

  if (entries.length === 0) {
    return <p className="am-empty">{emptyText}</p>;
  }

  return (
    <div className="am-browser">
      <nav className="am-file-list" aria-label="资产文件"><div className="am-list-caption">{entries.length} 个条目 · 点击已选条目可刷新</div>
        {entries.map(e => {
          const on = e.key === selectedKey;
          return (
            <button
              key={e.key}
              type="button" aria-current={on ? 'true' : undefined}
              onClick={() => {
                if (on) setBust(b => b + 1); // re-click refreshes from the workspace
                onSelect(e.key);
              }}
            >
              <Icon name="file" size="sm" /><span><strong>{e.title}</strong>{e.subtitle && <small title={e.subtitle}>{e.subtitle}</small>}{e.dirty && <em className="am-dirty">未发布</em>}</span>
            </button>
          );
        })}
      </nav>
      <div className="am-viewer">
        {!selected ? (
          <div className="am-empty">
            {emptyText}
          </div>
        ) : !selected.file ? (
          <div className="am-empty">
            {fallbackText}
          </div>
        ) : error ? (
          <div className="am-error" role="alert">
            <span>{error}</span><button type="button" className="da-btn" onClick={() => setBust(b => b + 1)}>重试</button>
          </div>
        ) : content === null ? (
          <div className="am-empty" role="status">
            加载中…
          </div>
        ) : !content.trim() ? <p className="am-empty">（空文件）</p> : (
          <ReadableCode key={selected.file} text={content} language={selected.file.endsWith('.sql') ? 'sql' : selected.file.endsWith('.json') ? 'json' : 'yaml'} title={selected.file} />
        )}
      </div>
    </div>
  );
}
