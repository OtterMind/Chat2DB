import assert from 'node:assert/strict';
import { DatabaseTypeCode } from '@/constants/common';
import { ImportExportFileType, ImportExportTaskType, ImportExportType } from '@/constants/importExport';
import enUSWorkspace from '@/i18n/en-US/workspace';
import esESWorkspace from '@/i18n/es-ES/workspace';
import jaJPWorkspace from '@/i18n/ja-JP/workspace';
import koKRWorkspace from '@/i18n/ko-KR/workspace';
import zhCNWorkspace from '@/i18n/zh-CN/workspace';
import type { ImportExportDataBoundInfo } from '@/typings/importExport';
import { multiTableTargetKey, type LogicalDependencyDraft, type MultiTableTarget } from './model';
import {
  buildMultiTableImportParams,
  defaultMultiTableImportSettings,
  defaultMultiTableImportSettingsFor,
  getMultiTableCycleStrategyLabelKey,
  getMultiTableOnErrorLabelKey,
  hasCompatibleMultiTableSettings,
  hasValidMultiTableMaxErrors,
  hasValidMultiTableNumericSettings,
  requiresImplicitMultiTableStaging,
  supportsMultiTableIndexRebuild,
  supportsMultiTableSequenceReset,
  supportsMultiTableStatisticsRefresh,
  usesMultiTableStaging,
} from './taskParams';

const boundInfo: ImportExportDataBoundInfo = {
  dataSourceId: 7,
  databaseType: DatabaseTypeCode.MYSQL,
  databaseName: 'app',
  schemaName: 'public',
  targetScope: 'SCHEMA',
  type: ImportExportType.IMPORT,
  fileType: ImportExportFileType.CSV,
};
const targets: MultiTableTarget[] = [
  { databaseName: 'app', schemaName: 'public', tableName: 'users' },
  { databaseName: 'app', schemaName: 'public', tableName: 'orders' },
];
const [usersKey, ordersKey] = targets.map(multiTableTargetKey);
const mysqlDefaults = defaultMultiTableImportSettingsFor(DatabaseTypeCode.MYSQL);
const sources = [
  {
    id: 'users-source',
    selection: { fileName: 'users.csv' },
    displayFileName: 'users.csv',
    targetKey: usersKey,
    fileId: 'file-users',
    status: 'READY' as const,
  },
  {
    id: 'orders-source',
    selection: { fileName: 'orders.csv' },
    displayFileName: 'orders.csv',
    targetKey: ordersKey,
    fileId: 'file-orders',
    status: 'READY' as const,
  },
];
const dependencies: LogicalDependencyDraft[] = [
  {
    id: 'orders-user',
    constraintName: 'fk_orders_user',
    keySequence: 1,
    parentTargetKey: usersKey,
    parentColumn: 'id',
    childTargetKey: ordersKey,
    childColumn: 'user_id',
  },
];

