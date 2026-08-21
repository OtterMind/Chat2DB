import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { AlertTriangle, RefreshCw, FileText } from 'lucide-react'
import { useRuntime } from '../store/runtime'
import { useI18n } from '../i18n'

interface DesktopBridge {
  restartBackend?: () => Promise<{ running: boolean; failure: string | null }>
  openLogFolder?: () => void
}

/**
 * A dead backend is now impossible to miss.
 *
 * Before this, the app looked merely "empty" when the bundled Python process was
 * not running: lists showed no data, the diagnostics screen showed dashes, and the
 * only visible error came from the first POST the user happened to trigger.
 */
export default function BackendBanner() {
  const online = useRuntime((s) => s.backendOnline)
  const { t } = useI18n()
  const navigate = useNavigate()
  const [restarting, setRestarting] = useState(false)

  if (online !== false) return null
  const bridge = (window as unknown as { cuttingEdge?: DesktopBridge }).cuttingEdge

  return (
    <div className="ce-offline">
      <AlertTriangle size={17} />
      <span className="ce-offline__text">
        {t(
          'The local processing service is not responding — import, export and analysis are unavailable.',
          'سرویس پردازش محلی پاسخ نمی‌دهد — افزودن رسانه، خروجی و تحلیل کار نمی‌کنند.'
        )}
      </span>
      {bridge?.restartBackend && (
        <button
          className="ce-btn ce-btn--sm"
          disabled={restarting}
          onClick={async () => {
            setRestarting(true)
            try {
              await bridge.restartBackend?.()
            } finally {
              setTimeout(() => setRestarting(false), 2000)
            }
          }}
        >
          <RefreshCw size={14} className={restarting ? 'ce-spin' : ''} /> {t('Restart', 'راه‌اندازی دوباره')}
        </button>
      )}
      <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => navigate('/doctor')}>
        <FileText size={14} /> {t('Diagnostics', 'عیب‌یابی')}
      </button>
    </div>
  )
}
