import zh from '@/i18n/zh-CN/common';
import en from '@/i18n/en-US/common';
import { LARGE_CELL_ERROR_CODE, LARGE_CELL_ERROR_MESSAGE } from './largeCellValue';
import { getLargeCellMessageKey } from './largeCellValueErrorMap';

function assertEqual(actual: any, expected: any, message: string) {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

// 1) Every backend error code must have a mapping, otherwise the raw i18n key reaches the user.
for (const code of Object.values(LARGE_CELL_ERROR_CODE)) {
  const messageKey = getLargeCellMessageKey(code);
  if (!messageKey) {
    throw new Error(`missing message mapping for ${code}`);
  }
  if (!(messageKey in en) || !(messageKey in zh)) {
    throw new Error(`i18n string missing for ${messageKey}`);
  }
}

// 2) The backend sends errorMessage as "<i18n key> : no message.", which must also resolve.
assertEqual(
  getLargeCellMessageKey('largeCellValue.snapshotExpired : no message.'),
  LARGE_CELL_ERROR_MESSAGE.SNAPSHOT_EXPIRED,
  'raw backend message resolves to a message key',
);
assertEqual(
  getLargeCellMessageKey('  largeCellValue.tokenExpired  '),
  LARGE_CELL_ERROR_MESSAGE.TOKEN_EXPIRED,
  'trimmed key resolves as well',
);

// 3) Unknown codes and free text stay unmapped so the caller can fall back.
assertEqual(getLargeCellMessageKey(undefined), undefined, 'undefined has no mapping');
assertEqual(getLargeCellMessageKey('common.paramError'), undefined, 'unrelated code has no mapping');
assertEqual(getLargeCellMessageKey('some custom backend text'), undefined, 'free text has no mapping');

// 4) The mapping must cover the backend contract, including the result snapshot keys.
const BACKEND_KEYS = [
  'largeCellValue.tokenExpired',
  'largeCellValue.snapshotExpired',
  'largeCellValue.tokenRequired',
  'largeCellValue.fullValueUnsupported',
  'largeCellValue.readFailed',
  'largeCellValue.downloadFailed',
  'largeCellValue.partialPreviewEditRejected',
  'largeCellValue.unsupportedFormat',
  'largeCellValue.snapshotCellMissing',
  'largeCellValue.snapshotReadFailed',
  'largeCellValue.snapshotWriteFailed',
];
for (const key of BACKEND_KEYS) {
  const messageKey = getLargeCellMessageKey(key);
  if (!messageKey) {
    throw new Error(`backend key without mapping: ${key}`);
  }
}

console.log('largeCellValueMessage tests passed');