const standard = buildMultiTableImportParams({
  boundInfo,
  clientSubmissionId: 'import-attempt-1',
  sources,
  targets,
  dependencies,
  settings: mysqlDefaults,
});
assert.equal(standard?.taskType, ImportExportTaskType.DATA_FILE_IMPORT);
assert.equal(standard?.clientSubmissionId, 'import-attempt-1');
assert.equal(standard?.scope, 'SCHEMA');
assert.equal(standard?.format, ImportExportFileType.CSV);
assert.deepEqual(
  standard?.tableSources?.map(({ tableName, fileId }) => ({ tableName, fileId })),
  [
    { tableName: 'users', fileId: 'file-users' },
    { tableName: 'orders', fileId: 'file-orders' },
  ],
);
assert.deepEqual(standard?.logicalDependencies, [
  {
    parentDatabaseName: 'app',
    parentSchemaName: 'public',
    parentTable: 'users',
    parentColumn: 'id',
    childDatabaseName: 'app',
    childSchemaName: 'public',
    childTable: 'orders',
    childColumn: 'user_id',
    constraintName: 'fk_orders_user',
    keySequence: 1,
    logical: true,
  },
]);
assert.equal(standard?.sourceKind, undefined);
assert.equal(standard?.cycleStrategy, undefined);
assert.equal(standard?.performanceSamplePercent, undefined);
assert.deepEqual(standard?.finalizationOptions, {
  resetSequences: false,
  rebuildIndexes: false,
  refreshStatistics: false,
});
assert.deepEqual(standard?.stagingPolicy, {
  enabled: true,
  allVarchar: false,
  twoPhase: false,
});
assert.equal(standard?.rollbackOptions?.fullRollback, false);
assert.equal(requiresImplicitMultiTableStaging(mysqlDefaults), true);
assert.equal(usesMultiTableStaging(mysqlDefaults), true);
assert.equal(hasValidMultiTableNumericSettings(defaultMultiTableImportSettings), true);
assert.equal(hasValidMultiTableMaxErrors({ onError: 'ABORT', maxErrors: undefined }), true);
assert.equal(hasValidMultiTableMaxErrors({ onError: 'SKIP', maxErrors: 1 }), true);
for (const maxErrors of [undefined, 0, -1, 1.5]) {
  assert.equal(hasValidMultiTableMaxErrors({ onError: 'SKIP', maxErrors }), false);
}
assert.equal(getMultiTableOnErrorLabelKey('ABORT'), 'workspace.importExport.multiTable.onErrorAbort');
assert.equal(getMultiTableOnErrorLabelKey('SKIP'), 'workspace.importExport.multiTable.onErrorSkip');
assert.equal(getMultiTableCycleStrategyLabelKey('REJECT'), 'workspace.importExport.multiTable.cycleReject');
assert.equal(
  getMultiTableCycleStrategyLabelKey('DEFER_CONSTRAINTS'),
  'workspace.importExport.multiTable.cycleDeferred',
);
assert.equal(
  getMultiTableCycleStrategyLabelKey('STAGING_TWO_PHASE'),
  'workspace.importExport.multiTable.cycleTwoPhase',
);
const requiredWorkspaceKeys = [
  'workspace.importExport.onError',
  'workspace.importExport.maxErrors',
  'workspace.importExport.multiTable.onErrorAbort',
  'workspace.importExport.multiTable.onErrorSkip',
  'workspace.importExport.multiTable.maxErrorsInvalid',
];
for (const [locale, messages] of Object.entries({
  'en-US': enUSWorkspace,
  'es-ES': esESWorkspace,
  'ja-JP': jaJPWorkspace,
  'ko-KR': koKRWorkspace,
  'zh-CN': zhCNWorkspace,
})) {
  for (const key of requiredWorkspaceKeys) {
    assert.equal(typeof (messages as Record<string, string>)[key], 'string', `${locale} is missing ${key}`);
    assert.notEqual((messages as Record<string, string>)[key].trim(), '', `${locale} has an empty ${key}`);
  }
}
assert.equal(hasCompatibleMultiTableSettings(defaultMultiTableImportSettings), true);
assert.equal(hasCompatibleMultiTableSettings(defaultMultiTableImportSettings, DatabaseTypeCode.MYSQL), false);
assert.equal(supportsMultiTableSequenceReset(DatabaseTypeCode.MYSQL), false);
assert.equal(supportsMultiTableSequenceReset(DatabaseTypeCode.H2), true);
assert.equal(supportsMultiTableSequenceReset(DatabaseTypeCode.KINGBASE), false);
assert.equal(supportsMultiTableIndexRebuild(DatabaseTypeCode.MYSQL), false);
assert.equal(supportsMultiTableIndexRebuild(DatabaseTypeCode.H2), false);
assert.equal(supportsMultiTableIndexRebuild(DatabaseTypeCode.POSTGRESQL), true);
assert.equal(supportsMultiTableIndexRebuild(DatabaseTypeCode.KINGBASE), false);
assert.equal(supportsMultiTableStatisticsRefresh(DatabaseTypeCode.MYSQL), false);
assert.equal(supportsMultiTableStatisticsRefresh(DatabaseTypeCode.POSTGRESQL), true);
assert.equal(supportsMultiTableStatisticsRefresh(DatabaseTypeCode.KINGBASE), false);
assert.equal(defaultMultiTableImportSettingsFor(DatabaseTypeCode.H2).finalizationOptions.resetSequences, false);

const thirdPartyRehearsal = buildMultiTableImportParams({
  boundInfo,
  sources,
  targets,
  dependencies,
  settings: {
    ...mysqlDefaults,
    sourceKind: 'THIRD_PARTY',
    cycleStrategy: 'STAGING_TWO_PHASE',
    validationOptions: {
      ...mysqlDefaults.validationOptions,
      sourceProfiling: false,
      orphanCheck: false,
    },
    rollbackOptions: { fullRollback: true, rehearsal: true },
  },
});
assert.equal(thirdPartyRehearsal?.sourceKind, 'THIRD_PARTY');
assert.equal(thirdPartyRehearsal?.cycleStrategy, 'STAGING_TWO_PHASE');
assert.equal(thirdPartyRehearsal?.performanceSamplePercent, 5);
assert.deepEqual(thirdPartyRehearsal?.stagingPolicy, {
  enabled: true,
  allVarchar: true,
  twoPhase: true,
});
assert.equal(thirdPartyRehearsal?.validationOptions?.sourceProfiling, true);
assert.equal(thirdPartyRehearsal?.validationOptions?.orphanCheck, true);
assert.equal(thirdPartyRehearsal?.performanceSamplePercent, 5);

assert.equal(
  buildMultiTableImportParams({
    boundInfo,
    sources: [{ ...sources[0], targetKey: undefined }],
    targets,
    dependencies: [],
    settings: mysqlDefaults,
  }),
  null,
);

