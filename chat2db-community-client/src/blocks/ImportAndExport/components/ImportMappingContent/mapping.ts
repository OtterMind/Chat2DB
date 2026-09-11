import { SKIP_IMPORT_SOURCE_FIELD } from '@/constants/importExport';

interface ISuggestedMapping {
  sourceColumn: string;
  targetColumn: string;
}

export type ImportMappingRow<TTargetColumn extends { name: string }> =
  | {
      key: string;
      kind: 'source';
      sourceColumn: string;
    }
  | {
      key: string;
      kind: 'target';
      targetColumn: TTargetColumn;
    };

export interface IDuplicateImportMapping {
  sourceColumn: string;
  targetColumn: string;
  mappedSource: string;
}

export const getImportPreviewErrorMessage = (
  error: unknown,
  fallback: string,
  messagesByCode: Record<string, string> = {},
): string => {
  if (typeof error === 'string' && error) {
    return error;
  }
  if (!error || typeof error !== 'object') {
    return fallback;
  }
  const value = error as { errorCode?: unknown; errorMessage?: unknown; message?: unknown };
  if (typeof value.errorMessage === 'string' && value.errorMessage && !value.errorMessage.endsWith(' : no message.')) {
    return value.errorMessage;
  }
  if (typeof value.errorCode === 'string' && messagesByCode[value.errorCode]) {
    return messagesByCode[value.errorCode];
  }
  return typeof value.message === 'string' && value.message ? value.message : fallback;
};

export const buildInitialImportMapping = (
  sourceColumns: string[],
  suggestedMapping: ISuggestedMapping[],
): Record<string, string> => {
  const mapping = Object.fromEntries(sourceColumns.map((name) => [name, SKIP_IMPORT_SOURCE_FIELD]));
  suggestedMapping.forEach(({ sourceColumn, targetColumn }) => {
    mapping[sourceColumn] = targetColumn;
  });
  return mapping;
};

export const buildImportMappingRows = <TTargetColumn extends { name: string }>(
  sourceColumns: string[],
  targetColumns: TTargetColumn[],
  mapping: Record<string, string>,
): ImportMappingRow<TTargetColumn>[] => {
  const mappedTargetColumns = new Set(Object.values(mapping));
  return [
    ...sourceColumns.map((name) => ({
      key: `source:${name}`,
      kind: 'source' as const,
      sourceColumn: name,
    })),
    ...targetColumns
      .filter(({ name }) => !mappedTargetColumns.has(name))
      .map((targetColumn) => ({
        key: `target:${targetColumn.name}`,
        kind: 'target' as const,
        targetColumn,
      })),
  ];
};

export const getDuplicateImportMappings = (
  mapping: Record<string, string>,
): Record<string, IDuplicateImportMapping> => {
  const firstSourceByTarget = new Map<string, string>();
  const duplicates: Record<string, IDuplicateImportMapping> = {};
  Object.entries(mapping).forEach(([sourceColumn, targetColumn]) => {
    if (targetColumn === SKIP_IMPORT_SOURCE_FIELD) {
      return;
    }
    const mappedSource = firstSourceByTarget.get(targetColumn);
    if (mappedSource) {
      duplicates[sourceColumn] = { sourceColumn, targetColumn, mappedSource };
      return;
    }
    firstSourceByTarget.set(targetColumn, sourceColumn);
  });
  return duplicates;
};
