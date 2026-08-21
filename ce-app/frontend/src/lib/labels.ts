import type { Lang } from '../i18n'

type Pair = [en: string, fa: string]

/** Backend keys are never shown raw; both languages live next to each other. */
const STATUS: Record<string, Pair> = {
  pending: ['Queued', 'در صف'],
  queued: ['Queued', 'در صف'],
  processing: ['Processing', 'در حال پردازش'],
  done: ['Ready', 'آماده'],
  failed: ['Failed', 'ناموفق'],
  cancelled: ['Cancelled', 'لغو شده'],
  selected: ['Selected', 'انتخاب‌شده'],
  rejected: ['Rejected', 'رد شده'],
  published: ['Published', 'منتشر شده'],
}

const STAGE: Record<string, Pair> = {
  ingest: ['Fetching video', 'دریافت ویدیو'],
  prepare: ['Preparing', 'آماده‌سازی'],
  transcribe: ['Transcribing', 'رونویسی گفتار'],
  select: ['Picking moments', 'انتخاب لحظه‌ها'],
  reframe: ['Reframing', 'قاب‌بندی'],
  subtitle: ['Subtitling', 'زیرنویس'],
  export: ['Exporting', 'خروجی گرفتن'],
  render: ['Rendering', 'رندر'],
}

const idx = (lang: Lang) => (lang === 'fa' ? 1 : 0)

export function statusLabel(status: string | null | undefined, lang: Lang) {
  if (!status) return '—'
  return STATUS[status]?.[idx(lang)] ?? status
}

export function stageLabel(stage: string | null | undefined, lang: Lang) {
  if (!stage) return null
  return STAGE[stage]?.[idx(lang)] ?? stage
}

/** 83.4 -> "1:23" */
export function timecode(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = Math.floor(seconds % 60)
  return `${m}:${s.toString().padStart(2, '0')}`
}
