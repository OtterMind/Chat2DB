import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { RefreshCw, CheckCircle2, AlertTriangle, FolderOpen } from 'lucide-react'
import Page, { Card, Num, Stat } from '../components/Page'
import { systemApi } from '../api/jobs'

interface Diagnostics {
  healthy?: boolean
  warnings?: string[]
  system?: { platform?: string; python_version?: string; cpu_count?: number; memory_gb?: number; disk_free_gb?: number }
  ffmpeg?: { found?: boolean; path?: string | null }
}

interface DesktopBridge {
  logPath?: () => Promise<string>
  openLogFolder?: () => void
}

export default function Doctor() {
  const [logPath, setLogPath] = useState<string | null>(null)
  const bridge = (window as unknown as { cuttingEdge?: DesktopBridge }).cuttingEdge

  useEffect(() => {
    bridge?.logPath?.().then(setLogPath).catch(() => undefined)
  }, [bridge])

  const { data, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['doctor'],
    queryFn: () => systemApi.doctor() as Promise<Diagnostics>,
  })

  return (
    <Page
      title="سلامت سیستم"
      subtitle="بررسی پیش‌نیازهای پردازش ویدیو روی این دستگاه"
      actions={
        <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => refetch()} disabled={isFetching}>
          <RefreshCw size={15} className={isFetching ? 'ce-spin' : ''} /> بررسی دوباره
        </button>
      }
    >
      {isLoading && <div className="ce-empty">در حال بررسی…</div>}

      {data && (
        <>
          <Card
            title="وضعیت کلی"
            tone={data.healthy ? 'success' : 'danger'}
            extra={
              <span className={`ce-badge ${data.healthy ? 'ce-badge--ok' : 'ce-badge--warn'}`}>
                {data.healthy ? 'سالم' : 'نیازمند توجه'}
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
                <CheckCircle2 size={16} /> همه‌چیز آماده است.
              </p>
            )}
          </Card>

          <Card title="مشخصات دستگاه">
            <div className="ce-stats ce-stats--compact">
              <Stat label="سیستم‌عامل" value={<Num>{data.system?.platform ?? '—'}</Num>} />
              <Stat label="پایتون" value={<Num>{data.system?.python_version ?? '—'}</Num>} />
              <Stat label="هسته پردازنده" value={<Num>{data.system?.cpu_count ?? '—'}</Num>} />
              <Stat label="حافظه" value={<><Num>{data.system?.memory_gb ?? '—'}</Num> گیگابایت</>} />
              <Stat label="فضای آزاد" value={<><Num>{data.system?.disk_free_gb ?? '—'}</Num> گیگابایت</>} />
            </div>
          </Card>

          <Card
            title="گزارش‌ها و عیب‌یابی"
            extra={
              bridge?.openLogFolder ? (
                <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => bridge.openLogFolder?.()}>
                  <FolderOpen size={15} /> باز کردن پوشه گزارش
                </button>
              ) : undefined
            }
          >
            <div className="ce-kv">
              <span>فایل گزارش</span>
              <strong className="ce-kv__wrap">
                <Num>{logPath ?? 'فقط در نسخه نصب‌شده'}</Num>
              </strong>
            </div>
            <p className="ce-hint">
              همه‌چیز اینجا ثبت می‌شود: راه‌اندازی بک‌اند، خطاهای رابط کاربری و خروجی کامل پردازش
              ویدیو. هنگام گزارش مشکل، همین فایل را بفرست.
            </p>
          </Card>

          <Card title="FFmpeg">
            <div className="ce-kv">
              <span>وضعیت</span>
              <strong>{data.ffmpeg?.found ? 'پیدا شد' : 'پیدا نشد'}</strong>
            </div>
            <div className="ce-kv">
              <span>مسیر</span>
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
