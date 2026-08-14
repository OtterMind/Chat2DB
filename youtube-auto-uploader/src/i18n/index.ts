import en from './en.json';
import fa from './fa.json';
import { useStore } from '@/lib/store';

export type TranslationKey = keyof typeof en;
export function useI18n(): { t: (key: TranslationKey) => string; language: 'en' | 'fa' } {
  const language = useStore((state) => state.settings?.language ?? 'en');
  const dictionary: Record<TranslationKey, string> = language === 'fa' ? fa : en;
  return { language, t: (key) => dictionary[key] ?? en[key] };
}
