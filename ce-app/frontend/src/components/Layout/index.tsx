import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Home, LayoutGrid, Clapperboard, Settings as SettingsIcon, Menu, Bell } from 'lucide-react'
import RunningStrip from '../RunningStrip'
import { useRuntime, selectActiveTasks } from '../../store/runtime'

const TABS = [
  { key: '/', label: 'خانه', icon: Home },
  { key: '/dashboard', label: 'پروژه‌ها', icon: LayoutGrid },
  { key: '/studio', label: 'استودیو', icon: Clapperboard },
  { key: '/settings', label: 'تنظیمات', icon: SettingsIcon },
]

export default function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const activeCount = useRuntime(selectActiveTasks).length

  const activeKey =
    TABS.filter((t) => t.key !== '/')
      .sort((a, b) => b.key.length - a.key.length)
      .find((t) => location.pathname.startsWith(t.key))?.key ?? '/'

  return (
    <div className="ce-shell">
      <header className="ce-header">
        <button className="ce-iconbtn" aria-label="منو" onClick={() => navigate('/doctor')}>
          <Menu size={22} />
          {activeCount > 0 && <span className="ce-header__dot" />}
        </button>

        <div className="ce-header__brand">
          <span className="ce-logo">CE</span>
          <span>Cutting Edge</span>
        </div>

        <button className="ce-iconbtn" aria-label="اعلان‌ها" onClick={() => navigate('/dashboard')}>
          <Bell size={20} />
          {activeCount > 0 && <span className="ce-header__badge">{activeCount}</span>}
        </button>
      </header>

      <nav className="ce-tabs">
        {TABS.map(({ key, label, icon: Icon }) => (
          <button
            key={key}
            className={`ce-tab ${activeKey === key ? 'is-active' : ''}`}
            onClick={() => navigate(key)}
          >
            <Icon size={22} />
            <span>{label}</span>
          </button>
        ))}
      </nav>

      <main className="ce-content">
        <Outlet />
      </main>

      {/* Always-visible dock: proof that work keeps running across navigation. */}
      <RunningStrip compact />
    </div>
  )
}
