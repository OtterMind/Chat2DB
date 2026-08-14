import { Bot, Clock3, History, Home, RefreshCw, Settings, Upload, Users, Video } from 'lucide-react';
import { NavLink } from 'react-router-dom';
import { useStore } from '@/lib/store';
import { useI18n, type TranslationKey } from '@/i18n';

const links: ReadonlyArray<readonly [string, typeof Home, TranslationKey]> = [
  ['/', Home, 'dashboard'], ['/upload', Upload, 'single'], ['/channel', Users, 'channel'], ['/sync', RefreshCw, 'sync'],
  ['/clipper', Bot, 'clipper'], ['/pending', Clock3, 'pending'], ['/history', History, 'history'], ['/settings', Settings, 'settings']
];

export function Sidebar(): JSX.Element {
  const account = useStore((state) => state.auth?.account);
  const { t } = useI18n();
  return <aside className="fixed inset-y-0 left-0 rtl:left-auto rtl:right-0 w-[260px] border-r rtl:border-r-0 rtl:border-l border-border bg-surface p-4 flex flex-col z-10">
    <div className="flex items-center gap-3 px-2 h-14"><div className="w-10 h-8 rounded-lg bg-primary grid place-items-center"><Video size={20}/></div><b>YT Auto-Uploader</b></div>
    <nav className="mt-5 space-y-1">{links.map(([to, Icon, key]) => <NavLink key={to} to={to} className={({ isActive }) => `flex items-center gap-3 rounded-md px-3 py-2.5 text-sm ${isActive ? 'bg-primary text-white' : 'text-zinc-400 hover:bg-surface-2 hover:text-white'}`}><Icon size={19}/>{t(key)}</NavLink>)}</nav>
    <div className="mt-auto card p-3 flex items-center gap-3">{account?.pictureUrl ? <img src={account.pictureUrl} className="w-10 h-10 rounded-full"/> : <div className="w-10 h-10 rounded-full bg-surface-2 grid place-items-center">?</div>}<div className="min-w-0"><div className="text-sm font-semibold truncate">{account?.channelName ?? account?.name}</div><div className="text-xs muted truncate">{account?.email}</div></div></div>
  </aside>;
}
