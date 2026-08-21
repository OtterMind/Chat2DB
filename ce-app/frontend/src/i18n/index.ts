import { create } from 'zustand'
import faIR from 'antd/locale/fa_IR'
import enUS from 'antd/locale/en_US'

export type Lang = 'en' | 'fa'

const STORAGE_KEY = 'ce.lang'

function initialLang(): Lang {
  if (typeof localStorage !== 'undefined') {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved === 'en' || saved === 'fa') return saved
  }
  return 'en'
}

interface LangState {
  lang: Lang
  setLang: (lang: Lang) => void
}

export const useLangStore = create<LangState>((set) => ({
  lang: initialLang(),
  setLang: (lang) => {
    if (typeof localStorage !== 'undefined') localStorage.setItem(STORAGE_KEY, lang)
    set({ lang })
  },
}))

/**
 * Deliberately key-less translation.
 *
 * With exactly two languages, `t('Projects', 'پروژه‌ها')` keeps the English source
 * readable at the call site and makes it impossible for a key to drift out of sync
 * with the text it names. English is the default; Persian is the translation.
 */
export function useI18n() {
  const lang = useLangStore((s) => s.lang)
  const setLang = useLangStore((s) => s.setLang)
  return {
    lang,
    setLang,
    isFa: lang === 'fa',
    dir: (lang === 'fa' ? 'rtl' : 'ltr') as 'rtl' | 'ltr',
    antdLocale: lang === 'fa' ? faIR : enUS,
    t: (en: string, fa: string) => (lang === 'fa' ? fa : en),
  }
}

/** For non-React modules (stores, services). */
export function translate(en: string, fa: string) {
  return useLangStore.getState().lang === 'fa' ? fa : en
}
