import type i18n from '@/i18n';
import type { SqlxDatasourceState, SqlxDatasourceStateValue } from '@/typings/settings';

type I18nKey = Parameters<typeof i18n>[0];

/** Badge text for one datasource: imported, ready to import, or blocked with a reason. */
export function datasourceStateLabelKey(state: SqlxDatasourceStateValue | undefined): I18nKey {
  switch (state) {
    case 'imported':
      return 'setting.sqlx.datasourceState.imported';
    case 'ready':
      return 'setting.sqlx.datasourceState.ready';
    case 'unsupported':
      return 'setting.sqlx.datasourceState.unsupported';
    default:
      return 'setting.sqlx.datasourceState.incomplete';
  }
}

/** Colour of that badge: a finished import, an actionable row, and a warning for what cannot be copied. */
export function datasourceStateColor(state: SqlxDatasourceStateValue | undefined): 'success' | 'default' | 'warning' {
  if (state === 'imported') {
    return 'success';
  }
  return state === 'ready' || state === undefined ? 'default' : 'warning';
}

/**
 * Whether a row can be selected for import.
 * <p>
 * Only a datasource SQLX does not hold yet is actionable: an imported row has nothing left to do, and a
 * blocked row can never be copied. Rows keep their checkbox disabled until their state is known.
 */
export function isDatasourceSelectable(state: SqlxDatasourceStateValue | undefined): boolean {
  return state === 'ready';
}

/** i18n key explaining a blocked row, or {@code null} when the badge already says everything. */
export function datasourceStateHintKey(state: SqlxDatasourceState | undefined): I18nKey | null {
  if (!state) {
    return null;
  }
  if (state.state === 'imported') {
    return 'setting.sqlx.datasourceState.importedHint';
  }
  if (state.state === 'unsupported') {
    return 'setting.sqlx.datasourceState.unsupportedHint';
  }
  return null;
}
