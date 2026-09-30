import type { FileUrl } from '@/components/UploadLocalFile';

export interface MultiTableTarget {
  databaseName?: string;
  schemaName?: string;
  tableName: string;
}

export type MultiTableSourceStatus = 'PENDING' | 'STAGING' | 'READY' | 'ERROR';

export interface MultiTableSourceDraft {
  id: string;
  selection: FileUrl;
  displayFileName: string;
  targetKey?: string;
  fileId?: string;
  status: MultiTableSourceStatus;
  error?: string;
}

export interface LogicalDependencyDraft {
  id: string;
  constraintName?: string;
  keySequence?: number;
  parentTargetKey?: string;
  parentColumn?: string;
  childTargetKey?: string;
  childColumn?: string;
}

export const createLogicalDependencyDraft = (id: string): LogicalDependencyDraft => ({
  id,
  constraintName: `chat2db-logical-${id}`,
  keySequence: 1,
});

export const multiTableTargetKey = (target: MultiTableTarget) =>
  JSON.stringify([target.databaseName || '', target.schemaName || '', target.tableName]);

export const multiTableTargetLabel = (target: MultiTableTarget) =>
  [target.schemaName, target.tableName].filter(Boolean).join('.');

const fileStem = (fileName: string) =>
  fileName
    .replace(/\.[^.]+$/, '')
    .trim()
    .toLocaleLowerCase();

export const findAutomaticTargetKey = (
  fileName: string,
  targets: MultiTableTarget[],
  unavailableTargetKeys: Set<string>,
) => {
  const matches = targets.filter(
    (target) => target.tableName.trim().toLocaleLowerCase() === fileStem(fileName),
  );
  if (matches.length !== 1) return undefined;
  const key = multiTableTargetKey(matches[0]);
  return unavailableTargetKeys.has(key) ? undefined : key;
};

export const reconcileMultiTableSources = (
  current: MultiTableSourceDraft[],
  selections: FileUrl[],
  targets: MultiTableTarget[],
  createId: () => string,
): MultiTableSourceDraft[] => {
  const claimedCurrentIds = new Set<string>();
  const retained = selections.map((selection) => {
    const match = current.find((source) => source.selection === selection && !claimedCurrentIds.has(source.id));
    if (match) claimedCurrentIds.add(match.id);
    return match;
  });
  const unavailableTargetKeys = new Set(
    retained.map((source) => source?.targetKey).filter((key): key is string => !!key),
  );

  return selections.map((selection, index) => {
    const existing = retained[index];
    if (existing) return existing;
    const displayFileName = selection.fileName || selection.file?.name || '';
    const targetKey = findAutomaticTargetKey(displayFileName, targets, unavailableTargetKeys);
    if (targetKey) unavailableTargetKeys.add(targetKey);
    return {
      id: createId(),
      selection,
      displayFileName,
      targetKey,
      status: 'PENDING',
    };
  });
};

export const reconcileMultiTableSourcesWithTargets = (
  sources: MultiTableSourceDraft[],
  targets: MultiTableTarget[],
): MultiTableSourceDraft[] => {
  const validTargetKeys = new Set(targets.map(multiTableTargetKey));
  const unavailableTargetKeys = new Set(
    sources
      .map((source) => source.targetKey)
      .filter((key): key is string => !!key && validTargetKeys.has(key)),
  );
  return sources.map((source) => {
    if (source.targetKey && validTargetKeys.has(source.targetKey)) return source;
    const targetKey = findAutomaticTargetKey(source.displayFileName, targets, unavailableTargetKeys);
    if (targetKey) unavailableTargetKeys.add(targetKey);
    return source.targetKey === targetKey ? source : { ...source, targetKey };
  });
};

