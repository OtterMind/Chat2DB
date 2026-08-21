import { Youtube, Facebook, Instagram, Info } from 'lucide-react'
import Page, { Card } from '../components/Page'
import { useI18n } from '../i18n'

const PLATFORMS = [
  { id: 'youtube', label: ['YouTube', 'یوتیوب'] as const, icon: <Youtube size={20} />, color: '#EF4444' },
  { id: 'instagram', label: ['Instagram', 'اینستاگرام'] as const, icon: <Instagram size={20} />, color: '#EC4899' },
  { id: 'facebook', label: ['Facebook', 'فیس‌بوک'] as const, icon: <Facebook size={20} />, color: '#3B82F6' },
]

export default function Uploads() {
  const { t, lang } = useI18n()
  const i = lang === 'fa' ? 1 : 0
  return (
    <Page
      title={t('Auto publishing', 'انتشار خودکار')}
      subtitle={t('Publish finished clips straight to social platforms', 'کلیپ‌های آماده را مستقیم روی شبکه‌های اجتماعی منتشر کن')}
    >
      <Card title={t('Connected accounts', 'حساب‌های متصل')}>
        <div className="ce-accounts">
          {PLATFORMS.map((p) => (
            <div key={p.id} className="ce-account">
              <span className="ce-account__icon" style={{ background: p.color }}>
                {p.icon}
              </span>
              <span className="ce-account__name">{p.label[i]}</span>
              <span className="ce-badge ce-badge--muted">{t('Not connected', 'متصل نیست')}</span>
              <button className="ce-btn ce-btn--ghost ce-btn--sm" disabled>
                {t('Connect', 'اتصال')}
              </button>
            </div>
          ))}
        </div>
      </Card>

      <Card title={t('Publishing history', 'تاریخچه انتشار')}>
        <div className="ce-empty">{t('Nothing published yet.', 'هنوز چیزی منتشر نشده است.')}</div>
      </Card>

      <div className="ce-note">
        <Info size={16} />
        <span>
          {t(
            'Account linking arrives in phase 4 of the roadmap; until then you can download each project’s output and publish manually.',
            'اتصال حساب‌ها در فاز ۴ نقشه‌راه فعال می‌شود؛ تا آن زمان می‌توانی خروجی‌ها را از صفحه‌ی هر پروژه دانلود و دستی منتشر کنی.'
          )}
        </span>
      </div>
    </Page>
  )
}
