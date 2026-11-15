import { useCallback, useEffect, useRef, useState } from 'react';
import { deleteDocument, listDocuments, uploadDocument } from '../../api/draftReview';
import type { BusinessDocument } from '../../api/draftReview';
import Icon from '../Icon';

const STATUS: Record<BusinessDocument['status'], { label: string; color?: string }> = {
  ADDED: { label: '新增 · 未发布', color: 'var(--da-warn)' },
  MODIFIED: { label: '已替换 · 未发布', color: 'var(--da-warn)' },
  UNCHANGED: { label: '已发布', color: 'var(--da-success)' },
  DELETED: { label: '已删除 · 未发布', color: 'var(--da-warn)' },
};

/**
 * spec092: several business documents per knowledge base. Adding, replacing and deleting are
 * draft changes; {@code onChanged} lets the page hand the change to the assistant.
 */
export default function BusinessDocumentsPanel({ groupId, onChanged, variant = 'default', onReadyChange }: {
  groupId: string;
  onChanged: (summary: string) => void;
  variant?: 'default' | 'setup';
  onReadyChange?: (ready: boolean) => void;
}) {
  const [documents, setDocuments] = useState<BusinessDocument[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const picker = useRef<HTMLInputElement>(null);
  const target = useRef<string | null>(null);
  const active = useRef(groupId); active.current = groupId;

  const refresh = useCallback(async () => {
    try {
      const list = await listDocuments(groupId);
      if (active.current === groupId) { setDocuments(list); setLoaded(true); setError(null); }
    } catch (e) { if (active.current === groupId) { setLoaded(false); setError(e instanceof Error ? e.message : '读取失败'); } }
  }, [groupId]);
  useEffect(() => { setDocuments([]); setLoaded(false); setError(null); void refresh(); }, [refresh]);
  useEffect(() => { onReadyChange?.(loaded && busy === null && !error); }, [loaded, busy, error, onReadyChange]);

  const pick = (replacing: string | null) => { target.current = replacing; picker.current?.click(); };
  const upload = async (file: File) => {
    const replacing = target.current;
    setBusy(replacing ?? 'new'); setError(null);
    try {
      // Replacing keeps the document's name whatever the picked file is called; the original
      // extension stays so .docx text extraction still applies.
      const extension = file.name.includes('.') ? file.name.slice(file.name.lastIndexOf('.')) : '';
      const named = replacing ? new File([file], replacing.replace(/\.md$/, '') + extension, { type: file.type }) : file;
      const saved = await uploadDocument(groupId, named);
      if (active.current !== groupId) return;
      await refresh();
      onChanged(replacing || saved.status === 'MODIFIED' ? `替换了业务文档 ${saved.name}` : `新增了业务文档 ${saved.name}`);
    } catch (e) { if (active.current === groupId) setError(e instanceof Error ? e.message : '上传失败'); }
    finally { if (active.current === groupId) setBusy(null); }
  };
  const remove = async (name: string) => {
    setBusy(name); setError(null);
    try {
      await deleteDocument(groupId, name);
      if (active.current !== groupId) return;
      await refresh();
      onChanged(`删除了业务文档 ${name}`);
    } catch (e) { if (active.current === groupId) setError(e instanceof Error ? e.message : '删除失败'); }
    finally { if (active.current === groupId) setBusy(null); }
  };

  const setup = variant === 'setup';
  const currentDocuments = documents.filter(doc => doc.status !== 'DELETED');
  return <div className={setup ? 'mp-documents' : 'da-card'}>
    <input ref={picker} type="file" hidden accept=".md,.txt,.docx"
      onChange={e => { const file = e.target.files?.[0]; e.target.value = ''; if (file) void upload(file); }} />
    {setup ? <div className="mp-row">
      <span className="mp-row-icon"><Icon name="file" size="sm" /></span>
      <div className="mp-row-text"><strong>业务文档 <span className="mp-optional">可选</span></strong>
        <small>{!loaded && !error ? '正在读取业务文档…' : currentDocuments.length ? `已上传 ${currentDocuments.length} 份 · 可以继续添加或替换` : '未上传 · .md / .txt / .docx，写了指标口径会更准，不传也能开始'}</small>
      </div>
      <button type="button" className="mp-button" disabled={busy !== null || !loaded} onClick={() => pick(null)}>{busy === 'new' ? '上传中…' : '上传'}</button>
    </div> : <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <h3 style={{ margin: 0 }}>业务文档</h3>
      <span className="da-small">可选，可以有多份</span>
      <button className="da-btn" style={{ marginLeft: 'auto' }} disabled={busy !== null} onClick={() => pick(null)}>
        {busy === 'new' ? '上传中…' : '＋ 新增文档'}
      </button>
    </div>}
    {!setup && <p className="da-small">新增、替换、删除都只进入草稿，助手会据此更新描述和规则；发布后才对问数生效。支持 .md / .txt / .docx。</p>}
    {error && <p role="alert" className={setup ? 'mp-error' : 'da-small'} style={{ color: 'var(--da-danger)' }}>{error} <button type="button" className="mp-text-button" onClick={() => { setError(null); void refresh(); }}>重试</button></p>}
    {!setup && !documents.length && <p className="da-small">还没有业务文档。写有字段含义、指标口径、过滤规则的文档能让问数更准确。</p>}
    {documents.map(doc => <div key={doc.name} className={setup ? 'mp-document' : undefined} style={setup ? undefined : { display: 'flex', alignItems: 'center', gap: 8, padding: '8px 0', borderTop: '1px solid var(--da-border)' }}>
      <span style={{ textDecoration: doc.status === 'DELETED' ? 'line-through' : undefined }}>{doc.name}</span>
      <span className="da-badge" style={{ color: STATUS[doc.status].color }}>{STATUS[doc.status].label}</span>
      {doc.previousVersions > 0 && <span className="da-small">历史版本 {doc.previousVersions}</span>}
      {doc.status !== 'DELETED' && <span style={{ marginLeft: 'auto', display: 'flex', gap: 6 }}>
        <button className="da-btn da-btn-sm" disabled={busy !== null} onClick={() => pick(doc.name)}>{busy === doc.name ? '处理中…' : '替换'}</button>
        <button className="da-btn da-btn-sm" disabled={busy !== null} onClick={() => void remove(doc.name)}>删除</button>
      </span>}
    </div>)}
  </div>;
}
