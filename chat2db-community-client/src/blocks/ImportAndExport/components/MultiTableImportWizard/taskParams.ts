import { DatabaseTypeCode } from '@/constants/common';
import { ImportExportFileType, ImportExportTaskType } from '@/constants/importExport';
import type { ImportTaskParams } from '@/service/importExport';
import type {
  IImportFinalizationOptions,
  IImportRollbackOptions,
  IImportStagingPolicy,
  IImportValidationOptions,
  ImportCycleStrategy,
  ImportExportDataBoundInfo,
  ImportSourceKind,
} from '@/typings/importExport';
import {
  getLogicalDependencyIssues,
  getMultiTableSourceIssues,
  multiTableTargetKey,
  type LogicalDependencyDraft,
  type MultiTableSourceDraft,
  type MultiTableTarget,
} from './model';

export interface MultiTableImportSettings {
  sourceKind: ImportSourceKind;
  cycleStrategy: ImportCycleStrategy;
  stagingPolicy: IImportStagingPolicy;
  validationOptions: IImportValidationOptions;
  finalizationOptions: IImportFinalizationOptions;
  rollbackOptions: IImportRollbackOptions;
  onError: 'ABORT' | 'SKIP';
  maxErrors?: number;
  performanceSamplePercent: number;
}

export const defaultMultiTableImportSettings: MultiTableImportSettings = {
  sourceKind: 'TRUSTED',
  cycleStrategy: 'REJECT',
  stagingPolicy: {
    enabled: false,
    allVarchar: false,
    twoPhase: false,
  },
  validationOptions: {
    sourceProfiling: false,
    rowCount: true,
    checksum: true,
    orphanCheck: true,
  },
  finalizationOptions: {
    resetSequences: true,
    rebuildIndexes: false,
    refreshStatistics: true,
  },
  rollbackOptions: {
    fullRollback: false,
    rehearsal: false,
  },
  onError: 'ABORT',
  performanceSamplePercent: 5,
};

const STAGING_DATABASE_TYPES = new Set<DatabaseTypeCode>([
  DatabaseTypeCode.MYSQL,
  DatabaseTypeCode.MARIADB,
  DatabaseTypeCode.POSTGRESQL,
  DatabaseTypeCode.KINGBASE,
  DatabaseTypeCode.H2,
]);

const DEFERRED_CONSTRAINT_DATABASE_TYPES = new Set<DatabaseTypeCode>([
  DatabaseTypeCode.POSTGRESQL,
  DatabaseTypeCode.KINGBASE,
]);

const SEQUENCE_RESET_DATABASE_TYPES = new Set<DatabaseTypeCode>([
  DatabaseTypeCode.POSTGRESQL,
  DatabaseTypeCode.H2,
]);

const TRANSACTIONAL_MAINTENANCE_DATABASE_TYPES = new Set<DatabaseTypeCode>([DatabaseTypeCode.POSTGRESQL]);

export const supportsMultiTableStaging = (databaseType?: DatabaseTypeCode) =>
  !!databaseType && STAGING_DATABASE_TYPES.has(databaseType);

export const supportsDeferredConstraintImport = (databaseType?: DatabaseTypeCode) =>
  !!databaseType && DEFERRED_CONSTRAINT_DATABASE_TYPES.has(databaseType);

export const supportsMultiTableSequenceReset = (databaseType?: DatabaseTypeCode) =>
  !!databaseType && SEQUENCE_RESET_DATABASE_TYPES.has(databaseType);

export const supportsMultiTableIndexRebuild = (databaseType?: DatabaseTypeCode) =>
  !!databaseType && TRANSACTIONAL_MAINTENANCE_DATABASE_TYPES.has(databaseType);

export const supportsMultiTableStatisticsRefresh = (databaseType?: DatabaseTypeCode) =>
  !!databaseType && TRANSACTIONAL_MAINTENANCE_DATABASE_TYPES.has(databaseType);

const anyEnabled = (options: object) => Object.values(options).some((value) => value === true);

export const requiresImplicitMultiTableStaging = (settings: MultiTableImportSettings) =>
  settings.sourceKind === 'THIRD_PARTY' ||
  settings.cycleStrategy !== 'REJECT' ||
  settings.stagingPolicy.allVarchar ||
  settings.stagingPolicy.twoPhase ||
  anyEnabled(settings.validationOptions) ||
  anyEnabled(settings.finalizationOptions) ||
  anyEnabled(settings.rollbackOptions);

export const usesMultiTableStaging = (settings: MultiTableImportSettings) =>
  settings.stagingPolicy.enabled || requiresImplicitMultiTableStaging(settings);

export const requestsAdvancedMultiTableImport = usesMultiTableStaging;

