import { useEffect } from 'react';
import type { CaliberTable } from '../../api/calibers';
import { publishCheck } from './caliberPresentation';
import type { CheckItem } from './caliberPresentation';

const ACTION: Record<NonNullable<CheckItem['step']>, string> = { draft: '去起草', review: '去核对', verify: '去验证', document: '去上传' };

/**
 * spec153 publish check: blocking items keep the knowledge base from publishing (the draft bar's
 * publish button stays disabled); hints never block. Each item jumps to its table and step.
 */
export default function CaliberPublishDrawer({ tables, assistantIdle, onGo, onClose, onPublish }: {
  tables: CaliberTable[];
  assistantIdle: boolean;
  onGo: (model: string | undefined, step: CheckItem['step']) => void;
  onClose: () => void;
  onPublish: () => void;
}) {
  const { blocking, hints, ready } = publishCheck(tables);
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  const group = (title: string, tone: string, items: CheckItem[], empty: string) => <section className={`cw-check cw-check-${tone}`}>
    <h3><span className="cw-check-dot" aria-hidden="true" />{title}（{items.length}）</h3>
    {items.length === 0 ? <p className="da-small">{empty}</p> : items.map(i => <div key={i.title} className="cw-check-item">
      <div className="mo-grow"><strong>{i.title}</strong><div className="da-small">{i.detail}</div></div>
      {i.model && i.step && <button type="button" className="sm-text-link" disabled={i.step === 'draft' && !assistantIdle}
        onClick={() => onGo(i.model, i.step)}>{ACTION[i.step]}</button>}
    </div>)}
  </section>;
  return <div className="cw-drawer-layer" onClick={onClose}>
    <aside className="cw-drawer" role="dialog" aria-modal="true" aria-label="发布检查" onClick={event => event.stopPropagation()}>
      <header className="cw-drawer-head">
        <div className="mo-grow"><h2>发布前检查 · 口径</h2>
          <p className="da-small">阻断项必须处理；提示只供参考，不影响发布。</p></div>
        <button type="button" className="cw-close" aria-label="关闭" onClick={onClose}>×</button>
      </header>
      <div className="cw-drawer-body">
        {group('阻断发布', 'block', blocking, '没有阻断项。')}
        {group('提示（不影响发布）', 'hint', hints, '没有提示。')}
        {group('已就绪', 'ok', ready, '还没有完成的表。')}
      </div>
      <footer className="cw-drawer-foot">
        <span className={blocking.length ? 'cw-danger-text' : 'cw-ok-text'} role="status">
          {blocking.length ? `还有 ${blocking.length} 项阻断，处理完后才能发布` : '口径已就绪，可以发布知识库'}</span>
        <span className="mo-grow" />
        <button type="button" className="da-btn" onClick={onClose}>关闭</button>
        {!blocking.length && <button type="button" className="da-btn da-btn-primary" onClick={onPublish}>去发布</button>}
      </footer>
    </aside>
  </div>;
}
