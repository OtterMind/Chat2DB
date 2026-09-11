import type { LangType } from '@/constants/settings';

export interface I18nRuntimeState {
  language?: LangType;
  isCN?: boolean;
}

type I18nRuntimeStateReader = () => I18nRuntimeState;

let stateReader: I18nRuntimeStateReader | undefined;

export function registerI18nStateReader(reader: I18nRuntimeStateReader) {
  stateReader = reader;
}

export function getI18nRuntimeState(): I18nRuntimeState {
  return stateReader?.() || {};
}
