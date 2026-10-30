import assert from 'node:assert/strict';
import { ImportExportFileType, ImportExportTaskType, ImportExportType } from '@/constants/importExport';
import type { ImportExportTaskDetails } from '@/typings/importExport';
import { hasSavedTaskSpec, parseTaskSpecForWizard } from './restoreTaskSpec';

type TaskStub = Pick<ImportExportTaskDetails, 'type' | 'specJson' | 'target'>;

const task = (overrides: Partial<TaskStub> & Pick<TaskStub, 'type'>): TaskStub => ({
  specJson: null,
  ...overrides,
});

const expectFailure = (value: ReturnType<typeof parseTaskSpecForWizard>, reason: string, i18nKey: string) => {
  assert.equal(value.ok, false, `expected a failure, got ${JSON.stringify(value)}`);
  if (value.ok) return;
  assert.equal(value.reason, reason);
  assert.equal(value.i18nKey, i18nKey);
};

function restoresADomainImportSpec() {
  const result = parseTaskSpecForWizard(
    task({
      type: ImportExportTaskType.DATA_FILE_IMPORT,
      specJson: JSON.stringify({
        taskType: 'DATA_FILE_IMPORT',
        taskName: 'Import orders',
        target: { dataSourceId: 7, databaseName: 'shop', schemaName: 'public', tableName: 'orders' },
        sourceFile: '/tmp/import/orders.csv',
        displayFileName: 'orders.csv',
        format: 'CSV',
        dataTimeFormat: 'yyyy-MM-dd',
        csvOptions: { encoding: 'UTF-8', delimiter: ',', hasHeader: true },
        columnMappings: [{ sourceColumn: 'id', targetColumn: 'id' }],
        unmappedTarget: 'DEFAULT',
        mode: 'FAST',
        duplicatePolicy: 'IGNORE',
        failurePolicy: 'SKIP_ERROR_ROWS',
        backendOnlyField: { nested: true },
      }),
    }),
  );
  assert.equal(result.ok, true);
  if (!result.ok) return;
  assert.equal(result.restore.type, ImportExportType.IMPORT);
  assert.deepEqual(result.restore.boundInfo, {
    dataSourceId: 7,
    databaseName: 'shop',
    schemaName: 'public',
    tableName: 'orders',
    type: ImportExportType.IMPORT,
  });
  assert.equal(result.restore.formValue.exportType, ImportExportFileType.CSV);
  assert.equal(result.restore.exportPath, undefined);
  assert.equal(result.restore.sqlImportOptions, undefined);
  assert.deepEqual(result.restore.importPolicies, { duplicatePolicy: 'IGNORE', failurePolicy: 'SKIP_ERROR_ROWS' });
  assert.deepEqual(
    result.restore.notices.map((notice) => notice.code),
    ['SOURCE_FILE', 'FORMAT_OPTIONS', 'IMPORT_POLICIES'],
    'the source file must be re-selected and file-derived options are re-read from the file',
  );
  assert.deepEqual(result.restore.notices[0].params, ['orders.csv']);
  // An unknown backend field must not leak into the restore or break it.
  assert.equal('backendOnlyField' in result.restore, false);
}

function restoresASqlImportSpecWithItsEncoding() {
  const result = parseTaskSpecForWizard(
    task({
      type: ImportExportTaskType.SQL_FILE_IMPORT,
      specJson: JSON.stringify({
        taskType: 'SQL_FILE_IMPORT',
        target: { dataSourceId: 2, databaseName: 'shop' },
        sourceFile: '/tmp/import/dump.sql',
        format: 'SQL',
        sqlImportOptions: { encoding: 'UTF-8' },
      }),
    }),
  );
  assert.equal(result.ok, true);
  if (!result.ok) return;
  assert.equal(result.restore.formValue.exportType, ImportExportFileType.SQL);
  assert.deepEqual(result.restore.sqlImportOptions, { encoding: 'UTF-8' });
  assert.equal(result.restore.boundInfo.tableName, '', 'a known target is not required to re-open the SQL import wizard');
  assert.deepEqual(result.restore.notices[0], {
    code: 'SOURCE_FILE',
    i18nKey: 'workspace.importExport.restoredSourceFile',
    params: ['dump.sql'],
  });
  assert.equal(result.restore.importPolicies, undefined, 'absent policies must not be invented');
}

