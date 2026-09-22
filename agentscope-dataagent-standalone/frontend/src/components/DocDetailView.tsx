import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getKnowledge, KnowledgeDoc, DatasetGroup } from '../api/datasets';
import { triggerKgBuild } from '../api/knowledgeGraph';
import EmptyIllustration from './EmptyIllustration';

interface Segment {
  index: number;
  text: string;
  chars: number;
}

/**
 * TC-style document detail surface for a KB's relationship/knowledge doc: header with description,
 * updater, update time and chunking strategy, a segmented card list on the left and
 * 原文预览 / 分段摘要 / 表格结构化 tabs on the right, plus 发起对话 / 重建图谱 actions.
 */
export default function DocDetailView({
  groupId,
  group,
}: {
  groupId: string;
  group: DatasetGroup | null;
}) {
  const navigate = useNavigate();
  const [doc, setDoc] = useState<KnowledgeDoc | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<'preview' | 'segments' | 'table'>('preview');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    getKnowledge(groupId)
      .then(d => {
        if (!cancelled) setDoc(d);
      })
      .catch(e => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [groupId]);

  const segments = useMemo<Segment[]>(() => {
    if (!doc || !doc.content.trim()) return [];
    return doc.content
      .split(/\n\n+/)
      .map(s => s.trim())
      .filter(Boolean)
      .map((text, i) => ({ index: i + 1, text, chars: text.length }));
  }, [doc]);

  async function handleRebuild() {
    setBusy(true);
    setError(null);
    try {
      await triggerKgBuild(groupId, { includeDoc: true, datasetIds: [] });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const updated = doc?.updatedAt ? doc.updatedAt.replace('T', ' ').slice(0, 19) : '—';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div
        style={{
          background: 'var(--da-surface)',
          borderBottom: '1px solid var(--da-border)',
          padding: '16px 24px',
          display: 'flex',
          alignItems: 'flex-start',
          gap: 24,
        }}
      >
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="da-page-title">关系说明文档</div>
          <div className="da-small" style={{ marginTop: 6 }}>
            {group?.description || '该知识库的业务口径与表间关系说明'}
          </div>
          <div className="da-small" style={{ marginTop: 6, display: 'flex', gap: 16 }}>
            <span>添加人：{group ? '微信用户' : '—'}</span>
            <span>更新时间：{updated}</span>
            <span className="da-badge">智能分段</span>
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
          <button className="da-btn" onClick={() => navigate(`/chat?groups=${encodeURIComponent(groupId)}`)}>
            发起对话
          </button>
          <button className="da-btn da-btn-primary" disabled={busy} onClick={handleRebuild}>
            {busy ? '构建中…' : '重建图谱'}
          </button>
        </div>
      </div>
      {error && (
        <div style={{ padding: '8px 24px', color: 'var(--da-danger)', fontSize: 12 }}>{error}</div>
      )}

      {segments.length === 0 ? (
        <EmptyIllustration variant="doc" caption="尚未上传关系说明文档，可在知识库页上传 .docx/.md/.txt" />
      ) : (
        <div style={{ flex: 1, minHeight: 0, display: 'flex', gap: 16, padding: 16, overflow: 'hidden' }}>
          <div style={{ width: '46%', minWidth: 320, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div className="da-section">文档分段（{segments.length}）</div>
            {segments.map(s => (
              <div key={s.index} className="da-card" style={{ padding: 12 }}>
                <div className="da-small" style={{ marginBottom: 6 }}>
                  #{s.index} · 字数 {s.chars}
                </div>
                <div style={{ fontSize: 13, lineHeight: 1.7, color: 'var(--da-text-2)', whiteSpace: 'pre-wrap' }}>
                  {s.text}
                </div>
              </div>
            ))}
          </div>
          <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
            <div className="da-tabbar">
              <button className={tab === 'preview' ? 'da-tab da-tab-active' : 'da-tab'} onClick={() => setTab('preview')}>
                原文预览
              </button>
              <button className={tab === 'segments' ? 'da-tab da-tab-active' : 'da-tab'} onClick={() => setTab('segments')}>
                分段摘要
              </button>
              <button className="da-tab" disabled title="即将推出" style={{ opacity: 0.5, cursor: 'not-allowed' }}>
                表格结构化
              </button>
            </div>
            <div
              className="da-card"
              style={{ flex: 1, minHeight: 0, overflowY: 'auto', marginTop: 12, padding: 16 }}
            >
              {tab === 'preview' && (
                <div style={{ fontSize: 13, lineHeight: 1.8, color: 'var(--da-text-2)', whiteSpace: 'pre-wrap' }}>
                  {doc?.content}
                </div>
              )}
              {tab === 'segments' && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {segments.map(s => (
                    <div key={s.index} className="da-stat-row" style={{ alignItems: 'flex-start' }}>
                      <span>#{s.index}</span>
                      <b style={{ flex: 1, fontWeight: 500, color: 'var(--da-text-2)' }}>
                        {s.text.slice(0, 60)}
                        {s.text.length > 60 ? '…' : ''}
                      </b>
                      <span>{s.chars} 字</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
