import React, { useCallback, useEffect, useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { ACTIVE_AGENT_ID } from '../api/activeAgent';
import { AgentDefinition, getAgent } from '../api/agents';
import SessionsSidebar from './SessionsSidebar';
import { ToastHost } from './Toast';
import NewVersionBanner from './NewVersionBanner';
import { HelpPanel } from './Onboarding';
import { ShellOutletContext } from './EditTierGate';

export default function AppShell() {
  // The modeling workbench keeps the global navigation like every other page, so it reads as
  // part of the product rather than a separate tool.
  const [agent, setAgent] = useState<AgentDefinition | null>(null);
  const [agentLoading, setAgentLoading] = useState(true);
  const [agentError, setAgentError] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setAgentLoading(true);
    setAgentError(null);
    getAgent(ACTIVE_AGENT_ID)
      .then(a => { if (!cancelled) setAgent(a); })
      .catch(e => { if (!cancelled) setAgentError(e instanceof Error ? e.message : 'Failed to load agent'); })
      .finally(() => { if (!cancelled) setAgentLoading(false); });
    return () => { cancelled = true; };
  }, []);

  // The modeling workbench (one knowledge base) starts with the navigation collapsed to an icon
  // rail; the user's own toggle wins until they move to another kind of page.
  const { pathname } = useLocation();
  const workbench = /^\/configure\/modeling\/[^/]+/.test(pathname);
  const [collapsed, setCollapsed] = useState(workbench);
  useEffect(() => { setCollapsed(workbench); }, [workbench]);

  const bumpSidebar = useCallback(() => setRefreshTick(t => t + 1), []);

  const ctx: ShellOutletContext = { agent, agentLoading, agentError, bumpSidebar };

  return (
    <div style={{ display: 'flex', height: '100vh', background: 'var(--da-app-bg)', color: 'var(--da-text)', overflow: 'hidden' }}>
      <a
        href="#main-content"
        style={{ position: 'absolute', left: -9999, top: 0, color: 'var(--da-primary)' }}
        onFocus={e => (e.currentTarget.style.left = '8px')}
        onBlur={e => (e.currentTarget.style.left = '-9999px')}
      >
        跳到主内容
      </a>
      <aside style={{ display: 'contents' }}>
        <SessionsSidebar refreshKey={refreshTick} collapsed={collapsed} onToggle={() => setCollapsed(c => !c)} />
      </aside>
      <main id="main-content" style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden', minWidth: 0 }}>
        <Outlet context={ctx} />
      </main>
      <ToastHost />
      <HelpPanel />
      <NewVersionBanner />
    </div>
  );
}
