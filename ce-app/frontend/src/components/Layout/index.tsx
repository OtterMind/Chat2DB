import { useEffect, useRef, useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { Home, LayoutGrid, Clapperboard, Settings as SettingsIcon, Menu, Bell, ChevronDown } from 'lucide-react'
import BrandMark from '../BrandMark'
import BackendBanner from '../BackendBanner'
import FullscreenButton from '../FullscreenButton'
import RunningStrip from '../RunningStrip'
import { useRuntime, selectActiveTasks } from '../../store/runtime'
import { useI18n } from '../../i18n'

const TABS = [
  { key: '/', label: ['Home', 'خانه'] as const, icon: Home, match: ['/'] },
  { key: '/dashboard', label: ['Projects', 'پروژه‌ها'] as const, icon: LayoutGrid, match: ['/dashboard', '/new', '/jobs'] },
  { key: '/studio', label: ['Studio', 'استودیو'] as const, icon: Clapperboard, match: ['/studio'] },
  { key: '/settings', label: ['Settings', 'تنظیمات'] as const, icon: SettingsIcon, match: ['/settings', '/uploads', '/doctor'] },
]

export default function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const activeCount = useRuntime(selectActiveTasks).length
  const { t, lang } = useI18n()
  const li = lang === 'fa' ? 1 : 0
  const contentRef = useRef<HTMLElement>(null)
  const reduceMotion = useReducedMotion()

  /*
   * Immersive sections.
   *
   * The home screen is a launcher, so it keeps its brand line and tab bar. Every
   * other screen is work: the chrome fades out, the section takes the whole
   * window, and the header comes back the moment the pointer reaches the top
   * edge (or on Escape, or from the small tab pill). No control disappears
   * without a way back — that is the difference between immersive and lost.
   */
  const isLauncher = location.pathname === '/'
  const [revealed, setRevealed] = useState(false)
  const chromeVisible = isLauncher || revealed

  useEffect(() => {
    setRevealed(false)
  }, [location.pathname])

  useEffect(() => {
    if (isLauncher) return
    const onMove = (event: PointerEvent) => {
      if (event.clientY <= 6) setRevealed(true)
      else if (event.clientY > 150) setRevealed(false)
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setRevealed((v) => !v)
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('keydown', onKey)
    }
  }, [isLauncher])

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

  const fade = reduceMotion
    ? { duration: 0 }
    : { duration: 0.32, ease: [0.22, 0.61, 0.36, 1] as [number, number, number, number] }

  return (
    <div className={`ce-shell ${chromeVisible ? '' : 'is-immersive'}`}>
      <AnimatePresence initial={false}>
        {chromeVisible && (
          <motion.div
            key="chrome"
            className="ce-chrome"
            initial={{ opacity: 0, y: -18 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -18 }}
            transition={fade}
          >
            <header className="ce-header">
              <button className="ce-iconbtn" aria-label={t('Menu', 'منو')} onClick={() => navigate('/doctor')}>
                <Menu size={22} />
                {activeCount > 0 && <span className="ce-header__dot" />}
              </button>

              <div className="ce-header__brand">
                <BrandMark />
              </div>

              <div className="ce-header__actions">
                <FullscreenButton />
                <button
                  className="ce-iconbtn"
                  aria-label={t('Notifications', 'اعلان‌ها')}
                  onClick={() => navigate('/dashboard')}
                >
                  <Bell size={20} />
                  {activeCount > 0 && <span className="ce-header__badge">{activeCount}</span>}
                </button>
              </div>
            </header>

            <nav className="ce-tabs">
              {TABS.map(({ key, label, icon: Icon }) => (
                <button
                  key={key}
                  className={`ce-tab ${activeKey === key ? 'is-active' : ''}`}
                  onClick={() => navigate(key)}
                >
                  <Icon size={22} />
                  <span>{label[li]}</span>
                </button>
              ))}
            </nav>
          </motion.div>
        )}
      </AnimatePresence>

      {/* The way back into the chrome while a section is full screen. */}
      {!isLauncher && !revealed && (
        <button
          className="ce-reveal"
          onClick={() => setRevealed(true)}
          title={t('Show the menu (Escape)', 'نمایش منو (Escape)')}
          aria-label={t('Show the menu', 'نمایش منو')}
        >
          <ChevronDown size={15} />
        </button>
      )}

      <BackendBanner />

      <main className="ce-content" ref={contentRef}>
        {/* Keyed by path so a route swap replaces the subtree instead of merging
            two screens' DOM, and animated so sections arrive instead of blinking. */}
        <AnimatePresence mode="wait" initial={false}>
          <motion.div
            key={location.pathname}
            className="ce-route"
            initial={{ opacity: 0, y: reduceMotion ? 0 : 10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: reduceMotion ? 0 : -6 }}
            transition={fade}
          >
            <Outlet />
          </motion.div>
        </AnimatePresence>
      </main>

      {/* Always-visible dock: proof that work keeps running across navigation. */}
      <RunningStrip compact />
    </div>
  )
}
