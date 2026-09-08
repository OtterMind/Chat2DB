import type { IImportPreview } from '@/typings/importExport';

export const mergeImportColumnMappings = (
  preview: IImportPreview,
  previous: Record<string, string | undefined>,
) => {
  const validTargets = new Set(preview.targetColumns.map((column) => column.name));
  return Object.fromEntries(
    preview.columnMatches.map((match) => {
      if (Object.prototype.hasOwnProperty.call(previous, match.fileColumn)) {
        const previousTarget = previous[match.fileColumn];
        if (!previousTarget || validTargets.has(previousTarget)) {
          return [match.fileColumn, previousTarget];
        }
      }
      return [match.fileColumn, match.matched ? match.tableColumn : undefined];
    }),
  );
};

export const getImportMappingIssues = (
  preview: IImportPreview | null,
  mapping: Record<string, string | undefined>,
  strategy: 'DEFAULT' | 'NULL' = 'DEFAULT',
) => {
  const targets = Object.values(mapping).filter((target): target is string => !!target);
  const normalizedTargets = targets.map((target) => target.trim().toLowerCase());
  return {
    duplicate: new Set(normalizedTargets).size !== targets.length,
    empty: targets.length === 0,
    required: (preview?.targetColumns || []).filter(
      (column) =>
        !column.nullable &&
        !column.autoIncrement &&
        !targets.includes(column.name) &&
        (strategy === 'NULL' || column.defaultValue == null),
    ),
    unmapped: (preview?.targetColumns || []).filter((column) => !targets.includes(column.name)),
  };
};
