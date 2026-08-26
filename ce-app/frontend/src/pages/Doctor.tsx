import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { RefreshCw, CheckCircle2, AlertTriangle, FolderOpen } from 'lucide-react'
import Page, { Card, Num, Stat } from '../components/Page'
import { systemApi } from '../api/jobs'
import { useI18n } from '../i18n'

interface Diagnostics {
  healthy?: boolean
  warnings?: string[]
  system?: { platform?: string; python_version?: string; cpu_count?: number; memory_gb?: number; disk_free_gb?: number }
  ffmpeg?: { found?: boolean; path?: string | null }
}

interface BackendStatus {
  running: boolean
  pid: number | null
  failure: string | null
  logPath: string
  tail: string[]
}

interface DesktopBridge {
  logPath?: () => Promise<string>
  openLogFolder?: () => void
  backendStatus?: () => Promise<BackendStatus>
  restartBackend?: () => Promise<{ running: boolean; failure: string | null }>
}

export default function Doctor() {
  const { t } = useI18n()
  const [logPath, setLogPath] = useState<string | null>(null)
  const [backend, setBackend] = useState<BackendStatus | null>(null)
  const bridge = (window as unknown as { cuttingEdge?: DesktopBridge }).cuttingEdge

  useEffect(() => {
    bridge?.logPath?.().then(setLogPath).catch(() => undefined)
    const refresh = () => bridge?.backendStatus?.().then(setBackend).catch(() => undefined)
    refresh()
    const timer = window.setInterval(refresh, 4000)
    return () => window.clearInterval(timer)
  }, [bridge])

  const { data, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['doctor'],
    queryFn: () => systemApi.doctor() as Promise<Diagnostics>,
  })

  return (
    <Page
      title={t('System health', 'سلامت سیستم')}
      subtitle={t('Check the prerequisites for video processing on this machine', 'بررسی پیش‌نیازهای پردازش ویدیو روی این دستگاه')}
      actions={
        <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => refetch()} disabled={isFetching}>
          <RefreshCw size={15} className={isFetching ? 'ce-spin' : ''} /> {t('Re-run', 'بررسی دوباره')}
        </button>
      }
    >
      {isLoading && <div className="ce-empty">{t('Checking…', 'در حال بررسی…')}</div>}

      {data && (
        <>
          <Card
            title={t('Overall status', 'وضعیت کلی')}
            tone={data.healthy ? 'success' : 'danger'}
            extra={
              <span className={`ce-badge ${data.healthy ? 'ce-badge--ok' : 'ce-badge--warn'}`}>
                {data.healthy ? t('Healthy', 'سالم') : t('Needs attention', 'نیازمند توجه')}
              </span>
            }
          >
            {data.warnings && data.warnings.length > 0 ? (
              <ul className="ce-warnlist">
                {data.warnings.map((w, i) => (
                  <li key={i}>
                    <AlertTriangle size={15} /> <span dir="auto">{w}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="ce-ok">
                <CheckCircle2 size={16} /> {t('Everything is ready.', 'همه‌چیز آماده است.')}
              </p>
            )}
          </Card>

          <Card title={t('Machine', 'مشخصات دستگاه')}>
            <div className="ce-stats ce-stats--compact">
              <Stat label={t('Operating system', 'سیستم‌عامل')} value={<Num>{data.system?.platform ?? '—'}</Num>} />
              <Stat label={t('Python', 'پایتون')} value={<Num>{data.system?.python_version ?? '—'}</Num>} />
              <Stat label={t('CPU cores', 'هسته پردازنده')} value={<Num>{data.system?.cpu_count ?? '—'}</Num>} />
              <Stat label={t('Memory', 'حافظه')} value={<><Num>{data.system?.memory_gb ?? '—'}</Num> {t('GB', 'گیگابایت')}</>} />
              <Stat label={t('Free space', 'فضای آزاد')} value={<><Num>{data.system?.disk_free_gb ?? '—'}</Num> {t('GB', 'گیگابایت')}</>} />
            </div>
          </Card>

          {backend && (
            <Card
              title={t('Processing service', 'سرویس پردازش')}
              tone={backend.running ? 'success' : 'danger'}
              extra={
                bridge?.restartBackend ? (
                  <button
                    className="ce-btn ce-btn--ghost ce-btn--sm"
                    onClick={() => bridge.restartBackend?.().then(() => bridge.backendStatus?.().then(setBackend))}
                  >
                    <RefreshCw size={15} /> {t('Restart', 'راه‌اندازی دوباره')}
                  </button>
                ) : undefined
              }
            >
              <div className="ce-kv">
                <span>{t('Status', 'وضعیت')}</span>
                <strong>
                  {backend.running
                    ? `${t('running', 'در حال اجرا')} (PID ${backend.pid})`
                    : t('not running', 'اجرا نمی‌شود')}
                </strong>
              </div>
              {backend.failure && (
                <div className="ce-kv">
                  <span>{t('Last error', 'آخرین خطا')}</span>
                  <strong className="ce-kv__wrap">{backend.failure}</strong>
                </div>
              )}
              {backend.tail.length > 0 && (
                <pre className="ce-logtail" dir="ltr">
                  {backend.tail.slice(-12).join('\n')}
                </pre>
              )}
            </Card>
          )}

          <Card
            title={t('Logs & diagnostics', 'گزارش‌ها و عیب‌یابی')}
            extra={
              bridge?.openLogFolder ? (
                <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => bridge.openLogFolder?.()}>
                  <FolderOpen size={15} /> {t('Open log folder', 'باز کردن پوشه گزارش')}
                </button>
              ) : undefined
            }
          >
            <div className="ce-kv">
              <span>{t('Log file', 'فایل گزارش')}</span>
              <strong className="ce-kv__wrap">
                <Num>{logPath ?? t('installed app only', 'فقط در نسخه نصب‌شده')}</Num>
              </strong>
            </div>
            <p className="ce-hint">
              {t(
                'Everything is recorded here: backend startup, UI errors and the full video-processing output. Attach this file when reporting a problem.',
                'همه‌چیز اینجا ثبت می‌شود: راه‌اندازی بک‌اند، خطاهای رابط کاربری و خروجی کامل پردازش ویدیو. هنگام گزارش مشکل، همین فایل را بفرست.'
              )}
            </p>
          </Card>

          <Card title="FFmpeg">
            <div className="ce-kv">
              <span>{t('Status', 'وضعیت')}</span>
              <strong>{data.ffmpeg?.found ? t('Found', 'پیدا شد') : t('Not found', 'پیدا نشد')}</strong>
            </div>
            <div className="ce-kv">
              <span>{t('Path', 'مسیر')}</span>
              <strong>
                <Num>{data.ffmpeg?.path ?? '—'}</Num>
              </strong>
            </div>
          </Card>
        </>
      )}
    </Page>
  )
}
