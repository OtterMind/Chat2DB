import { useEffect, useState } from 'react'
import { message } from 'antd'
import { RefreshCw, Download, CheckCircle2, Settings as SettingsIcon, Stethoscope } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { formatBytes, updateBridge, type UpdatePayload } from '../services/updater'
import { useI18n } from '../i18n'
import { Num } from './Page'

declare const __APP_VERSION__: string
const APP_VERSION = __APP_VERSION__

/**
 * Update card for the launcher.
 *
 * This used to live only in Settings, which was only reachable from the tab bar
 * — and when the bar went away the user could no longer update the app at all.
 * The control that keeps the product alive belongs on the first screen, next to
 * the version it is about, not two navigations deep.
 */
export default function UpdateCard() {
  const { t } = useI18n()
  const navigate = useNavigate()
  const [phase, setPhase] = useState<'idle' | 'checking' | 'downloading' | 'ready' | 'uptodate'>('idle')
  const [available, setAvailable] = useState<string | null>(null)
  const [progress, setProgress] = useState<UpdatePayload | null>(null)
  const [failure, setFailure] = useState<string | null>(null)

  useEffect(() => {
    const bridge = updateBridge()
    if (!bridge) return
    return bridge.onUpdateEvent((payload) => {
      switch (payload.type) {
        case 'checking':
          setPhase('checking'); setFailure(null); setProgress(null); break
        case 'available':
          setPhase('downloading'); setAvailable(payload.version ?? null); break
        case 'not-available':
          setPhase('uptodate'); break
        case 'progress':
          setPhase('downloading'); setProgress(payload); break
        case 'downloaded':
          setPhase('ready'); message.success(t('The update is ready to install', 'به‌روزرسانی آماده‌ی نصب است')); break
        case 'error':
          setPhase('idle'); setFailure(payload.error ?? t('Unknown error', 'خطای نامشخص')); break
      }
    })
  }, [t])

  const bridge = updateBridge()

  return (
    <section className="ce-updatecard">
      <div className="ce-updatecard__head">
        <div>
          <h3>{t('Application update', 'به‌روزرسانی برنامه')}</h3>
          <span className="ce-updatecard__version">
            {t('Version', 'نسخه')} <Num>{APP_VERSION}</Num>
            {phase === 'checking' && ` · ${t('checking…', 'در حال بررسی…')}`}
            {phase === 'uptodate' && ` · ${t('up to date', 'به‌روز است')}`}
            {available && phase !== 'ready' && ` · ${t('new', 'جدید')} ${available}`}
            {phase === 'ready' && ` · ${t('ready to install', 'آماده‌ی نصب')}`}
          </span>
        </div>

        <div className="ce-updatecard__actions">
          {phase !== 'ready' ? (
            <button
              className="ce-btn ce-btn--sm"
              disabled={phase === 'checking' || phase === 'downloading'}
              onClick={() => {
                if (!bridge) {
                  message.info(
                    t('Updates are available in the desktop app.', 'به‌روزرسانی در نسخه‌ی دسکتاپ فعال است.')
                  )
                  return
                }
                setPhase('checking')
                bridge.runUpdate()
              }}
            >
              <RefreshCw size={15} /> {t('Check for updates', 'بررسی به‌روزرسانی')}
            </button>
          ) : (
            <button className="ce-btn ce-btn--sm" onClick={() => bridge?.installUpdate()}>
              <Download size={15} /> {t('Install and restart', 'نصب و راه‌اندازی دوباره')}
            </button>
          )}

          <button className="ce-iconbtn" title={t('Settings', 'تنظیمات')} onClick={() => navigate('/settings')}>
            <SettingsIcon size={18} />
          </button>
          <button className="ce-iconbtn" title={t('Diagnostics', 'عیب‌یابی')} onClick={() => navigate('/doctor')}>
            <Stethoscope size={18} />
          </button>
        </div>
      </div>

      {phase === 'downloading' && (
        <div className="ce-update">
          <span className="ce-progress">
            <span
              className="ce-progress__bar"
              style={{
                width: `${Math.max(2, progress?.percent ?? 0)}%`,
                background: 'linear-gradient(90deg,#6366F1,#8B5CF6)',
              }}
            />
          </span>
          <div className="ce-update__row">
            <span>
              <Num>{formatBytes(progress?.transferred)}</Num> {t('of', 'از')}{' '}
              <Num>{formatBytes(progress?.total)}</Num>
            </span>
            <span>
              <Num>{formatBytes(progress?.bytesPerSecond)}</Num>/s
            </span>
          </div>
          <p className="ce-hint">
            {t(
              'Only the changed blocks are downloaded — a number far below the full installer size means the differential patch is working.',
              'فقط بخش‌های تغییرکرده دانلود می‌شود؛ عددی خیلی کمتر از حجم کامل نصب‌کننده یعنی پچ تفاضلی کار می‌کند.'
            )}
          </p>
        </div>
      )}

      {phase === 'ready' && (
        <p className="ce-hint">
          <CheckCircle2 size={14} />{' '}
          {t('Installing restarts the app; your work is autosaved first.', 'نصب برنامه را دوباره باز می‌کند؛ کارت از قبل ذخیره شده است.')}
        </p>
      )}

      {failure && <p className="ce-updatecard__error">{failure}</p>}
    </section>
  )
}
