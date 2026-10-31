import React, { useEffect, useState } from 'react';
import Icon from './Icon';
import './ConversationPolish.css';

export default function CopyAnswerButton({ text }: { text: string }) {
  const [status, setStatus] = useState<'idle' | 'copied' | 'failed'>('idle');
  const [busy, setBusy] = useState(false);
  useEffect(() => { setStatus('idle'); }, [text]);
  useEffect(() => {
    if (status !== 'copied') return;
    const timer = setTimeout(() => setStatus('idle'), 2500);
    return () => clearTimeout(timer);
  }, [status]);
  return <div className="da-answer-copy">
    <button type="button" className="da-btn da-btn-ghost da-btn-sm" disabled={busy || !text.trim()} onClick={async () => {
      setBusy(true);
      try { await navigator.clipboard.writeText(text); setStatus('copied'); }
      catch { setStatus('failed'); }
      finally { setBusy(false); }
    }}><Icon name={status === 'copied' ? 'check' : 'copy'} size="sm" />{status === 'copied' ? '已复制' : '复制回答'}</button>
    <span role="status" aria-live="polite">{status === 'failed' ? '浏览器未允许复制，请选择回答文字手动复制。' : status === 'copied' ? '回答已复制到剪贴板' : ''}</span>
  </div>;
}
