import type { ReactNode } from 'react'
import {
  Scissors, Captions, AudioLines, ScanFace, Mic, Images, Languages, Eraser,
  Sparkles, UploadCloud, Film, Stethoscope, Wand2, Music4, Type, Crop,
} from 'lucide-react'

export interface FeatureTile {
  id: string
  /** Label shown under the tile, English first. */
  label: [en: string, fa: string]
  hint: [en: string, fa: string]
  icon: ReactNode
  /** Two-stop gradient for the tile background. */
  gradient: string
  route: string
  badge?: 'new' | 'soon' | 'beta'
  /** Feature groups let the home screen stay readable as the catalog grows. */
  group: 'core' | 'ai' | 'polish' | 'publish' | 'system'
}

const ICON = { size: 26, strokeWidth: 2 } as const

export const FEATURES: FeatureTile[] = [
  // ---- core -------------------------------------------------------------
  {
    id: 'autoclip',
    label: ['Auto Clip', 'کلیپ خودکار'],
    hint: ['Long video to short clips', 'ویدیوی بلند → کلیپ‌های کوتاه'],
    icon: <Scissors {...ICON} />,
    gradient: 'linear-gradient(145deg,#6366F1,#8B5CF6)',
    route: '/new?preset=autoclip',
    group: 'core',
  },
  {
    id: 'timeline',
    label: ['Editor', 'میز تدوین'],
    hint: ['Cut, layers and timeline', 'برش، لایه و تایم‌لاین'],
    icon: <Film {...ICON} />,
    gradient: 'linear-gradient(145deg,#0EA5E9,#2563EB)',
    route: '/studio',
    group: 'core',
  },
  {
    id: 'reframe',
    label: ['Reframe', 'قاب عمودی'],
    hint: ['Turn 16:9 into 9:16', 'تبدیل ۱۶:۹ به ۹:۱۶'],
    icon: <Crop {...ICON} />,
    gradient: 'linear-gradient(145deg,#14B8A6,#0D9488)',
    route: '/new?preset=reframe',
    group: 'core',
  },
  {
    id: 'facetrack',
    label: ['Face Tracking', 'فیس‌ترکینگ'],
    hint: ['Keep the speaker in frame', 'قاب روی گوینده قفل می‌شود'],
    icon: <ScanFace {...ICON} />,
    gradient: 'linear-gradient(145deg,#22C55E,#16A34A)',
    route: '/new?preset=facetrack',
    badge: 'beta',
    group: 'core',
  },

  // ---- ai ---------------------------------------------------------------
  {
    id: 'subtitles',
    label: ['Smart Captions', 'زیرنویس هوشمند'],
    hint: ['Transcribe and animate', 'رونویسی + استایل متحرک'],
    icon: <Captions {...ICON} />,
    gradient: 'linear-gradient(145deg,#3B82F6,#1D4ED8)',
    route: '/new?preset=subtitles',
    group: 'ai',
  },
  {
    id: 'silence',
    label: ['Silence Removal', 'حذف سکوت'],
    hint: ['Cut pauses and filler words', 'مکث‌ها و اِاِ‌ها پاک می‌شوند'],
    icon: <AudioLines {...ICON} />,
    gradient: 'linear-gradient(145deg,#06B6D4,#0891B2)',
    route: '/new?preset=silence',
    badge: 'soon',
    group: 'ai',
  },
  {
    id: 'voiceover',
    label: ['Voice Over', 'وویس‌اوور'],
    hint: ['Text to speech narration', 'متن به گفتار با edge-tts'],
    icon: <Mic {...ICON} />,
    gradient: 'linear-gradient(145deg,#EC4899,#DB2777)',
    route: '/new?preset=voiceover',
    badge: 'soon',
    group: 'ai',
  },
  {
    id: 'broll',
    label: ['Auto B-Roll', 'بی‌رول خودکار'],
    hint: ['Relevant footage from Pexels', 'تصاویر مرتبط از Pexels'],
    icon: <Images {...ICON} />,
    gradient: 'linear-gradient(145deg,#F97316,#EA580C)',
    route: '/new?preset=broll',
    badge: 'soon',
    group: 'ai',
  },
  {
    id: 'translate',
    label: ['Translate & Dub', 'ترجمه و دوبله'],
    hint: ['Multilingual subs and audio', 'زیرنویس و صدای چندزبانه'],
    icon: <Languages {...ICON} />,
    gradient: 'linear-gradient(145deg,#8B5CF6,#6D28D9)',
    route: '/new?preset=translate',
    badge: 'soon',
    group: 'ai',
  },

  // ---- polish -----------------------------------------------------------
  {
    id: 'bgremove',
    label: ['Background Removal', 'حذف پس‌زمینه'],
    hint: ['Chroma key without a green screen', 'کروماکی بدون پرده سبز'],
    icon: <Eraser {...ICON} />,
    gradient: 'linear-gradient(145deg,#4F46E5,#4338CA)',
    route: '/studio?tool=bgremove',
    badge: 'soon',
    group: 'polish',
  },
  {
    id: 'enhance',
    label: ['Enhance', 'ارتقای کیفیت'],
    hint: ['Denoise and sharpen', 'نویزگیری و شارپ‌سازی'],
    icon: <Sparkles {...ICON} />,
    gradient: 'linear-gradient(145deg,#F59E0B,#D97706)',
    route: '/studio?tool=enhance',
    badge: 'soon',
    group: 'polish',
  },
  {
    id: 'titles',
    label: ['Titles & Text', 'تیتراژ و متن'],
    hint: ['Ready-made title templates', 'قالب‌های آماده تایتل'],
    icon: <Type {...ICON} />,
    gradient: 'linear-gradient(145deg,#E11D48,#BE123C)',
    route: '/studio?tool=titles',
    badge: 'soon',
    group: 'polish',
  },
  {
    id: 'music',
    label: ['Music & Mix', 'موسیقی و میکس'],
    hint: ['Automatic audio ducking', 'داکینگ خودکار صدا'],
    icon: <Music4 {...ICON} />,
    gradient: 'linear-gradient(145deg,#10B981,#059669)',
    route: '/studio?tool=music',
    badge: 'soon',
    group: 'polish',
  },

  // ---- publish / system -------------------------------------------------
  {
    id: 'uploads',
    label: ['Auto Publish', 'انتشار خودکار'],
    hint: ['YouTube, Instagram, Facebook', 'یوتیوب، اینستاگرام، فیس‌بوک'],
    icon: <UploadCloud {...ICON} />,
    gradient: 'linear-gradient(145deg,#EF4444,#DC2626)',
    route: '/uploads',
    group: 'publish',
  },
  {
    id: 'presets',
    label: ['My Presets', 'قالب‌های من'],
    hint: ['A consistent look for your brand', 'استایل ثابت برای برند شما'],
    icon: <Wand2 {...ICON} />,
    gradient: 'linear-gradient(145deg,#A855F7,#7E22CE)',
    route: '/settings?tab=presets',
    badge: 'soon',
    group: 'publish',
  },
  {
    id: 'doctor',
    label: ['System Health', 'سلامت سیستم'],
    hint: ['FFmpeg, GPU, disk space', 'FFmpeg، GPU، فضای دیسک'],
    icon: <Stethoscope {...ICON} />,
    gradient: 'linear-gradient(145deg,#64748B,#475569)',
    route: '/doctor',
    group: 'system',
  },
]

export const GROUP_TITLES: Record<FeatureTile['group'], [en: string, fa: string]> = {
  core: ['Create & Edit', 'ساخت و تدوین'],
  ai: ['Artificial Intelligence', 'هوش مصنوعی'],
  polish: ['Effects & Polish', 'جلوه و پرداخت'],
  publish: ['Publishing', 'انتشار'],
  system: ['System', 'سیستم'],
}

export const BADGE_LABELS: Record<NonNullable<FeatureTile['badge']>, [en: string, fa: string]> = {
  new: ['NEW', 'جدید'],
  soon: ['SOON', 'به‌زودی'],
  beta: ['BETA', 'بتا'],
}
