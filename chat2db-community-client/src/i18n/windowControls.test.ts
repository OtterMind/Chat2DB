import assert from 'node:assert/strict';

import enUS from './en-US/common';
import esES from './es-ES/common';
import jaJP from './ja-JP/common';
import koKR from './ko-KR/common';
import zhCN from './zh-CN/common';

const WINDOW_CONTROL_KEYS = [
  'common.window.minimize',
  'common.window.maximize',
  'common.window.restore',
  'common.window.close',
] as const;

const localeCommonModules: Record<string, Record<string, string>> = {
  'en-US': enUS,
  'zh-CN': zhCN,
  'ja-JP': jaJP,
  'ko-KR': koKR,
  'es-ES': esES,
};

for (const [locale, common] of Object.entries(localeCommonModules)) {
  for (const key of WINDOW_CONTROL_KEYS) {
    const value = common[key];
    assert.notEqual(
      value,
      undefined,
      `${locale}/common.ts must define ${key} for the title bar window controls`,
    );
    assert.equal(typeof value, 'string', `${locale}/common.ts: ${key} must be a string`);
    assert.ok(value!.trim().length > 0, `${locale}/common.ts: ${key} must not be empty`);
  }
}

console.log('windowControls.test.ts: all locales define the window control keys');
