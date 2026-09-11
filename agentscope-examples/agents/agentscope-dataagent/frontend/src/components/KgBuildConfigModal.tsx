import React, { useEffect, useMemo, useState } from 'react';
import { getGroupDetail } from '../api/datasets';
import { triggerKgBuild } from '../api/knowledgeGraph';

const DOC_ID = '__knowledge_doc__';

interface Item {
  id: string;
  name: string;
  kind: 'doc' | 'table';
}

const overlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(15,23,42,0.45)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 60,
};

const dialogStyle: React.CSSProperties = {
  background: '#fff',
  borderRadius: 12,
  width: 720,
  maxWidth: '92vw',
  padding: 20,
  boxShadow: '0 20px 60px rgba(15,23,42,0.25)',
};

const colStyle: React.CSSProperties = {
  flex: 1,
  border: '1px solid #e2e8f0',
  borderRadius: 8,
  padding: 10,
  minHeight: 260,
  maxHeight: 320,
  overflowY: 'auto',
};

const rowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '6px 4px',
  fontSize: '0.82rem',
  color: '#334155',
};

const btnPrimary: React.CSSProperties = {
  background: '#1f2937',
  color: '#fff',
  border: 'none',
  borderRadius: 8,
  padding: '8px 18px',
  fontSize: '0.85rem',
  cursor: 'pointer',
};

const btnGhost: React.CSSProperties = {
  background: '#fff',
  color: '#334155',
  border: '1px solid #e2e8f0',
  borderRadius: 8,
  padding: '8px 18px',
  fontSize: '0.85rem',
  cursor: 'pointer',
};

/**
 * TC-style "配置知识图谱" dialog: pick which knowledge-base documents/tables feed the GraphRAG
 * build (left = available with 全选, right = chosen with 清空), then 保存 triggers the build.
 */
export default function KgBuildConfigModal({
  groupId,
  open,
  onClose,
  onSaved,
}: {
  groupId: string;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [items, setItems] = useState<Item[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [search, setSearch] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setError(null);
    setSearch('');
    (async () => {
      try {
        const detail = await getGroupDetail(groupId);
        const list: Item[] = [];
        if (detail.knowledge && detail.knowledge.trim()) {
          list.push({ id: DOC_ID, name: '知识文档', kind: 'doc' });
        }
        for (const d of detail.datasets) {
          list.push({ id: d.id, name: d.name, kind: 'table' });
        }
        if (!cancelled) {
          setItems(list);
          setSelected(new Set(list.map(i => i.id)));
        }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [open, groupId]);

  const filtered = useMemo(
    () => items.filter(i => i.name.toLowerCase().includes(search.trim().toLowerCase())),
    [items, search],
  );
  const chosen = items.filter(i => selected.has(i.id));

  function toggle(id: string) {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function handleSave() {
    setBusy(true);
    setError(null);
    try {
      await triggerKgBuild(groupId, {
        includeDoc: selected.has(DOC_ID),
        datasetIds: items.filter(i => i.kind === 'table' && selected.has(i.id)).map(i => i.id),
      });
      onSaved();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  if (!open) return null;

  return (
    <div style={overlayStyle} onClick={onClose}>
      <div style={dialogStyle} onClick={e => e.stopPropagation()}>
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginBottom: 14,
          }}
        >
          <div style={{ fontSize: '1rem', fontWeight: 600, color: '#0f172a' }}>配置知识图谱</div>
          <button style={btnGhost} onClick={onClose}>
            ×
          </button>
        </div>

        <div style={{ display: 'flex', gap: 14 }}>
          <div style={colStyle}>
            <div style={{ ...rowStyle, justifyContent: 'space-between' }}>
              <span style={{ fontWeight: 600 }}>选择数据文档</span>
            </div>
            <input
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder="请输入名称搜索"
              style={{
                width: '100%',
                border: '1px solid #e2e8f0',
                borderRadius: 6,
                padding: '6px 8px',
                fontSize: '0.8rem',
                marginBottom: 8,
                outline: 'none',
              }}
            />
            <label style={rowStyle}>
              <input
                type="checkbox"
                checked={filtered.length > 0 && filtered.every(i => selected.has(i.id))}
                onChange={e => {
                  const add = e.target.checked;
                  setSelected(prev => {
                    const next = new Set(prev);
                    for (const i of filtered) {
                      if (add) next.add(i.id);
                      else next.delete(i.id);
                    }
                    return next;
                  });
                }}
              />
              全选
            </label>
            {filtered.map(i => (
              <label key={i.id} style={rowStyle}>
                <input type="checkbox" checked={selected.has(i.id)} onChange={() => toggle(i.id)} />
                <span>{i.kind === 'doc' ? '📄' : '🗄'} {i.name}</span>
              </label>
            ))}
          </div>

          <div style={colStyle}>
            <div style={{ ...rowStyle, justifyContent: 'space-between' }}>
              <span style={{ fontWeight: 600 }}>已选择 {chosen.length} 个文档</span>
              <button style={{ ...btnGhost, padding: '2px 8px' }} onClick={() => setSelected(new Set())}>
                清空
              </button>
            </div>
            {chosen.map(i => (
              <div key={i.id} style={rowStyle}>
                <span>{i.kind === 'doc' ? '📄' : '🗄'} {i.name}</span>
              </div>
            ))}
            {chosen.length === 0 && (
              <div style={{ color: '#94a3b8', fontSize: '0.8rem', padding: 8 }}>未选择任何文档</div>
            )}
          </div>
        </div>

        {error && (
          <div style={{ color: '#b91c1c', fontSize: '0.8rem', marginTop: 10 }}>{error}</div>
        )}

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 16 }}>
          <button style={btnGhost} onClick={onClose}>
            取消
          </button>
          <button style={btnPrimary} disabled={busy || chosen.length === 0} onClick={handleSave}>
            {busy ? '提交中…' : '保存'}
          </button>
        </div>
      </div>
    </div>
  );
}
