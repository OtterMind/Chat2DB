import { useEffect, useRef, useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import BrandMark from '../BrandMark'
import BackendBanner from '../BackendBanner'
import FullscreenButton from '../FullscreenButton'
import RunningStrip from '../RunningStrip'
import { useRuntime, selectActiveTasks } from '../../store/runtime'
import { backendOrigin } from '../../api/runtime'
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
/**
 * The world-class landing (Finn-Loop phase 1, option D): gradient-mesh aurora,
 * sparse gated particles, a spring logo, a sweeping filmstrip playhead and a
 * glass CTA. Reduced-motion collapses it to a calm static mark. It dismisses on
 * the CTA or on its own after a beat, so it never blocks the app.
 */
function Landing({ onDone }: { onDone: () => void }) {
  const reduce = useReducedMotion()
  const [gone, setGone] = useState(false)
  useEffect(() => {
    const t = setTimeout(() => { setGone(true); onDone() }, reduce ? 400 : 2600)
    return () => clearTimeout(t)
  }, [onDone, reduce])
  const leave = () => { setGone(true); onDone() }
  return (
    <motion.div className="ln-landing" initial={{ opacity: 1 }}
      animate={{ opacity: gone ? 0 : 1 }} transition={{ duration: 0.5 }}
      onAnimationComplete={() => { if (gone) onDone() }} role="dialog" aria-label="Cutting Edge">
      <span className="ln-landing__aurora" aria-hidden />
      {!reduce && (
        <span className="ln-landing__parts" aria-hidden>
          {Array.from({ length: 14 }).map((_, i) => (
            <motion.i key={i} style={{ left: `${(i * 7.3) % 100}%`, top: `${(i * 13.7) % 100}%` }}
              initial={{ y: 0, opacity: 0 }} animate={{ y: [0, -18, 0], opacity: [0, 0.7, 0] }}
              transition={{ duration: 3 + (i % 4), repeat: Infinity, delay: i * 0.2 }} />
          ))}
        </span>
      )}
      <motion.div initial={reduce ? {} : { scale: 0.8, opacity: 0, y: 14 }}
        animate={{ scale: 1, opacity: 1, y: 0 }}
        transition={{ type: 'spring', stiffness: 240, damping: 20 }}>
        <BrandMark size="lg" />
      </motion.div>
      <span className="ln-landing__strip" aria-hidden>
        <motion.i className="ln-landing__playhead"
          initial={reduce ? { left: '50%' } : { left: '0%' }} animate={{ left: '100%' }}
          transition={{ duration: 2.2, ease: 'easeInOut' }} />
      </span>
      <motion.button className="ln-landing__cta" onClick={leave}
        initial={reduce ? {} : { opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }}
        transition={{ delay: reduce ? 0 : 0.7, type: 'spring', stiffness: 200, damping: 18 }}>
        Start Editing
      </motion.button>
    </motion.div>
  )
}

export default function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const activeCount = useRuntime(selectActiveTasks).length
  const { t } = useI18n()
  const contentRef = useRef<HTMLElement>(null)
  const reduceMotion = useReducedMotion()

  const isLauncher = location.pathname === '/'

  /** The saved accent repaints before first paint of content; the splash is a
   *  one-second brand breath on cold start (skipped for reduced motion). */
  const [splash, setSplash] = useState(true)

  // Motion package switcher: the active package tunes the whole motion language
  // via CSS vars + a window flag (LiveGlobe reads it). Runtime-switchable, so it
  // grows through differential updates without a reinstall.
  useEffect(() => {
    const apply = () => {
      fetch(`${backendOrigin}/api/motion/params`)
        .then((r) => r.json())
        .then((p) => {
          const root = document.documentElement
          root.style.setProperty('--m-speed', String(p.duration ?? 1))
          root.style.setProperty('--m-stagger', `${(p.stagger ?? 0.05) * 1000}ms`)
          root.style.setProperty('--m-ease', String(p.ease ?? 'cubic-bezier(0.22, 0.61, 0.36, 1)'))
          ;(window as any).__ceMotion = p
          window.dispatchEvent(new Event('ce:motion'))
        })
        .catch(() => undefined)
    }
    apply()
    window.addEventListener('ce:motion-change', apply)
    return () => window.removeEventListener('ce:motion-change', apply)
  }, [])

  useEffect(() => {
    const acc = localStorage.getItem('ce-accent')
    if (acc) document.documentElement.dataset.accent = acc
    // The Landing owns its timing (CTA or ~2.6s); this is only a safety net.
    const timer = setTimeout(() => setSplash(false), reduceMotion ? 0 : 4000)
    return () => clearTimeout(timer)
  }, [reduceMotion])

  useEffect(() => {
    contentRef.current?.scrollTo({ top: 0 })
  }, [location.pathname, location.search])

  const spring = reduceMotion
    ? { duration: 0 }
    : { type: 'spring' as const, stiffness: 420, damping: 38, mass: 0.7 }

  return (
    <div className={`ce-shell ${isLauncher ? 'is-launcher' : 'is-immersive'}`}>
      {splash && <Landing onDone={() => setSplash(false)} />}
      {/* One shared element: centred on the launcher, docked in a section. */}
      <motion.button
        layoutId="ce-wordmark"
        transition={spring}
        className={`ce-brandbtn ${isLauncher ? 'is-hero' : 'is-docked'}`}
        onClick={() => navigate('/')}
        title={isLauncher ? 'Cutting Edge' : t('Back to home', 'برگشت به خانه')}
        aria-label={t('Home', 'خانه')}
      >
        {/* The single wordmark: the big hero on the launcher, docked elsewhere. */}
        <BrandMark size={isLauncher ? 'lg' : 'md'} />
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
