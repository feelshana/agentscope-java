import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { ACTIVE_AGENT_ID } from '../api/activeAgent';
import { AgentDefinition, getAgent } from '../api/agents';
import SessionsSidebar from './SessionsSidebar';
import { ToastHost } from './Toast';
import { ShellOutletContext } from './EditTierGate';
import { ShellNavigationContext } from './ShellNavigation';
import Icon from './Icon';
import NewVersionBanner from './NewVersionBanner';
import './AppShell.css';

export default function AppShell() {
  const location = useLocation();
  const [collapsed, setCollapsed] = useState(true);
  const [navigationOpen, setNavigationOpen] = useState(false);
  const navigation = useRef<HTMLElement>(null);
  const content = useRef<HTMLElement>(null);
  const navigationTrigger = useRef<HTMLElement | null>(null);
  const toggleNavigation = useCallback(() => {
    navigationTrigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setNavigationOpen(value => !value);
  }, []);
  useEffect(() => { setNavigationOpen(false); }, [location.pathname, location.search]);
  useEffect(() => {
    if (content.current) content.current.inert = navigationOpen;
    return () => { if (content.current) content.current.inert = false; };
  }, [navigationOpen]);
  useEffect(() => {
    if (!navigationOpen) return;
    navigation.current?.querySelector<HTMLButtonElement>('button')?.focus();
    const keys = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); setNavigationOpen(false); }
      if (event.key === 'Tab') {
        const nodes = Array.from(navigation.current?.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], input:not(:disabled), [tabindex="0"]') ?? []).filter(node => node.getClientRects().length > 0);
        if (!nodes.length) return;
        if (event.shiftKey && document.activeElement === nodes[0]) { event.preventDefault(); nodes[nodes.length - 1].focus(); }
        else if (!event.shiftKey && document.activeElement === nodes[nodes.length - 1]) { event.preventDefault(); nodes[0].focus(); }
      }
    };
    const resize = () => { if (window.innerWidth > 760) setNavigationOpen(false); };
    document.addEventListener('keydown', keys);
    window.addEventListener('resize', resize);
    return () => {
      document.removeEventListener('keydown', keys);
      window.removeEventListener('resize', resize);
      if (navigationTrigger.current?.isConnected) navigationTrigger.current.focus();
    };
  }, [navigationOpen]);
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

  const bumpSidebar = useCallback(() => setRefreshTick(t => t + 1), []);

  const ctx: ShellOutletContext = { agent, agentLoading, agentError, bumpSidebar };

  return (
    <ShellNavigationContext.Provider value={{ enabled: true, open: navigationOpen, toggle: toggleNavigation }}>
    <div className="app-shell">
      <a
        href="#main-content"
        style={{ position: 'absolute', left: -9999, top: 0, color: 'var(--da-primary)' }}
        onFocus={e => (e.currentTarget.style.left = '8px')}
        onBlur={e => (e.currentTarget.style.left = '-9999px')}
      >
        跳到主内容
      </a>
      {navigationOpen && <button type="button" className="shell-nav-backdrop" aria-label="关闭导航" tabIndex={-1} onClick={() => setNavigationOpen(false)} />}
      <aside ref={navigation} id="app-navigation" className={`shell-navigation${navigationOpen ? ' is-open' : ''}`}
        role={navigationOpen ? 'dialog' : undefined} aria-modal={navigationOpen || undefined} aria-label={navigationOpen ? '主导航' : undefined}>
        <button type="button" className="shell-nav-close" aria-label="关闭导航" onClick={() => setNavigationOpen(false)}><Icon name="close" size="sm" /></button>
        <SessionsSidebar refreshKey={refreshTick} collapsed={navigationOpen ? false : collapsed} onToggle={() => setCollapsed(value => !value)} />
      </aside>
      <main ref={content} id="main-content" style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden', minWidth: 0 }}>
        <Outlet context={ctx} />
      </main>
      <ToastHost />
      <NewVersionBanner />
    </div>
    </ShellNavigationContext.Provider>
  );
}