const incompatibleRollbackSettings = {
  ...mysqlDefaults,
  finalizationOptions: { ...mysqlDefaults.finalizationOptions, resetSequences: true },
  rollbackOptions: { fullRollback: true, rehearsal: false },
};
assert.equal(hasCompatibleMultiTableSettings(incompatibleRollbackSettings), false);
assert.equal(
  buildMultiTableImportParams({
    boundInfo,
    sources,
    targets,
    dependencies,
    settings: incompatibleRollbackSettings,
  }),
  null,
);

assert.equal(
  buildMultiTableImportParams({
    boundInfo,
    sources,
    targets,
    dependencies,
    settings: { ...mysqlDefaults, onError: 'SKIP', maxErrors: 1.5 },
  }),
  null,
);

for (const performanceSamplePercent of [0, 101, 2.5]) {
  assert.equal(
    buildMultiTableImportParams({
      boundInfo,
      sources,
      targets,
      dependencies,
      settings: {
        ...mysqlDefaults,
        rollbackOptions: { ...mysqlDefaults.rollbackOptions, rehearsal: true },
        performanceSamplePercent,
      },
    }),
    null,
  );
}

assert.equal(
  buildMultiTableImportParams({
    boundInfo: { ...boundInfo, databaseType: DatabaseTypeCode.H2 },
    sources,
    targets,
    dependencies,
    settings: {
      ...mysqlDefaults,
      finalizationOptions: { ...mysqlDefaults.finalizationOptions, rebuildIndexes: true },
    },
  }),
  null,
);

assert.equal(
  buildMultiTableImportParams({
    boundInfo,
    sources,
    targets,
    dependencies,
    settings: { ...mysqlDefaults, onError: 'SKIP', maxErrors: undefined },
  }),
  null,
);

assert.equal(
  buildMultiTableImportParams({
    boundInfo,
    sources: [{ ...sources[0], targetKey: JSON.stringify(['app', 'stale', 'users']) }],
    targets,
    dependencies: [],
    settings: mysqlDefaults,
  }),
  null,
);

const unsupportedBoundInfo = { ...boundInfo, databaseType: DatabaseTypeCode.ORACLE };
assert.equal(
  buildMultiTableImportParams({
    boundInfo: unsupportedBoundInfo,
    sources,
    targets,
    dependencies,
    settings: mysqlDefaults,
  }),
  null,
);

const standardOnlySettings = defaultMultiTableImportSettingsFor(DatabaseTypeCode.ORACLE);
assert.equal(requiresImplicitMultiTableStaging(standardOnlySettings), false);
assert.equal(usesMultiTableStaging(standardOnlySettings), false);
const unsupportedStandard = buildMultiTableImportParams({
  boundInfo: unsupportedBoundInfo,
  sources,
  targets,
  dependencies,
  settings: standardOnlySettings,
});
assert.equal(unsupportedStandard?.scope, 'SCHEMA');
assert.equal(unsupportedStandard?.stagingPolicy, undefined);
assert.deepEqual(unsupportedStandard?.validationOptions, {
  sourceProfiling: false,
  rowCount: false,
  checksum: false,
  orphanCheck: false,
});

assert.equal(
  buildMultiTableImportParams({
    boundInfo,
    sources,
    targets,
    dependencies,
    settings: { ...mysqlDefaults, cycleStrategy: 'DEFER_CONSTRAINTS' },
  }),
  null,
);
assert.equal(
  buildMultiTableImportParams({
    boundInfo: { ...boundInfo, databaseType: DatabaseTypeCode.POSTGRESQL },
    sources,
    targets,
    dependencies,
    settings: {
      ...defaultMultiTableImportSettingsFor(DatabaseTypeCode.POSTGRESQL),
      cycleStrategy: 'DEFER_CONSTRAINTS',
    },
  })?.cycleStrategy,
  'DEFER_CONSTRAINTS',
);

const compositeDependencies: LogicalDependencyDraft[] = [
  {
    ...dependencies[0],
    id: 'orders-user-tenant',
    parentColumn: 'tenant_id',
    childColumn: 'user_tenant_id',
    keySequence: 1,
  },
  {
    ...dependencies[0],
    id: 'orders-user-id',
    keySequence: 2,
  },
];
const composite = buildMultiTableImportParams({
  boundInfo,
  sources,
  targets,
  dependencies: compositeDependencies,
  settings: mysqlDefaults,
});
assert.deepEqual(
  composite?.logicalDependencies?.map(({ constraintName, keySequence }) => ({ constraintName, keySequence })),
  [
    { constraintName: 'fk_orders_user', keySequence: 1 },
    { constraintName: 'fk_orders_user', keySequence: 2 },
  ],
);

console.log('Multi-table import task parameter tests passed');
