import assert from 'node:assert/strict';
import type { LangType } from '@/constants/settings';
import { getAvailableLanguageOptions, languageOptions, resolveCurrentLanguage } from './model';

assert.equal(
  getAvailableLanguageOptions(false, false).length,
  languageOptions.length,
  'unrestricted editions expose every supported language',
);
assert.equal(
  getAvailableLanguageOptions(true, false).some((item) => item.value === 'zh-CN'),
  false,
  'region-restricted non-CN editions hide Simplified Chinese',
);
assert.equal(
  getAvailableLanguageOptions(true, true).some((item) => item.value === 'zh-CN'),
  true,
  'region-restricted CN editions keep Simplified Chinese available',
);
assert.equal(
  resolveCurrentLanguage('zh-CN' as LangType, true, false),
  'en-US',
  'a persisted Simplified Chinese preference falls back to English when it is unavailable',
);
assert.equal(
  resolveCurrentLanguage('ja-JP' as LangType, true, false),
  'ja-JP',
  'available persisted languages remain unchanged',
);

console.log('Base settings model tests passed.');
