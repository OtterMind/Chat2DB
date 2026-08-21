import type { LangType } from '@/constants/settings';

const EN_US = 'en-US' as LangType;
const ZH_CN = 'zh-CN' as LangType;
const JA_JP = 'ja-JP' as LangType;
const ES_ES = 'es-ES' as LangType;
const KO_KR = 'ko-KR' as LangType;

export const languageOptions = [
  { value: ZH_CN, label: '简体中文' },
  { value: EN_US, label: 'English' },
  { value: JA_JP, label: '日本語' },
  { value: ES_ES, label: 'Español' },
  { value: KO_KR, label: '한국어' },
];

export function getAvailableLanguageOptions(restrictChineseOutsideChina: boolean, isCN: boolean) {
  if (restrictChineseOutsideChina && !isCN) {
    return languageOptions.filter((item) => item.value !== ZH_CN);
  }
  return languageOptions;
}

export function resolveCurrentLanguage(language: LangType, restrictChineseOutsideChina: boolean, isCN: boolean) {
  if (restrictChineseOutsideChina && !isCN && language === ZH_CN) {
    return EN_US;
  }
  return language;
}
