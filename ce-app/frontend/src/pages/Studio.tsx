import { useSearchParams, useNavigate } from 'react-router-dom'
import { Clapperboard } from 'lucide-react'
import Page from '../components/Page'

const TOOLS: Record<string, { title: string; body: string }> = {
  bgremove: { title: 'حذف پس‌زمینه', body: 'جداسازی سوژه بدون پرده سبز با مدل‌های متن‌باز.' },
  enhance: { title: 'ارتقای کیفیت', body: 'نویزگیری، شارپ‌سازی و بازسازی رزولوشن.' },
  titles: { title: 'تیتراژ و متن', body: 'قالب‌های آماده تایتل با انیمیشن.' },
  music: { title: 'موسیقی و میکس', body: 'داکینگ خودکار موسیقی زیر گفتار.' },
}

export default function Studio() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const info = params.get('tool') ? TOOLS[params.get('tool')!] : undefined

  return (
    <Page title={info?.title ?? 'میز تدوین'} subtitle="تایم‌لاین چندلایه، برش و پیش‌نمایش زنده">
      <div className="ce-soon">
        <Clapperboard size={44} />
        <h3>{info?.title ?? 'تایم‌لاین چندلایه'} — در دست ساخت</h3>
        <p>{info?.body ?? 'برش، جابه‌جایی، لایه‌های ویدیو/صدا/متن و پیش‌نمایش زنده.'}</p>
        <p className="ce-soon__note">
          تا آماده شدن این بخش، از «کلیپ خودکار» استفاده کن؛ خروجی همان‌جا قابل بازبینی و اصلاح است.
        </p>
        <button className="ce-btn" onClick={() => navigate('/new')}>ساخت پروژه جدید</button>
      </div>
    </Page>
  )
}
