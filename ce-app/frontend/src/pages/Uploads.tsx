import { Youtube, Facebook, Instagram, Info } from 'lucide-react'
import Page, { Card } from '../components/Page'

const PLATFORMS = [
  { id: 'youtube', label: 'یوتیوب', icon: <Youtube size={20} />, color: '#EF4444', ready: false },
  { id: 'instagram', label: 'اینستاگرام', icon: <Instagram size={20} />, color: '#EC4899', ready: false },
  { id: 'facebook', label: 'فیس‌بوک', icon: <Facebook size={20} />, color: '#3B82F6', ready: false },
]

export default function Uploads() {
  return (
    <Page title="انتشار خودکار" subtitle="کلیپ‌های آماده را مستقیم روی شبکه‌های اجتماعی منتشر کن">
      <Card title="حساب‌های متصل">
        <div className="ce-accounts">
          {PLATFORMS.map((p) => (
            <div key={p.id} className="ce-account">
              <span className="ce-account__icon" style={{ background: p.color }}>
                {p.icon}
              </span>
              <span className="ce-account__name">{p.label}</span>
              <span className="ce-badge ce-badge--muted">متصل نیست</span>
              <button className="ce-btn ce-btn--ghost ce-btn--sm" disabled>
                اتصال
              </button>
            </div>
          ))}
        </div>
      </Card>

      <Card title="تاریخچه انتشار">
        <div className="ce-empty">هنوز چیزی منتشر نشده است.</div>
      </Card>

      <div className="ce-note">
        <Info size={16} />
        <span>
          اتصال حساب‌ها در فاز ۴ نقشه‌راه فعال می‌شود؛ تا آن زمان می‌توانی خروجی‌ها را از صفحه‌ی هر
          پروژه دانلود و دستی منتشر کنی.
        </span>
      </div>
    </Page>
  )
}
