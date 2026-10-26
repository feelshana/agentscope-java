import React, { createContext, useContext } from 'react';
import Icon from './Icon';

export const ShellNavigationContext = createContext<{ enabled: boolean; open: boolean; toggle: () => void } | null>(null);

export function ShellNavigationToggle() {
  const navigation = useContext(ShellNavigationContext);
  if (!navigation?.enabled) return null;
  return <button type="button" className="shell-nav-toggle" aria-label={navigation.open ? '收起导航' : '展开导航'}
    aria-expanded={navigation.open} aria-controls="app-navigation" onClick={navigation.toggle}>
    <Icon name={navigation.open ? 'close' : 'menu'} size="sm" />
  </button>;
}
