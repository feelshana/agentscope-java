import React, { useEffect, useRef, useState } from 'react';

export interface TaskTraceProps {
  /** True while the assistant turn is still streaming / invoking tools. */
  running: boolean;
  /** True once at least one step (narration or tool call) has appeared. */
  active: boolean;
  /** True only when the turn was interrupted and produced no usable answer. */
  hasError?: boolean;
  /** True when some tools errored but the agent recovered and still answered. */
  hadToolErrors?: boolean;
  /** True when the turn was interrupted before completion. */
  interrupted?: boolean;
  /** Elapsed time in ms (shown after completion). */
  elapsedMs?: number;
  children: React.ReactNode;
}

/**
 * WorkBuddy-style inline execution trace.
 *
 * Collapsed: a single subtle line "状态 时间 ›" with no border.
 * Expanded: children rendered as inline step list with small icons.
 */
export default function TaskTrace({
  running,
  active,
  hasError = false,
  hadToolErrors = false,
  interrupted = false,
  elapsedMs,
  children,
}: TaskTraceProps) {
  const [open, setOpen] = useState(running);
  const userToggled = useRef(false);

  const failed = hasError || interrupted;

  useEffect(() => {
    if (!running && !userToggled.current && !failed) {
      setOpen(false);
    }
  }, [running, failed]);

  function toggle() {
    userToggled.current = true;
    setOpen(o => !o);
  }

  let status: string;
  if (running) {
    status = active ? '执行中…' : '收到，正在处理…';
  } else if (interrupted) {
    status = '已中断';
  } else if (hasError) {
    status = '执行失败';
  } else if (hadToolErrors) {
    status = '已完成（部分步骤重试）';
  } else {
    status = '已完成';
  }

  const elapsedText = !running && elapsedMs != null
    ? formatElapsed(elapsedMs)
    : null;

  return (
    <div className="da-trace">
      <button type="button" className="da-trace-head" onClick={toggle} aria-expanded={open}>
        <span className={`da-trace-status${running ? ' running' : failed ? ' failed' : ''}`}>
          {status}
        </span>
        {elapsedText && <span className="da-trace-elapsed">{elapsedText}</span>}
        <span className={`da-trace-chevron${open ? ' open' : ''}`}>›</span>
      </button>
      {open && <div className="da-trace-body">{children}</div>}
    </div>
  );
}

function formatElapsed(ms: number): string {
  if (ms < 1000) return '不到1秒';
  const sec = Math.round(ms / 1000);
  if (sec < 60) return `${sec}秒`;
  const min = Math.floor(sec / 60);
  const rem = sec % 60;
  return rem ? `${min}分${rem}秒` : `${min}分`;
}
