import { useRef } from 'react';

export interface DocumentUploadState {
  status: 'idle' | 'uploading' | 'success' | 'error';
  filename: string;
  error?: string;
}
export default function BusinessDocumentGuide({ content, upload, analysisStatus, onUpload }: {
  content: string;
  upload: DocumentUploadState;
  analysisStatus?: string;
  onUpload: () => void;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const saved = !!content.trim();
  const uploading = upload.status === 'uploading';
  const analysis = analysisStatus === 'RUNNING' ? '口径分析中，可继续对话'
    : analysisStatus === 'READY' ? '口径分析完成，提案待审阅'
      : analysisStatus === 'FAILED' ? '口径分析未完成，可继续对话或重新分析'
        : '口径变更仍需确认';
  const status = uploading ? `正在上传：${upload.filename}`
    : upload.status === 'error' ? `上传失败：${upload.filename}。${upload.error || '请重试'}`
      : upload.status === 'success' ? `✓ 上传成功：${upload.filename} · 已保存到当前知识库`
        : saved ? '✓ 当前知识库已有业务文档' : '先上传指标定义或业务规则；没有文档也可以先提交分析问题。';
  return <section aria-label="业务文档准备" style={{ padding: '10px 14px', flexShrink: 0, border: '1px solid var(--da-primary)', background: 'var(--da-primary-subtle)', borderRadius: 10 }}>
    <div style={{ display: 'flex', gap: 12, alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap' }}>
      <strong style={{ fontSize: 14 }}>{saved ? '业务文档已准备' : '建议先上传业务文档'}</strong>
      <div style={{ display: 'flex', gap: 8 }}>
        {saved && <button className="da-btn da-btn-sm" onClick={() => dialogRef.current?.showModal()}>查看业务文档</button>}
        <button className="da-btn da-btn-primary da-btn-sm" style={{ fontWeight: 600 }} disabled={uploading} onClick={onUpload}>
          {uploading ? '正在上传…' : saved ? '更新业务文档' : '上传业务文档'}
        </button>
      </div>
    </div>
    <div role={upload.status === 'error' ? 'alert' : 'status'} aria-live="polite" className="da-small"
      title={status} style={{ marginTop: 4, overflowWrap: 'anywhere', color: upload.status === 'error' ? 'var(--da-danger)' : saved ? 'var(--da-success)' : undefined }}>
      {status}{saved && !uploading && upload.status !== 'error' && <span style={{ color: 'var(--da-text-muted)' }}> · {analysis}</span>}
    </div>
    <dialog ref={dialogRef} aria-label="已保存的业务文档" onClick={event => { if (event.target === event.currentTarget) dialogRef.current?.close(); }}
      style={{ width: 720, maxWidth: '90vw', maxHeight: '85vh', boxSizing: 'border-box', padding: 24, border: '1px solid var(--da-border)', borderRadius: 12, background: 'var(--da-surface)', color: 'var(--da-text)' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
        <strong>已保存的业务文档</strong><button className="da-btn da-btn-sm" onClick={() => dialogRef.current?.close()}>关闭文档</button>
      </div>
      <p className="da-small">{analysis}。可到“查看语义模型 → 术语与规则”审阅提案；文档保存不等于模型已修改。</p>
      <pre style={{ maxHeight: '60vh', overflowY: 'auto', whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontFamily: 'inherit', lineHeight: 1.7 }}>{content}</pre>
      <p className="da-small">更新上传会替换当前业务文档内容。</p>
    </dialog>
  </section>;
}
