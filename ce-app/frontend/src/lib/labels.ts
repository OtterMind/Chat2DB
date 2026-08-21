/** Shared Persian labels so no screen shows a raw backend key. */
export const STATUS_FA: Record<string, string> = {
  pending: 'در صف',
  queued: 'در صف',
  processing: 'در حال پردازش',
  done: 'آماده',
  failed: 'ناموفق',
  cancelled: 'لغو شده',
  selected: 'انتخاب‌شده',
  rejected: 'رد شده',
  published: 'منتشر شده',
}

export const STAGE_FA: Record<string, string> = {
  ingest: 'دریافت ویدیو',
  prepare: 'آماده‌سازی',
  transcribe: 'رونویسی گفتار',
  select: 'انتخاب لحظه‌ها',
  reframe: 'قاب‌بندی',
  subtitle: 'زیرنویس',
  export: 'خروجی گرفتن',
}

export function statusFa(status?: string | null) {
  if (!status) return '—'
  return STATUS_FA[status] ?? status
}

export function stageFa(stage?: string | null) {
  if (!stage) return null
  return STAGE_FA[stage] ?? stage
}

/** 83.4 -> "۱:۲۳" style timecode (kept LTR by the <Num> wrapper). */
export function timecode(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = Math.floor(seconds % 60)
  return `${m}:${s.toString().padStart(2, '0')}`
}
