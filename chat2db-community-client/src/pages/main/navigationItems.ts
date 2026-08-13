import { ClipboardList, Layers, LayoutDashboard, MessageSquarePlus } from 'lucide-react';
import type { ReactNode } from 'react';

import type { INavItem } from '@/typings/main';

export const CORE_MAIN_NAV_KEYS = ['stream', 'tasks', 'workspace', 'dashboard'] as const;

type CoreMainNavKey = (typeof CORE_MAIN_NAV_KEYS)[number];

type CoreMainNavContent = Record<
  CoreMainNavKey,
  {
    component: ReactNode;
    name: string;
  }
>;

export const CORE_MAIN_NAV_ICONS = {
  stream: MessageSquarePlus,
  tasks: ClipboardList,
  workspace: Layers,
  dashboard: LayoutDashboard,
} as const;

export function createCoreMainNavItems(content: CoreMainNavContent): INavItem[] {
  return CORE_MAIN_NAV_KEYS.map((key) => ({
    key,
    icon: CORE_MAIN_NAV_ICONS[key],
    isLoad: false,
    component: content[key].component,
    name: content[key].name,
  }));
}
