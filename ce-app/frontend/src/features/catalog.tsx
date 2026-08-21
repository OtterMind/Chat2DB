import type { ReactNode } from 'react'
import {
  Scissors, Captions, AudioLines, ScanFace, Mic, Images, Languages, Eraser,
  Sparkles, UploadCloud, Film, Stethoscope, Wand2, Music4, Type, Crop,
} from 'lucide-react'

export interface FeatureTile {
  id: string
  /** Persian label shown under the tile. */
  label: string
  hint: string
  icon: ReactNode
  /** Two-stop gradient for the tile background. */
  gradient: string
  route: string
  badge?: 'جدید' | 'به‌زودی' | 'بتا'
  /** Feature groups let the home screen stay readable as the catalog grows. */
  group: 'core' | 'ai' | 'polish' | 'publish' | 'system'
}

const ICON = { size: 26, strokeWidth: 2 } as const

export const FEATURES: FeatureTile[] = [
  // ---- core -------------------------------------------------------------
  {
    id: 'autoclip',
    label: 'کلیپ خودکار',
    hint: 'ویدیوی بلند → کلیپ‌های کوتاه',
    icon: <Scissors {...ICON} />,
    gradient: 'linear-gradient(145deg,#6366F1,#8B5CF6)',
    route: '/new?preset=autoclip',
    group: 'core',
  },
  {
    id: 'timeline',
    label: 'میز تدوین',
    hint: 'برش، لایه و تایم‌لاین',
    icon: <Film {...ICON} />,
    gradient: 'linear-gradient(145deg,#0EA5E9,#2563EB)',
    route: '/studio',
    badge: 'به‌زودی',
    group: 'core',
  },
  {
    id: 'reframe',
    label: 'قاب عمودی',
    hint: 'تبدیل ۱۶:۹ به ۹:۱۶',
    icon: <Crop {...ICON} />,
    gradient: 'linear-gradient(145deg,#14B8A6,#0D9488)',
    route: '/new?preset=reframe',
    group: 'core',
  },
  {
    id: 'facetrack',
    label: 'فیس‌ترکینگ',
    hint: 'قاب روی گوینده قفل می‌شود',
    icon: <ScanFace {...ICON} />,
    gradient: 'linear-gradient(145deg,#22C55E,#16A34A)',
    route: '/new?preset=facetrack',
    badge: 'بتا',
    group: 'core',
  },

  // ---- ai ---------------------------------------------------------------
  {
    id: 'subtitles',
    label: 'زیرنویس هوشمند',
    hint: 'رونویسی + استایل متحرک',
    icon: <Captions {...ICON} />,
    gradient: 'linear-gradient(145deg,#3B82F6,#1D4ED8)',
    route: '/new?preset=subtitles',
    group: 'ai',
  },
  {
    id: 'silence',
    label: 'حذف سکوت',
    hint: 'مکث‌ها و اِاِ‌ها پاک می‌شوند',
    icon: <AudioLines {...ICON} />,
    gradient: 'linear-gradient(145deg,#06B6D4,#0891B2)',
    route: '/new?preset=silence',
    badge: 'به‌زودی',
    group: 'ai',
  },
  {
    id: 'voiceover',
    label: 'وویس‌اوور',
    hint: 'متن به گفتار با edge-tts',
    icon: <Mic {...ICON} />,
    gradient: 'linear-gradient(145deg,#EC4899,#DB2777)',
    route: '/new?preset=voiceover',
    badge: 'به‌زودی',
    group: 'ai',
  },
  {
    id: 'broll',
    label: 'بی‌رول خودکار',
    hint: 'تصاویر مرتبط از Pexels',
    icon: <Images {...ICON} />,
    gradient: 'linear-gradient(145deg,#F97316,#EA580C)',
    route: '/new?preset=broll',
    badge: 'به‌زودی',
    group: 'ai',
  },
  {
    id: 'translate',
    label: 'ترجمه و دوبله',
    hint: 'زیرنویس و صدای چندزبانه',
    icon: <Languages {...ICON} />,
    gradient: 'linear-gradient(145deg,#8B5CF6,#6D28D9)',
    route: '/new?preset=translate',
    badge: 'به‌زودی',
    group: 'ai',
  },

  // ---- polish -----------------------------------------------------------
  {
    id: 'bgremove',
    label: 'حذف پس‌زمینه',
    hint: 'کروماکی بدون پرده سبز',
    icon: <Eraser {...ICON} />,
    gradient: 'linear-gradient(145deg,#4F46E5,#4338CA)',
    route: '/studio?tool=bgremove',
    badge: 'به‌زودی',
    group: 'polish',
  },
  {
    id: 'enhance',
    label: 'ارتقای کیفیت',
    hint: 'نویزگیری و شارپ‌سازی',
    icon: <Sparkles {...ICON} />,
    gradient: 'linear-gradient(145deg,#F59E0B,#D97706)',
    route: '/studio?tool=enhance',
    badge: 'به‌زودی',
    group: 'polish',
  },
  {
    id: 'titles',
    label: 'تیتراژ و متن',
    hint: 'قالب‌های آماده تایتل',
    icon: <Type {...ICON} />,
    gradient: 'linear-gradient(145deg,#E11D48,#BE123C)',
    route: '/studio?tool=titles',
    badge: 'به‌زودی',
    group: 'polish',
  },
  {
    id: 'music',
    label: 'موسیقی و میکس',
    hint: 'داکینگ خودکار صدا',
    icon: <Music4 {...ICON} />,
    gradient: 'linear-gradient(145deg,#10B981,#059669)',
    route: '/studio?tool=music',
    badge: 'به‌زودی',
    group: 'polish',
  },

  // ---- publish / system -------------------------------------------------
  {
    id: 'uploads',
    label: 'انتشار خودکار',
    hint: 'یوتیوب، اینستاگرام، فیس‌بوک',
    icon: <UploadCloud {...ICON} />,
    gradient: 'linear-gradient(145deg,#EF4444,#DC2626)',
    route: '/uploads',
    group: 'publish',
  },
  {
    id: 'presets',
    label: 'قالب‌های من',
    hint: 'استایل ثابت برای برند شما',
    icon: <Wand2 {...ICON} />,
    gradient: 'linear-gradient(145deg,#A855F7,#7E22CE)',
    route: '/settings?tab=presets',
    badge: 'به‌زودی',
    group: 'publish',
  },
  {
    id: 'doctor',
    label: 'سلامت سیستم',
    hint: 'FFmpeg، GPU، فضای دیسک',
    icon: <Stethoscope {...ICON} />,
    gradient: 'linear-gradient(145deg,#64748B,#475569)',
    route: '/doctor',
    group: 'system',
  },
]

export const GROUP_TITLES: Record<FeatureTile['group'], string> = {
  core: 'ساخت و تدوین',
  ai: 'هوش مصنوعی',
  polish: 'جلوه و پرداخت',
  publish: 'انتشار',
  system: 'سیستم',
}