function restoresADomainExportSpec() {
  const result = parseTaskSpecForWizard(
    task({
      type: ImportExportTaskType.TABLE_DATA_EXPORT,
      specJson: JSON.stringify({
        taskType: 'TABLE_DATA_EXPORT',
        target: { dataSourceId: 7, databaseName: 'shop', schemaName: 'public', tableName: 'orders' },
        tableNames: ['orders'],
        format: 'XLSX',
        scope: 'TABLE',
        containsHeader: false,
        exportPath: '/tmp/export',
        exportSize: 'ALL',
        suggestedFileName: 'orders.xlsx',
      }),
    }),
  );
  assert.equal(result.ok, true);
  if (!result.ok) return;
  assert.equal(result.restore.type, ImportExportType.EXPORT);
  assert.deepEqual(result.restore.boundInfo, {
    dataSourceId: 7,
    databaseName: 'shop',
    schemaName: 'public',
    tableName: 'orders',
    type: ImportExportType.EXPORT,
  });
  assert.deepEqual(result.restore.formValue, {
    exportType: ImportExportFileType.XLSX,
    containsHeader: false,
  });
  assert.equal(result.restore.exportPath, '/tmp/export');
  assert.deepEqual(result.restore.notices, [], 'an export keeps every field the wizard shows');
}

function restoresTheFlatRequestLayout() {
  const result = parseTaskSpecForWizard(
    task({
      type: ImportExportTaskType.TABLE_DATA_EXPORT,
      specJson: JSON.stringify({
        taskType: 'TABLE_DATA_EXPORT',
        dataSourceId: 3,
        databaseName: 'analytics',
        schemaName: 'main',
        tableNames: ['events'],
        format: 'JSON',
        containsHeader: true,
      }),
    }),
  );
  assert.equal(result.ok, true);
  if (!result.ok) return;
  assert.equal(result.restore.boundInfo.dataSourceId, 3);
  assert.equal(result.restore.boundInfo.databaseName, 'analytics');
  assert.equal(result.restore.boundInfo.tableName, 'events');
  assert.equal(result.restore.formValue.exportType, ImportExportFileType.JSON);
}

function fallsBackToTheTaskRowTarget() {
  const result = parseTaskSpecForWizard({
    type: ImportExportTaskType.DATA_FILE_IMPORT,
    specJson: JSON.stringify({ taskType: 'DATA_FILE_IMPORT', format: 'CSV' }),
    target: { dataSourceId: 9, databaseName: 'legacy', schemaName: 'dbo', tableName: 'people' },
  });
  assert.equal(result.ok, true);
  if (!result.ok) return;
  assert.deepEqual(result.restore.boundInfo, {
    dataSourceId: 9,
    databaseName: 'legacy',
    schemaName: 'dbo',
    tableName: 'people',
    type: ImportExportType.IMPORT,
  });
  assert.deepEqual(result.restore.notices[0].code, 'SOURCE_FILE_UNKNOWN');
}

function legacyTasksHaveNoSpec() {
  for (const specJson of [null, undefined, '', '   ']) {
    const value = parseTaskSpecForWizard(task({ type: ImportExportTaskType.DATA_FILE_IMPORT, specJson }));
    expectFailure(value, 'EMPTY_SPEC', 'workspace.task.reuse.unavailable');
  }
  assert.equal(hasSavedTaskSpec({ specJson: null }), false);
  assert.equal(hasSavedTaskSpec({ specJson: '{"taskType":"DATA_FILE_IMPORT"}' }), true);
}

function damagedSnapshotsAreReportedInsteadOfOpeningAnEmptyWizard() {
  for (const specJson of ['{ "taskType": ', 'not json', '[]', 'null', '"DATA_FILE_IMPORT"', '42']) {
    const value = parseTaskSpecForWizard(task({ type: ImportExportTaskType.DATA_FILE_IMPORT, specJson }));
    expectFailure(value, 'INVALID_JSON', 'workspace.task.reuse.invalidSpec');
  }
}

