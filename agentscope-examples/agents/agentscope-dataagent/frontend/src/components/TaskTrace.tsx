import React, { useEffect, useRef, useState } from 'react';
import Icon from './Icon';

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
  children: React.ReactNode;
}

/**
 * TC-style collapsible execution trace.
 *
 * Outcome semantics (per user expectation):
 * - 任务执行完成 — the turn finished and produced an answer, even if some
 *   intermediate tool calls failed and the agent recovered via another route.
 * - 任务执行完成（部分步骤重试后成功）— finished with an answer, but one or more
 *   tool calls had errored along the way (visible per-tool in the trace body).
 * - 任务执行失败 — the turn was interrupted (stream error) and produced no answer.
 *
 * The body stays open while running and on failure so the user can inspect what
 * happened; it auto-collapses on success unless the user opened it by hand.
 */
export default function TaskTrace({
  running,
  active,
  hasError = false,
  hadToolErrors = false,
  interrupted = false,
  children,
}: TaskTraceProps) {
  const [open, setOpen] = useState(running);
  const userToggled = useRef(false);

  const failed = hasError || interrupted;

  useEffect(() => {
    // Keep open on interruption so the user can see what went wrong;
    // auto-collapse on any successful finish unless user manually opened.
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
    status = active ? '任务进行中…' : '好的，收到您的需求，我将为您执行任务…';
  } else if (interrupted) {
    status = '任务已中断';
  } else if (hasError) {
    status = '任务执行失败（未产出回答）';
  } else if (hadToolErrors) {
    status = '任务执行完成（部分步骤重试后成功）';
  } else {
    status = '任务执行完成';
  }

  const statusClass = running
    ? 'running'
    : failed
    ? 'failed'
    : '';

  return (
    <div className="da-trace">
      <button type="button" className="da-trace-head" onClick={toggle} aria-expanded={open}>
        <span className={`da-trace-status ${statusClass}`}>{status}</span>
        <span className={`da-trace-chevron${open ? ' open' : ''}`}>
          <Icon name="chevron" size="sm" />
        </span>
      </button>
      {open && <div className="da-trace-body">{children}</div>}
    </div>
  );
}