const isIntegerInRange = (value: number | undefined, min: number, max?: number) =>
  Number.isInteger(value) && value! >= min && (max === undefined || value! <= max);

export const hasValidMultiTableMaxErrors = (
  settings: Pick<MultiTableImportSettings, 'onError' | 'maxErrors'>,
) => settings.onError !== 'SKIP' || isIntegerInRange(settings.maxErrors, 1);

export const getMultiTableOnErrorLabelKey = (onError: MultiTableImportSettings['onError']) =>
  onError === 'SKIP'
    ? ('workspace.importExport.multiTable.onErrorSkip' as const)
    : ('workspace.importExport.multiTable.onErrorAbort' as const);

export const getMultiTableCycleStrategyLabelKey = (cycleStrategy: ImportCycleStrategy) => {
  switch (cycleStrategy) {
    case 'DEFER_CONSTRAINTS':
      return 'workspace.importExport.multiTable.cycleDeferred' as const;
    case 'STAGING_TWO_PHASE':
      return 'workspace.importExport.multiTable.cycleTwoPhase' as const;
    default:
      return 'workspace.importExport.multiTable.cycleReject' as const;
  }
};

export const hasValidMultiTableNumericSettings = (settings: MultiTableImportSettings) =>
  hasValidMultiTableMaxErrors(settings) &&
  (!settings.rollbackOptions.rehearsal || isIntegerInRange(settings.performanceSamplePercent, 1, 100));

export const hasCompatibleMultiTableSettings = (
  settings: MultiTableImportSettings,
  databaseType?: DatabaseTypeCode,
) =>
  (!settings.rollbackOptions.fullRollback ||
    settings.rollbackOptions.rehearsal ||
    !anyEnabled(settings.finalizationOptions)) &&
  (!databaseType ||
    ((!settings.finalizationOptions.resetSequences || supportsMultiTableSequenceReset(databaseType)) &&
      (!settings.finalizationOptions.rebuildIndexes || supportsMultiTableIndexRebuild(databaseType)) &&
      (!settings.finalizationOptions.refreshStatistics || supportsMultiTableStatisticsRefresh(databaseType))));

const cloneSettings = (settings: MultiTableImportSettings): MultiTableImportSettings => ({
  ...settings,
  stagingPolicy: { ...settings.stagingPolicy },
  validationOptions: { ...settings.validationOptions },
  finalizationOptions: { ...settings.finalizationOptions },
  rollbackOptions: { ...settings.rollbackOptions },
});

export const constrainMultiTableSettings = (
  settings: MultiTableImportSettings,
  databaseType?: DatabaseTypeCode,
): MultiTableImportSettings => {
  const next = cloneSettings(settings);
  if (!supportsMultiTableStaging(databaseType)) {
    return {
      ...next,
      sourceKind: 'TRUSTED',
      cycleStrategy: 'REJECT',
      stagingPolicy: { enabled: false, allVarchar: false, twoPhase: false },
      validationOptions: { sourceProfiling: false, rowCount: false, checksum: false, orphanCheck: false },
      finalizationOptions: { resetSequences: false, rebuildIndexes: false, refreshStatistics: false },
      rollbackOptions: { fullRollback: false, rehearsal: false },
    };
  }
  if (!supportsDeferredConstraintImport(databaseType) && next.cycleStrategy === 'DEFER_CONSTRAINTS') {
    next.cycleStrategy = 'REJECT';
  }
  if (!supportsMultiTableSequenceReset(databaseType)) {
    next.finalizationOptions.resetSequences = false;
  }
  if (!supportsMultiTableIndexRebuild(databaseType)) {
    next.finalizationOptions.rebuildIndexes = false;
  }
  if (!supportsMultiTableStatisticsRefresh(databaseType)) {
    next.finalizationOptions.refreshStatistics = false;
  }
  return next;
};

export const defaultMultiTableImportSettingsFor = (databaseType?: DatabaseTypeCode) => {
  const defaults = constrainMultiTableSettings(defaultMultiTableImportSettings, databaseType);
  if (databaseType === DatabaseTypeCode.H2) {
    defaults.finalizationOptions.resetSequences = false;
  }
  return defaults;
};

interface BuildMultiTableImportParamsInput {
  boundInfo: ImportExportDataBoundInfo;
  clientSubmissionId?: string;
  sources: MultiTableSourceDraft[];
  targets: MultiTableTarget[];
  dependencies: LogicalDependencyDraft[];
  settings: MultiTableImportSettings;
}

export const MULTI_TABLE_IMPORT_FORMAT = ImportExportFileType.CSV;
export const MULTI_TABLE_IMPORT_ACCEPT = `.${MULTI_TABLE_IMPORT_FORMAT.toLowerCase()}`;

