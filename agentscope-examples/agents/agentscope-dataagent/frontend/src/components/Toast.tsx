import React, { useEffect, useState } from 'react';
import Icon, { IconName } from './Icon';

export type ToastKind = 'success' | 'error' | 'info';

interface ToastItem {
  id: number;
  kind: ToastKind;
  text: string;
}

type Listener = (t: ToastItem) => void;
let listener: Listener | null = null;
let seq = 0;

/** Fire a transient toast (auto-dismiss 3.5s). Mount <ToastHost/> once (AppShell). */
export function toast(text: string, kind: ToastKind = 'info') {
  seq += 1;
  listener?.({ id: seq, kind, text });
}

const KIND_ICON: Record<ToastKind, IconName> = {
  success: 'check',
  error: 'warn',
  info: 'chat',
};
const KIND_COLOR: Record<ToastKind, string> = {
  success: 'var(--da-success)',
  error: 'var(--da-danger)',
  info: 'var(--da-primary)',
};

export function ToastHost() {
  const [items, setItems] = useState<ToastItem[]>([]);

  useEffect(() => {
    listener = t => {
      setItems(prev => [...prev, t]);
      setTimeout(() => setItems(prev => prev.filter(x => x.id !== t.id)), 3500);
    };
    return () => {
      listener = null;
    };
  }, []);

  return (
    <div
      aria-live="polite"
      style={{
        position: 'fixed',
        bottom: 24,
        left: '50%',
        transform: 'translateX(-50%)',
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
        zIndex: 100,
        pointerEvents: 'none',
      }}
    >
      {items.map(t => (
        <div
          key={t.id}
          className="da-enter"
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            padding: '10px 16px',
            background: 'var(--da-surface)',
            border: '1px solid var(--da-border)',
            borderLeft: `3px solid ${KIND_COLOR[t.kind]}`,
            borderRadius: 'var(--da-radius-md)',
            boxShadow: 'var(--da-shadow-pop)',
            fontSize: 13,
            color: 'var(--da-text)',
          }}
        >
          <span style={{ color: KIND_COLOR[t.kind], display: 'inline-flex' }}>
            <Icon name={KIND_ICON[t.kind]} size="sm" />
          </span>
          {t.text}
        </div>
      ))}
    </div>
  );
}
