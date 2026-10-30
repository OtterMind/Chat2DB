import type { ITable } from '@/typings';

/**
 * Target table selection of the import wizard.
 *
 * The wizard lets the user change the import target on the file step. The table list, the options
 * rendered for it, and the state transition a switch triggers are pure decisions, so they live
 * here; the React state stays in the wizard. A switch changes the target columns, which is why it
 * always invalidates the mapping and the preview that were built for the previous table.
 */

/** One request of the table list; the dropdown loads the next page when the user scrolls to it. */
export const IMPORT_TARGET_TABLE_PAGE_SIZE = 200;

export interface ImportTargetTableOption {
  value: string;
  label: string;
}

export interface ImportTargetTableSwitchPlan {
  /** The table the wizard keeps as the import target. */
  tableName: string;
  /** True when the switch invalidates the mapping the user already picked. */
  mappingCleared: boolean;
  /** True when the loaded preview belongs to the previous table and has to be requested again. */
  previewReloadRequired: boolean;
  /** True when the user has to be told that the mapping for the previous table was dropped. */
  notifyMappingCleared: boolean;
}

const asTableName = (value?: string | null): string => (typeof value === 'string' ? value.trim() : '');

/** Tables of the current database as dropdown options; blank or duplicated names are dropped. */
export const toImportTargetTableOptions = (tables?: ITable[] | null): ImportTargetTableOption[] => {
  const seen = new Set<string>();
  const options: ImportTargetTableOption[] = [];
  (tables ?? []).forEach((table) => {
    const value = asTableName(table?.name);
    if (!value || seen.has(value)) {
      return;
    }
    seen.add(value);
    options.push({ value, label: value });
  });
  return options;
};

/** Appends a page without duplicating a table of the previous one. */
export const appendImportTargetTableOptions = (
  current: ImportTargetTableOption[],
  incoming: ImportTargetTableOption[],
): ImportTargetTableOption[] => {
  const seen = new Set(current.map((option) => option.value));
  const appended = incoming.filter((option) => !seen.has(option.value));
  return appended.length ? [...current, ...appended] : current;
};

/**
 * Plans a target table switch. Selecting the same table, or no table at all, changes nothing;
 * a real switch drops the mapping of the previous table, asks for a new preview, and reports back
 * to the user whenever a file was already selected and a mapping could have existed.
 */
export const planImportTargetTableSwitch = (input: {
  previousTableName?: string | null;
  nextTableName?: string | null;
  fileSelected: boolean;
}): ImportTargetTableSwitchPlan => {
  const previousTableName = asTableName(input.previousTableName);
  const nextTableName = asTableName(input.nextTableName);
  if (!nextTableName || nextTableName === previousTableName) {
    return {
      tableName: previousTableName,
      mappingCleared: false,
      previewReloadRequired: false,
      notifyMappingCleared: false,
    };
  }
  return {
    tableName: nextTableName,
    mappingCleared: true,
    previewReloadRequired: true,
    notifyMappingCleared: input.fileSelected,
  };
};

/** Loading the next page only makes sense while a next page exists and no request is running. */
export const shouldLoadMoreImportTargetTables = (state: { hasNextPage: boolean; loading: boolean }): boolean =>
  state.hasNextPage && !state.loading;
