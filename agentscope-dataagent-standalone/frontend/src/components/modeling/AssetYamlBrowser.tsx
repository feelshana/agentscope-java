import { useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import YamlContent from './YamlContent';
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

const S: Record<string, CSSProperties> = {
  wrap: { display: 'flex', gap: 12, alignItems: 'stretch', minHeight: 260 },
  list: {
    width: 236,
    flexShrink: 0,
    border: '1px solid var(--da-border)',
    borderRadius: 8,
    background: 'var(--da-surface-sunken, #f7f7f8)',
    padding: 6,
    display: 'flex',
    flexDirection: 'column',
    gap: 2,
    overflowY: 'auto',
    maxHeight: 480,
  },
  item: {
    border: 'none',
    background: 'transparent',
    textAlign: 'left',
    cursor: 'pointer',
    borderRadius: 6,
    padding: '7px 9px',
    display: 'block',
    width: '100%',
  },
  itemOn: { background: 'var(--da-primary-subtle, #eef2ff)' },
  title: { fontWeight: 600, fontSize: '0.85rem', color: 'var(--da-text)', display: 'flex', alignItems: 'center', gap: 6 },
  subtitle: { fontSize: '0.72rem', color: 'var(--da-text-3)', marginTop: 1 },
  dirtyDot: {
    width: 6,
    height: 6,
    borderRadius: '50%',
    background: 'var(--da-warn)',
    flexShrink: 0,
  },
  dirtyBadge: {
    fontSize: '0.66rem',
    color: 'var(--da-warn)',
    border: '1px solid var(--da-warn)',
    borderRadius: 8,
    padding: '0 5px',
    fontWeight: 400,
  },
  viewer: {
    flex: 1,
    minWidth: 0,
    border: '1px solid var(--da-border)',
    borderRadius: 8,
    background: 'var(--da-surface, #fff)',
    padding: 14,
    overflow: 'auto',
    maxHeight: 480,
  },
  filePath: {
    fontFamily: 'var(--da-mono, monospace)',
    fontSize: '0.72rem',
    color: 'var(--da-text-3)',
    marginBottom: 8,
  },
};

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
    return <div className="da-small">{emptyText}</div>;
  }

  return (
    <div style={S.wrap}>
      <div style={S.list}>
        {entries.map(e => {
          const on = e.key === selectedKey;
          return (
            <button
              key={e.key}
              style={on ? { ...S.item, ...S.itemOn } : S.item}
              onClick={() => {
                if (on) setBust(b => b + 1); // re-click refreshes from the workspace
                onSelect(e.key);
              }}
            >
              <span style={S.title}>
                {e.dirty && <span style={S.dirtyDot} title="工作区有未发布变更" />}
                <span
                  style={{
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {e.title}
                </span>
                {e.dirty && <span style={S.dirtyBadge}>未发布</span>}
              </span>
              {e.subtitle && <div style={S.subtitle}>{e.subtitle}</div>}
            </button>
          );
        })}
      </div>
      <div style={S.viewer}>
        {!selected ? (
          <div className="da-small" style={{ color: 'var(--da-text-3)' }}>
            {emptyText}
          </div>
        ) : !selected.file ? (
          <div className="da-small" style={{ color: 'var(--da-text-3)' }}>
            {fallbackText}
          </div>
        ) : error ? (
          <div className="da-small" style={{ color: 'var(--da-danger)' }}>
            {error}
          </div>
        ) : content === null ? (
          <div className="da-small" style={{ color: 'var(--da-text-3)' }}>
            加载中…
          </div>
        ) : (
          <>
            <div style={S.filePath}>{selected.file}</div>
            <YamlContent text={content} />
          </>
        )}
      </div>
    </div>
  );
}
