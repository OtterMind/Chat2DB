import type { ImportParserOptions } from '@/typings/importExport';

export const DEFAULT_IMPORT_OPTIONS: ImportParserOptions = {
  encoding: 'UTF-8',
  delimiter: ',',
  quote: '"',
  escape: '"',
  hasHeader: true,
  emptyAsNull: true,
  headerRow: 1,
  startRow: 0,
  endRow: 0,
  formulaMode: 'CACHED_VALUE',
};

export type ImportValidationIssueCode =
  | 'duplicateHeaders'
  | 'duplicateTargetMapping'
  | 'invalidRange'
  | 'noMappedColumns'
  | 'requiredUnmapped';

export interface ImportValidationIssue {
  code: ImportValidationIssueCode;
  values?: string[];
}

interface ValidationInput {
  duplicateHeaders?: string[];
  mapping: Record<string, string>;
  blockedColumns: string[];
  options: ImportParserOptions;
  skipValue: string;
}

export const firstDataRowIndex = (options: ImportParserOptions) =>
  options.hasHeader ? Math.max(options.headerRow || 1, options.startRow || 0) : options.startRow || 0;

const stableValue = (value: unknown): unknown => {
  if (Array.isArray(value)) {
    return value.map(stableValue);
  }
  if (value && typeof value === 'object') {
    return Object.keys(value as Record<string, unknown>)
      .sort()
      .reduce<Record<string, unknown>>((result, key) => {
        const child = (value as Record<string, unknown>)[key];
        if (child !== undefined) {
          result[key] = stableValue(child);
        }
        return result;
      }, {});
  }
  return value;
};

export const importOptionsKey = (options: ImportParserOptions) => JSON.stringify(stableValue(options));

export const isPreviewCurrent = (previewOptionsKey: string | undefined, options: ImportParserOptions) =>
  !!previewOptionsKey && previewOptionsKey === importOptionsKey(options);

export const getImportValidationIssues = ({
  duplicateHeaders = [],
  mapping,
  blockedColumns,
  options,
  skipValue,
}: ValidationInput): ImportValidationIssue[] => {
  const issues: ImportValidationIssue[] = [];
  if (duplicateHeaders.length) {
    issues.push({ code: 'duplicateHeaders', values: duplicateHeaders });
  }

  const mappedTargets = Object.values(mapping).filter((target) => target && target !== skipValue);
  const duplicateTargets = mappedTargets.filter((target, index) => mappedTargets.indexOf(target) !== index);
  if (duplicateTargets.length) {
    issues.push({ code: 'duplicateTargetMapping', values: [...new Set(duplicateTargets)] });
  }

  if (options.endRow && options.endRow > 0 && options.endRow - 1 < firstDataRowIndex(options)) {
    issues.push({ code: 'invalidRange' });
  }

  if (!mappedTargets.length) {
    issues.push({ code: 'noMappedColumns' });
  }

  if (blockedColumns.length) {
    issues.push({ code: 'requiredUnmapped', values: blockedColumns });
  }

  return issues;
};
