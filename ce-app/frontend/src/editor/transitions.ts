/**
 * Transition catalogue.
 *
 * Every id is a real FFmpeg `xfade` transition, so the preview label and the
 * rendered result can never drift apart — the compositor passes the id straight
 * through to the filter.
 */
export interface TransitionKind {
  id: string
  label: [en: string, fa: string]
}

export const TRANSITIONS: TransitionKind[] = [
  { id: 'fade', label: ['Fade', 'محو'] },
  { id: 'fadeblack', label: ['Fade to black', 'محو به سیاه'] },
  { id: 'fadewhite', label: ['Fade to white', 'محو به سفید'] },
  { id: 'dissolve', label: ['Dissolve', 'حل شدن'] },
  { id: 'wipeleft', label: ['Wipe left', 'پاک‌شو چپ'] },
  { id: 'wiperight', label: ['Wipe right', 'پاک‌شو راست'] },
  { id: 'wipeup', label: ['Wipe up', 'پاک‌شو بالا'] },
  { id: 'wipedown', label: ['Wipe down', 'پاک‌شو پایین'] },
  { id: 'slideleft', label: ['Slide left', 'لغزش چپ'] },
  { id: 'slideright', label: ['Slide right', 'لغزش راست'] },
  { id: 'slideup', label: ['Slide up', 'لغزش بالا'] },
  { id: 'slidedown', label: ['Slide down', 'لغزش پایین'] },
  { id: 'smoothleft', label: ['Smooth left', 'نرم چپ'] },
  { id: 'smoothright', label: ['Smooth right', 'نرم راست'] },
  { id: 'circlecrop', label: ['Circle crop', 'دایره'] },
  { id: 'circleopen', label: ['Circle open', 'باز شدن دایره'] },
  { id: 'circleclose', label: ['Circle close', 'بسته شدن دایره'] },
  { id: 'rectcrop', label: ['Rectangle', 'مستطیل'] },
  { id: 'radial', label: ['Radial', 'شعاعی'] },
  { id: 'pixelize', label: ['Pixelize', 'پیکسلی'] },
  { id: 'hlslice', label: ['Slice', 'برش افقی'] },
  { id: 'vuslice', label: ['Slice up', 'برش عمودی'] },
  { id: 'diagtl', label: ['Diagonal', 'مورب'] },
  { id: 'squeezeh', label: ['Squeeze', 'فشرده'] },
  { id: 'zoomin', label: ['Zoom in', 'زوم'] },
  { id: 'distance', label: ['Distance', 'فاصله'] },
  { id: 'hblur', label: ['Blur', 'محو حرکتی'] },
  { id: 'wipetl', label: ['Wipe corner', 'پاک‌شو گوشه'] },
]
