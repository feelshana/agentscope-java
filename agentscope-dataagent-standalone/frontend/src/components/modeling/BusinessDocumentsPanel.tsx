import { useCallback, useEffect, useRef, useState } from 'react';
import { deleteDocument, listDocuments, uploadDocument } from '../../api/draftReview';
import type { BusinessDocument } from '../../api/draftReview';
import Icon from '../Icon';
import BusinessDocumentReader from './BusinessDocumentReader';
import './ModelingOptimization.css';

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
  const [reading, setReading] = useState<BusinessDocument | null>(null);
  const picker = useRef<HTMLInputElement>(null);
  const target = useRef<string | null>(null);
  const active = useRef(groupId); active.current = groupId;

  const refresh = useCallback(async () => {
    try {
      const list = await listDocuments(groupId);
      if (active.current === groupId) { setDocuments(list); setLoaded(true); setError(null); }
    } catch (e) { if (active.current === groupId) { setLoaded(false); setError(e instanceof Error ? e.message : '读取失败'); } }
  }, [groupId]);
  useEffect(() => { setDocuments([]); setLoaded(false); setError(null); setReading(null); void refresh(); }, [refresh]);
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
  return <div className={setup ? 'mp-documents' : 'da-card mo-documents'}>
    <input ref={picker} type="file" hidden accept=".md,.txt,.docx"
      onChange={e => { const file = e.target.files?.[0]; e.target.value = ''; if (file) void upload(file); }} />
    {setup ? <div className="mp-row">
      <span className="mp-row-icon"><Icon name="file" size="sm" /></span>
      <div className="mp-row-text"><strong>业务文档 <span className="mp-optional">可选</span></strong>
        <small>{!loaded && !error ? '正在读取业务文档…' : currentDocuments.length ? `已上传 ${currentDocuments.length} 份 · 可以继续添加或替换` : '未上传 · .md / .txt / .docx，写了指标口径会更准，不传也能开始'}</small>
      </div>
      <button type="button" className="mp-button" disabled={busy !== null || !loaded} onClick={() => pick(null)}>{busy === 'new' ? '上传中…' : '上传'}</button>
    </div> : <div className="mo-section-head">
      <span className="mo-section-icon mo-section-icon-doc"><Icon name="file" /></span><div className="mo-section-heading"><h3>业务文档 <span className="mo-resource-count">{loaded ? `${currentDocuments.length} 份` : error ? '未能读取' : '读取中…'}</span></h3><p>补充指标定义、过滤规则与术语说明</p></div>
      <button className="da-btn da-btn-primary" disabled={busy !== null} onClick={() => pick(null)}>
        <Icon name="upload" size="sm" />{busy === 'new' ? '上传中…' : '新增文档'}
      </button>
    </div>}
    {error && <p role="alert" className={setup ? 'mp-error' : 'mo-documents-error'} style={{ color: 'var(--da-danger)' }}>{error} <button type="button" className={setup ? 'mp-text-button' : 'da-btn'} onClick={() => { setError(null); void refresh(); }}>重试</button></p>}
    {!setup && !loaded && !error && <p className="mo-section-description" role="status">正在读取业务文档…</p>}
    {!setup && loaded && !documents.length && <div className="mo-documents-empty"><Icon name="file" /><strong>还没有业务文档</strong><p>可补充字段含义、指标口径和过滤规则，也可以稍后添加。</p></div>}
    <div className={setup ? undefined : 'mo-document-list'}>{documents.map(doc => <div key={doc.name} className={setup ? 'mp-document' : `mo-document-row ${doc.status === 'DELETED' ? 'is-deleted' : ''}`}>
      {!setup && <span className="mo-document-icon"><Icon name="file" size="sm" /></span>}
      <div className={setup ? undefined : 'mo-grow'}>
        {setup || doc.status === 'DELETED' ? <span style={{ textDecoration: doc.status === 'DELETED' ? 'line-through' : undefined }}>{doc.name}</span>
          : <button type="button" className="mo-document-title" onClick={() => setReading(doc)} title="阅读文档内容">{doc.name}</button>}
        <div className={setup ? undefined : 'mo-document-meta'}><span className={`da-badge${setup ? '' : ` mo-document-status mo-document-status-${doc.status.toLowerCase()}`}`} style={{ color: STATUS[doc.status].color }}>{STATUS[doc.status].label}</span>
        {!setup && <span className="mo-document-size">{doc.chars.toLocaleString('zh-CN')} 字符</span>}
        {doc.previousVersions > 0 && <span className="da-small">历史版本 {doc.previousVersions}</span>}</div>
      </div>
      {doc.status !== 'DELETED' && <span className={setup ? 'mo-setup-document-actions' : 'mo-document-actions'}>
        {!setup && <button type="button" className="da-btn mo-document-read" onClick={() => setReading(doc)}><Icon name="book" size="sm" />阅读</button>}
        <button type="button" className={setup ? 'da-btn da-btn-sm' : 'mo-document-tool'} disabled={busy !== null} aria-label={setup ? undefined : `替换文档：${doc.name}`} title={setup ? undefined : busy === doc.name ? '处理中…' : '替换文档'} onClick={() => pick(doc.name)}>{setup ? busy === doc.name ? '处理中…' : '替换' : <Icon name="refresh" size="sm" />}</button>
        <button type="button" className={setup ? 'da-btn da-btn-sm' : 'mo-document-tool mo-document-delete'} disabled={busy !== null} aria-label={setup ? undefined : `删除文档：${doc.name}`} title={setup ? undefined : '删除文档'} onClick={() => void remove(doc.name)}>{setup ? '删除' : <Icon name="trash" size="sm" />}</button>
      </span>}
    </div>)}</div>
    {!setup && <footer className="mo-table-footer"><Icon name="info" size="sm" /><span>可选，支持 .md / .txt / .docx；发布后对问数生效</span></footer>}
    {reading && <BusinessDocumentReader key={`${groupId}:${reading.name}`} groupId={groupId} document={reading}
      onClose={() => setReading(current => current === reading ? null : current)} />}
  </div>;
}
