import { useEffect, useRef } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import BrandMark from '../BrandMark'
import BackendBanner from '../BackendBanner'
import FullscreenButton from '../FullscreenButton'
import RunningStrip from '../RunningStrip'
import { useRuntime, selectActiveTasks } from '../../store/runtime'
import { useI18n } from '../../i18n'
import { Bell, Menu } from 'lucide-react'

/**
 * The shell is one thing now: the wordmark.
 *
 * There is no menu bar, no tab strip and no heading band. On the launcher the
 * wordmark sits in the middle of the screen as the identity of the app; the
 * moment you enter a section it flies to the top-left corner and becomes the way
 * home. Everything else that used to live in the bars is on the launcher, where
 * a session starts.
 */
export default function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const activeCount = useRuntime(selectActiveTasks).length
  const { t } = useI18n()
  const contentRef = useRef<HTMLElement>(null)
  const reduceMotion = useReducedMotion()

  const isLauncher = location.pathname === '/'

  useEffect(() => {
    contentRef.current?.scrollTo({ top: 0 })
  }, [location.pathname, location.search])

  const spring = reduceMotion
    ? { duration: 0 }
    : { type: 'spring' as const, stiffness: 420, damping: 38, mass: 0.7 }

  return (
    <div className={`ce-shell ${isLauncher ? 'is-launcher' : 'is-immersive'}`}>
      {/* One shared element: centred on the launcher, docked in a section. */}
      <motion.button
        layoutId="ce-wordmark"
        transition={spring}
        className={`ce-brandbtn ${isLauncher ? 'is-hero' : 'is-docked'}`}
        onClick={() => navigate('/')}
        title={isLauncher ? 'Cutting Edge' : t('Back to home', 'برگشت به خانه')}
        aria-label={t('Home', 'خانه')}
      >
        <BrandMark />
      </motion.button>

      {/* The launcher keeps the two window-level actions; sections stay clean. */}
      <AnimatePresence initial={false}>
        {isLauncher && (
          <motion.div
            key="launcher-actions"
            className="ce-launcheractions"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
          >
            <button className="ce-iconbtn" aria-label={t('Diagnostics', 'عیب‌یابی')} onClick={() => navigate('/doctor')}>
              <Menu size={20} />
              {activeCount > 0 && <span className="ce-header__dot" />}
            </button>
            <FullscreenButton />
            <button
              className="ce-iconbtn"
              aria-label={t('Notifications', 'اعلان‌ها')}
              onClick={() => navigate('/dashboard')}
            >
              <Bell size={19} />
              {activeCount > 0 && <span className="ce-header__badge">{activeCount}</span>}
            </button>
          </motion.div>
        )}
      </AnimatePresence>

      <BackendBanner />

      <main className="ce-content" ref={contentRef}>
        <AnimatePresence mode="wait" initial={false}>
          <motion.div
            key={location.pathname}
            className="ce-route"
            initial={{ opacity: 0, y: reduceMotion ? 0 : 10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: reduceMotion ? 0 : -6 }}
            transition={{ duration: reduceMotion ? 0 : 0.28, ease: [0.22, 0.61, 0.36, 1] }}
          >
            <Outlet />
          </motion.div>
        </AnimatePresence>
      </main>

      <RunningStrip compact />
    </div>
  )
}