export const buildMultiTableImportParams = ({
  boundInfo,
  clientSubmissionId,
  sources,
  targets,
  dependencies,
  settings,
}: BuildMultiTableImportParamsInput): ImportTaskParams | null => {
  if (boundInfo.targetScope !== 'SCHEMA' && boundInfo.targetScope !== 'DATABASE') return null;
  if (
    !hasValidMultiTableNumericSettings(settings) ||
    !hasCompatibleMultiTableSettings(settings, boundInfo.databaseType)
  )
    return null;
  const targetByKey = new Map(targets.map((target) => [multiTableTargetKey(target), target]));
  const validTargetKeys = new Set(targetByKey.keys());
  const sourceIssues = getMultiTableSourceIssues(sources, validTargetKeys);
  const selectedTargetKeys = new Set(
    sources.map((source) => source.targetKey).filter((key): key is string => !!key && validTargetKeys.has(key)),
  );
  const dependencyIssues = getLogicalDependencyIssues(dependencies, selectedTargetKeys);
  if (
    !sourceIssues.ready ||
    sourceIssues.invalidTargets.length > 0 ||
    sourceIssues.duplicateTargets ||
    dependencyIssues.incomplete ||
    dependencyIssues.unknownTarget ||
    dependencyIssues.duplicate ||
    dependencyIssues.invalidGroup
  ) {
    return null;
  }

  const advancedRequested = requestsAdvancedMultiTableImport(settings);
  if (advancedRequested && !supportsMultiTableStaging(boundInfo.databaseType)) return null;
  if (settings.cycleStrategy === 'DEFER_CONSTRAINTS' && !supportsDeferredConstraintImport(boundInfo.databaseType)) {
    return null;
  }
  const thirdPartySource = settings.sourceKind === 'THIRD_PARTY';
  const twoPhase = settings.cycleStrategy === 'STAGING_TWO_PHASE';
  const stagingPolicy: IImportStagingPolicy = {
    enabled: usesMultiTableStaging(settings),
    allVarchar: settings.stagingPolicy.allVarchar || thirdPartySource,
    twoPhase: settings.stagingPolicy.twoPhase || twoPhase,
  };
  const validationOptions: IImportValidationOptions = {
    ...settings.validationOptions,
    sourceProfiling: settings.validationOptions.sourceProfiling || thirdPartySource,
    orphanCheck: settings.validationOptions.orphanCheck || thirdPartySource,
  };

  return {
    ...(clientSubmissionId ? { clientSubmissionId } : {}),
    dataSourceId: boundInfo.dataSourceId,
    databaseName: boundInfo.databaseName,
    schemaName: boundInfo.schemaName,
    taskType: ImportExportTaskType.DATA_FILE_IMPORT,
    format: MULTI_TABLE_IMPORT_FORMAT,
    mode: 'STANDARD',
    scope: boundInfo.targetScope,
    tableSources: sources.map((source) => {
      const target = targetByKey.get(source.targetKey!)!;
      return {
        databaseName: target.databaseName || boundInfo.databaseName,
        schemaName: target.schemaName,
        tableName: target.tableName,
        fileId: source.fileId,
        displayFileName: source.displayFileName,
        format: MULTI_TABLE_IMPORT_FORMAT,
        unmappedTarget: 'DEFAULT',
        options:
          settings.onError === 'SKIP' ? { onError: 'SKIP', maxErrors: settings.maxErrors } : { onError: 'ABORT' },
      };
    }),
    logicalDependencies: dependencies.map((dependency) => {
      const parent = targetByKey.get(dependency.parentTargetKey!)!;
      const child = targetByKey.get(dependency.childTargetKey!)!;
      return {
        parentDatabaseName: parent.databaseName || boundInfo.databaseName,
        parentSchemaName: parent.schemaName,
        parentTable: parent.tableName,
        parentColumn: dependency.parentColumn!,
        childDatabaseName: child.databaseName || boundInfo.databaseName,
        childSchemaName: child.schemaName,
        childTable: child.tableName,
        childColumn: dependency.childColumn!,
        constraintName: dependency.constraintName,
        keySequence: dependency.keySequence,
        logical: true,
      };
    }),
    ...(settings.sourceKind === 'THIRD_PARTY' ? { sourceKind: settings.sourceKind } : {}),
    ...(settings.cycleStrategy !== 'REJECT' ? { cycleStrategy: settings.cycleStrategy } : {}),
    ...(stagingPolicy.enabled || stagingPolicy.allVarchar || stagingPolicy.twoPhase ? { stagingPolicy } : {}),
    validationOptions,
    finalizationOptions: settings.finalizationOptions,
    rollbackOptions: settings.rollbackOptions,
    ...(settings.rollbackOptions.rehearsal ? { performanceSamplePercent: settings.performanceSamplePercent } : {}),
  };
};
