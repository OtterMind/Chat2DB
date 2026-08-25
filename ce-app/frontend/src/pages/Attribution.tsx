import { useEffect, useState } from 'react'
import Page, { Card } from '../components/Page'
import { systemApi } from '../api/jobs'
import { useI18n } from '../i18n'
import { Heart } from 'lucide-react'

/**
 * The credit screen.
 *
 * A video editor stands on FFmpeg, a Python runtime, a speech model and a dozen
 * libraries. This lists every one with its licence — the backend half read live
 * from installed package metadata (so it cannot drift from what ships), the
 * bundled tools named beside them. It is the 1.0 criterion "every shipped
 * package listed with its licence", as a screen a person can read.
 */
export default function Attribution() {
  const { t } = useI18n()
  const [data, setData] = useState<Awaited<ReturnType<typeof systemApi.attribution>> | null>(null)

  useEffect(() => {
    systemApi.attribution().then(setData).catch(() => setData(null))
  }, [])

  return (
    <Page
      title={t('Credits & licences', 'اعتبارها و پروانه‌ها')}
      subtitle={t(
        'Cutting Edge is built on the work of these projects. Thank you.',
        'کاتینگ اِج روی دوش این پروژه‌ها ساخته شده. سپاس.'
      )}
      width="md"
      back
    >
      <Card title={t('Bundled', 'همراه برنامه')}>
        <div className="ce-kv" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
          {(data?.bundled ?? []).map((entry) => (
            <div key={entry.name} className="attr-row">
              <strong>{entry.name}</strong>
              <span className="ce-hint">{entry.why}</span>
              <span dir="ltr" className="ce-badge">{entry.licence}</span>
            </div>
          ))}
        </div>
      </Card>

      <Card title={t('Python runtime', 'رانتایم پایتون')}>
        <div className="ce-kv" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
          {(data?.backend ?? []).map((entry) => (
            <div key={entry.name} className="attr-row">
              <strong dir="ltr">{entry.name} {entry.version}</strong>
              <span dir="ltr" className="ce-badge">{entry.licence}</span>
            </div>
          ))}
        </div>
        <p className="ce-hint" style={{ marginTop: 10 }}>
          <Heart size={13} />{' '}
          {t(
            'Optional engines you fetch (silero-vad, RapidOCR) are credited on their own cards; they are yours, not shipped.',
            'موتورهای اختیاری که خودت می‌گیری (silero-vad، RapidOCR) در کارت‌های خودشان credited شده‌اند؛ آن‌ها مال تو‌اند، نه داخل برنامه.'
          )}
        </p>
      </Card>
    </Page>
  )
}
