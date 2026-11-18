import React, { lazy, Suspense, useLayoutEffect, useRef } from 'react';
import './KnowledgeDataPanel.css';

const KnowledgeDataPanel = lazy(() => import('./KnowledgeDataPanel'));

/** Reuse the authorized read-only browser; the native drawer contains and restores focus. */
export default function DashboardKnowledgeDrawer({ groupId, onClose }: { groupId: string; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const trigger = useRef(document.activeElement instanceof HTMLElement ? document.activeElement : null);
  useLayoutEffect(() => {
    const node = dialog.current;
    node?.showModal();
    return () => { node?.close(); if (trigger.current?.isConnected) trigger.current.focus({ preventScroll: true }); };
  }, []);
  return <dialog ref={dialog} className="dashboard-knowledge-drawer" aria-label="知识库资料" onCancel={event => { event.preventDefault(); onClose(); }} onClick={event => { if (event.target === event.currentTarget) onClose(); }}>
    <div className="dashboard-knowledge-content"><Suspense fallback={<div className="kdp-state" role="status">加载知识库面板…<button type="button" className="dashboard-secondary" onClick={onClose}>关闭</button></div>}><KnowledgeDataPanel groupId={groupId} onClose={onClose} /></Suspense></div>
  </dialog>;
}
