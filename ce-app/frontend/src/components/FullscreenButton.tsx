import { useEffect, useState } from 'react'
import { Maximize2, Minimize2 } from 'lucide-react'
import { useI18n } from '../i18n'

interface DesktopBridge {
  toggleFullscreen?: () => Promise<boolean>
  isFullscreen?: () => Promise<boolean>
  onFullscreenChange?: (cb: (value: boolean) => void) => () => void
}

/**
 * Fullscreen for both runtimes: the Electron window when packaged, the browser
 * Fullscreen API in the dev preview — so the control is never dead.
 */
export default function FullscreenButton() {
  const { t } = useI18n()
  const [active, setActive] = useState(false)
  const bridge = (window as unknown as { cuttingEdge?: DesktopBridge }).cuttingEdge

  useEffect(() => {
    if (bridge?.isFullscreen) {
      bridge.isFullscreen().then(setActive).catch(() => undefined)
      return bridge.onFullscreenChange?.(setActive)
    }
    const onChange = () => setActive(Boolean(document.fullscreenElement))
    document.addEventListener('fullscreenchange', onChange)
    return () => document.removeEventListener('fullscreenchange', onChange)
  }, [bridge])

  const toggle = async () => {
    if (bridge?.toggleFullscreen) {
      setActive(await bridge.toggleFullscreen())
      return
    }
    if (document.fullscreenElement) await document.exitFullscreen()
    else await document.documentElement.requestFullscreen().catch(() => undefined)
  }

  const label = active ? t('Exit fullscreen (F11)', 'خروج از تمام‌صفحه (F11)') : t('Fullscreen (F11)', 'تمام‌صفحه (F11)')

  return (
    <button className="ce-iconbtn" onClick={toggle} title={label} aria-label={label}>
      {active ? <Minimize2 size={19} /> : <Maximize2 size={19} />}
    </button>
  )
}
