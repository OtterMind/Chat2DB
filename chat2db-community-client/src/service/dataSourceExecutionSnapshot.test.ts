import assert from 'node:assert/strict';
import type { DatabaseTypeCode } from '@/constants';
import type { IBoundInfo } from '@/typings';
import {
  attachDataSourceExecutionId,
  captureDataSourceExecutionSnapshot,
  createDataSourceExecutionBoundInfo,
  createDataSourceExecutionSnapshot,
  createDataSourceExecutionSnapshotRegistry,
  getDataSourceExecutionSnapshot,
  getDataSourceExecutionTargetLabel,
  registerDataSourceExecutionSnapshot,
  releaseDataSourceExecutionSnapshot,
} from './dataSourceExecutionSnapshot';

const registry = createDataSourceExecutionSnapshotRegistry();
const boundInfo: IBoundInfo = {
  consoleId: 41,
  dataSourceId: 17,
  dataSourceName: 'orders-primary',
  identityColor: '#12AB34',
  environmentId: 3,
  environment: {
    id: 3,
    name: 'Production',
    shortName: 'PROD',
    color: '#FF0000',
  },
  databaseName: 'orders',
  schemaName: 'public',
  databaseType: 'MYSQL' as DatabaseTypeCode,
  connectable: true,
};

const snapshot = captureDataSourceExecutionSnapshot(registry, 4, boundInfo, 1234);
assert.equal(Object.isFrozen(snapshot), true);
assert.deepEqual(snapshot, {
  consoleId: 41,
  dataSourceId: 17,
  dataSourceName: 'orders-primary',
  environmentId: 3,
  environmentName: 'Production',
  environmentShortName: 'PROD',
  databaseName: 'orders',
  schemaName: 'public',
  databaseType: 'MYSQL',
  connectable: true,
  startedAt: 1234,
});
assert.equal(
  Object.prototype.hasOwnProperty.call(snapshot, 'identityColor'),
  false,
  'presentation color must remain dynamically resolved instead of being frozen into execution identity',
);

boundInfo.consoleId = 42;
boundInfo.dataSourceName = 'renamed-after-start';
boundInfo.databaseName = 'switched-after-start';
boundInfo.environment!.shortName = 'STAGE';
assert.equal(snapshot.dataSourceName, 'orders-primary');
assert.equal(snapshot.consoleId, 41);
assert.equal(snapshot.databaseName, 'orders');
assert.equal(snapshot.environmentShortName, 'PROD');

assert.equal(attachDataSourceExecutionId(registry, 4, 'execution-4'), snapshot);
assert.equal(getDataSourceExecutionSnapshot(registry, { executionId: 'execution-4' }), snapshot);
assert.equal(getDataSourceExecutionSnapshot(registry, { executionSequence: 4 }), snapshot);
assert.equal(getDataSourceExecutionTargetLabel(snapshot), 'PROD / orders-primary / orders / public');

releaseDataSourceExecutionSnapshot(registry, { executionId: 'execution-4' });
assert.equal(getDataSourceExecutionSnapshot(registry, { executionId: 'execution-4' }), undefined);
assert.equal(getDataSourceExecutionSnapshot(registry, { executionSequence: 4 }), undefined);

const clickTarget = createDataSourceExecutionSnapshot(
  {
    dataSourceId: 21,
    dataSourceName: 'source-at-click',
    databaseName: 'database-at-click',
    schemaName: 'schema-at-click',
    databaseType: 'MYSQL' as DatabaseTypeCode,
  },
  6000,
);
const mutableEditorTarget: IBoundInfo = {
  dataSourceId: 22,
  dataSourceName: 'source-after-parser',
  databaseName: 'database-after-parser',
  schemaName: 'schema-after-parser',
  databaseType: 'POSTGRESQL' as DatabaseTypeCode,
};
const registeredClickTarget = registerDataSourceExecutionSnapshot(registry, 6, clickTarget);
assert.equal(registeredClickTarget, clickTarget, 'execution registration must preserve the click-time snapshot');
assert.notEqual(registeredClickTarget.dataSourceId, mutableEditorTarget.dataSourceId);
assert.deepEqual(getDataSourceExecutionSnapshot(registry, { executionSequence: 6 }), clickTarget);
releaseDataSourceExecutionSnapshot(registry, { executionSequence: 6 });

const parserBoundInfoSource: IBoundInfo = {
  consoleId: 71,
  dataSourceId: 21,
  dataSourceName: 'source-at-click',
  environment: { id: 3, name: 'Production', shortName: 'PROD', color: '#FF0000' },
};
const parserBoundInfo = createDataSourceExecutionBoundInfo(parserBoundInfoSource);
parserBoundInfoSource.consoleId = 72;
parserBoundInfoSource.dataSourceId = 22;
parserBoundInfoSource.environment!.shortName = 'STAGE';
assert.equal(Object.isFrozen(parserBoundInfo), true);
assert.equal(Object.isFrozen(parserBoundInfo.environment), true);
assert.equal(parserBoundInfo.consoleId, 71, 'quick parsing retains the click-time console id');
assert.equal(parserBoundInfo.dataSourceId, 21);
assert.equal(parserBoundInfo.environment?.shortName, 'PROD');

captureDataSourceExecutionSnapshot(registry, 5, boundInfo, 5678);
releaseDataSourceExecutionSnapshot(registry, { executionSequence: 5 });
assert.equal(getDataSourceExecutionSnapshot(registry, { executionSequence: 5 }), undefined);
assert.equal(attachDataSourceExecutionId(registry, 99, 'missing-execution'), undefined);
assert.equal(getDataSourceExecutionTargetLabel(undefined), '');

console.log('Data source execution snapshot tests passed');