function missingTargetIsReported() {
  const withoutDataSource = parseTaskSpecForWizard(
    task({
      type: ImportExportTaskType.DATA_FILE_IMPORT,
      specJson: JSON.stringify({ taskType: 'DATA_FILE_IMPORT', format: 'CSV', target: { databaseName: 'shop' } }),
    }),
  );
  expectFailure(withoutDataSource, 'MISSING_TARGET', 'workspace.task.reuse.missingTarget');

  const exportWithoutTable = parseTaskSpecForWizard(
    task({
      type: ImportExportTaskType.TABLE_DATA_EXPORT,
      specJson: JSON.stringify({
        taskType: 'TABLE_DATA_EXPORT',
        target: { dataSourceId: 4, databaseName: 'shop' },
        format: 'CSV',
      }),
    }),
  );
  expectFailure(exportWithoutTable, 'MISSING_TARGET', 'workspace.task.reuse.missingTarget');
}

function specsTheWizardCannotReproduceAreRejected() {
  const queryResultExport = parseTaskSpecForWizard(
    task({
      type: ImportExportTaskType.QUERY_RESULT_EXPORT,
      specJson: JSON.stringify({
        taskType: 'QUERY_RESULT_EXPORT',
        target: { dataSourceId: 1, databaseName: 'shop' },
        resultSetId: 12,
        format: 'CSV',
      }),
    }),
  );
  expectFailure(queryResultExport, 'UNSUPPORTED_SPEC', 'workspace.task.reuse.unsupportedSpec');

  const multiTableExport = parseTaskSpecForWizard(
    task({
      type: ImportExportTaskType.TABLE_DATA_EXPORT,
      specJson: JSON.stringify({
        taskType: 'TABLE_DATA_EXPORT',
        target: { dataSourceId: 1, databaseName: 'shop' },
        tableNames: ['orders', 'items'],
        scope: 'ALL',
        format: 'CSV',
      }),
    }),
  );
  expectFailure(multiTableExport, 'UNSUPPORTED_SPEC', 'workspace.task.reuse.unsupportedSpec');

  const sqlExport = parseTaskSpecForWizard(
    task({
      type: ImportExportTaskType.SQL_EXPORT,
      specJson: JSON.stringify({
        taskType: 'SQL_EXPORT',
        target: { dataSourceId: 1, databaseName: 'shop', tableName: 'orders' },
        sql: 'select 1',
        format: 'SQL',
      }),
    }),
  );
  expectFailure(sqlExport, 'UNSUPPORTED_SPEC', 'workspace.task.reuse.unsupportedSpec');

  const unknownType = parseTaskSpecForWizard(
    task({
      type: ImportExportTaskType.DATA_FILE_IMPORT,
      specJson: JSON.stringify({ taskType: 'SOMETHING_ELSE', target: { dataSourceId: 1, databaseName: 'shop' } }),
    }),
  );
  expectFailure(unknownType, 'UNSUPPORTED_SPEC', 'workspace.task.reuse.unsupportedSpec');
}

function unknownFormatsFallBackToTheWizardDefault() {
  const value = parseTaskSpecForWizard(
    task({
      type: ImportExportTaskType.DATA_FILE_IMPORT,
      specJson: JSON.stringify({
        taskType: 'DATA_FILE_IMPORT',
        target: { dataSourceId: 1, databaseName: 'shop' },
        sourceFile: '/tmp/a.parquet',
        format: 'PARQUET',
      }),
    }),
  );
  assert.equal(value.ok, true);
  if (!value.ok) return;
  assert.equal(value.restore.formValue.exportType, undefined, 'an unknown format must not reach the select as a raw value');
}

function run() {
  for (const scenario of [restoresADomainImportSpec, restoresASqlImportSpecWithItsEncoding, restoresADomainExportSpec,
    restoresTheFlatRequestLayout, fallsBackToTheTaskRowTarget, legacyTasksHaveNoSpec,
    damagedSnapshotsAreReportedInsteadOfOpeningAnEmptyWizard, missingTargetIsReported,
    specsTheWizardCannotReproduceAreRejected, unknownFormatsFallBackToTheWizardDefault]) {
    scenario();
    console.log(`ok - ${scenario.name}`);
  }
}

run();