export const reconcileLogicalDependenciesWithTargets = (
  dependencies: LogicalDependencyDraft[],
  validTargetKeys: ReadonlySet<string>,
): LogicalDependencyDraft[] =>
  dependencies.map((dependency) => ({
    ...dependency,
    ...(!dependency.parentTargetKey || validTargetKeys.has(dependency.parentTargetKey)
      ? {}
      : { parentTargetKey: undefined, parentColumn: undefined }),
    ...(!dependency.childTargetKey || validTargetKeys.has(dependency.childTargetKey)
      ? {}
      : { childTargetKey: undefined, childColumn: undefined }),
  }));

export const normalizeLogicalDependencySequences = (
  dependencies: LogicalDependencyDraft[],
): LogicalDependencyDraft[] => {
  const nextSequence = new Map<string, number>();
  return dependencies.map((dependency) => {
    const group = dependency.constraintName?.trim();
    if (!group) return dependency;
    const keySequence = (nextSequence.get(group) || 0) + 1;
    nextSequence.set(group, keySequence);
    return dependency.keySequence === keySequence ? dependency : { ...dependency, keySequence };
  });
};

export const getMultiTableSourceIssues = (
  sources: MultiTableSourceDraft[],
  validTargetKeys?: ReadonlySet<string>,
) => {
  const targetKeys = sources.map((source) => source.targetKey).filter((key): key is string => !!key);
  const invalidTargets = validTargetKeys
    ? sources.filter((source) => !!source.targetKey && !validTargetKeys.has(source.targetKey))
    : [];
  return {
    empty: sources.length === 0,
    staging: sources.some((source) => source.status === 'PENDING' || source.status === 'STAGING'),
    failed: sources.filter((source) => source.status === 'ERROR'),
    unmapped: sources.filter((source) => !source.targetKey),
    invalidTargets,
    duplicateTargets: new Set(targetKeys).size !== targetKeys.length,
    ready:
      sources.length > 0 &&
      invalidTargets.length === 0 &&
      sources.every((source) => source.status === 'READY' && !!source.fileId && !!source.targetKey),
  };
};

export const getLogicalDependencyIssues = (
  dependencies: LogicalDependencyDraft[],
  validTargetKeys: Set<string>,
) => {
  const complete = dependencies.filter(
    (dependency) =>
      dependency.parentTargetKey &&
      dependency.parentColumn &&
      dependency.childTargetKey &&
      dependency.childColumn &&
      dependency.constraintName?.trim() &&
      Number.isInteger(dependency.keySequence ?? Number.NaN) &&
      dependency.keySequence! > 0,
  );
  const edgeKeys = complete.map((dependency) =>
    [
      dependency.parentTargetKey,
      dependency.parentColumn?.trim(),
      dependency.childTargetKey,
      dependency.childColumn?.trim(),
    ].join('\u0000'),
  );
  const groups = new Map<string, LogicalDependencyDraft[]>();
  complete.forEach((dependency) => {
    const group = dependency.constraintName!.trim();
    groups.set(group, [...(groups.get(group) || []), dependency]);
  });
  const invalidGroup = Array.from(groups.values()).some((group) => {
    const parentTargets = new Set(group.map((dependency) => dependency.parentTargetKey));
    const childTargets = new Set(group.map((dependency) => dependency.childTargetKey));
    const parentColumns = group.map((dependency) => dependency.parentColumn!.trim());
    const childColumns = group.map((dependency) => dependency.childColumn!.trim());
    const keySequences = group.map((dependency) => dependency.keySequence!).sort((left, right) => left - right);
    return (
      parentTargets.size !== 1 ||
      childTargets.size !== 1 ||
      new Set(parentColumns).size !== parentColumns.length ||
      new Set(childColumns).size !== childColumns.length ||
      keySequences.some((sequence, index) => sequence !== index + 1)
    );
  });
  return {
    incomplete: dependencies.length !== complete.length,
    unknownTarget: complete.some(
      (dependency) =>
        !validTargetKeys.has(dependency.parentTargetKey!) || !validTargetKeys.has(dependency.childTargetKey!),
    ),
    duplicate: new Set(edgeKeys).size !== edgeKeys.length,
    invalidGroup,
  };
};
