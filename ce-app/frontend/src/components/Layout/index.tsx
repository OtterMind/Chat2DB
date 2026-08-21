import { useEffect, useRef } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Home, LayoutGrid, Clapperboard, Settings as SettingsIcon, Menu, Bell } from 'lucide-react'
import BrandMark from '../BrandMark'
import RunningStrip from '../RunningStrip'
import { useRuntime, selectActiveTasks } from '../../store/runtime'

const TABS = [
  { key: '/', label: 'خانه', icon: Home, match: ['/'] },
  { key: '/dashboard', label: 'پروژه‌ها', icon: LayoutGrid, match: ['/dashboard', '/new', '/jobs'] },
  { key: '/studio', label: 'استودیو', icon: Clapperboard, match: ['/studio'] },
  { key: '/settings', label: 'تنظیمات', icon: SettingsIcon, match: ['/settings', '/uploads', '/doctor'] },
]

export default function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const activeCount = useRuntime(selectActiveTasks).length
  const contentRef = useRef<HTMLElement>(null)

  // Each screen starts at the top: without this a short page inherits the
  // scroll offset of the previous one and looks like two pages stacked.
  useEffect(() => {
    contentRef.current?.scrollTo({ top: 0 })
  }, [location.pathname, location.search])

  // Highlight the tab that owns the current screen, including its sub-routes,
  // so deep pages never look like they belong to the wrong section.
  const activeKey =
    TABS.find((t) => t.match.some((m) => m !== '/' && location.pathname.startsWith(m)))?.key ??
    (location.pathname === '/' ? '/' : '')

  return (
    <div className="ce-shell">
      <header className="ce-header">
        <button className="ce-iconbtn" aria-label="منو" onClick={() => navigate('/doctor')}>
          <Menu size={22} />
          {activeCount > 0 && <span className="ce-header__dot" />}
        </button>

        <div className="ce-header__brand">
          <BrandMark />
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

      <main className="ce-content" ref={contentRef} key="content">
        {/* keyed by path so a route swap replaces the subtree instead of
            merging two screens' DOM during transitions */}
        <div key={location.pathname} className="ce-route">
          <Outlet />
        </div>
      </main>

      {/* Always-visible dock: proof that work keeps running across navigation. */}
      <RunningStrip compact />
    </div>
  )
}
