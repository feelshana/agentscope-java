import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { ACTIVE_AGENT_ID } from '../api/activeAgent';
import ChatHeader from '../components/ChatHeader';
import ChatPanel from '../components/ChatPanel';
import ToolInspector from '../components/ToolInspector';
import { ToolInspectPayload } from '../components/ToolCallBlock';
import { ShellOutletContext } from '../components/EditTierGate';

const PANEL_MIN = 300;

export default function ChatPage() {
  const ctx = useOutletContext<ShellOutletContext>();
  const [title, setTitle] = useState('');
  const [inspectorId, setInspectorId] = useState<string | null>(null);
  const [inspectorPayload, setInspectorPayload] = useState<ToolInspectPayload | null>(null);
  const [panelW, setPanelW] = useState(420);
  const dragging = useRef(false);

  const onMouseMove = useCallback((e: MouseEvent) => {
    if (!dragging.current) return;
    const max = Math.round(window.innerWidth * 0.6);
    const w = window.innerWidth - e.clientX;
    setPanelW(Math.max(PANEL_MIN, Math.min(max, w)));
  }, []);

  const stopDrag = useCallback(() => { dragging.current = false; }, []);

  useEffect(() => {
    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup', stopDrag);
    return () => {
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseup', stopDrag);
    };
  }, [onMouseMove, stopDrag]);

  const handleInspect = useCallback((t: ToolInspectPayload) => {
    setInspectorId(prev => {
      if (prev === t.id) {
        setInspectorPayload(null);
        return null;
      }
      setInspectorPayload(t);
      return t.id;
    });
  }, []);

  const handleToolResolved = useCallback((t: ToolInspectPayload | null) => {
    setInspectorPayload(t);
  }, []);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <ChatHeader title={title} />
      <div style={{ flex: 1, minHeight: 0, display: 'flex' }}>
        <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
          <ChatPanel
            agentId={ACTIVE_AGENT_ID}
            onSessionUpdate={ctx.bumpSidebar}
            onTitle={setTitle}
            onInspect={handleInspect}
            inspectorId={inspectorId}
            onToolResolved={handleToolResolved}
          />
        </div>
        {inspectorPayload && (
          <>
            <div
              title="拖动调整宽度"
              onMouseDown={() => { dragging.current = true; }}
              style={{
                width: 5, flexShrink: 0, cursor: 'col-resize',
                borderLeft: '1px solid var(--da-border)',
                background: 'transparent',
              }}
            />
            <div style={{
              width: panelW, flexShrink: 0, minHeight: 0,
              background: 'var(--da-surface)',
              borderLeft: '1px solid var(--da-border)',
            }}>
              <ToolInspector
                tool={inspectorPayload}
                onClose={() => { setInspectorId(null); setInspectorPayload(null); }}
              />
            </div>
          </>
        )}
      </div>
    </div>
  );
}
