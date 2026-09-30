import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {
  createLogicalDependencyDraft,
  findAutomaticTargetKey,
  getLogicalDependencyIssues,
  getMultiTableSourceIssues,
  multiTableTargetKey,
  normalizeLogicalDependencySequences,
  reconcileLogicalDependenciesWithTargets,
  reconcileMultiTableSources,
  reconcileMultiTableSourcesWithTargets,
  type LogicalDependencyDraft,
  type MultiTableTarget,
} from './model';

const targets: MultiTableTarget[] = [
  { databaseName: 'app', schemaName: 'public', tableName: 'users' },
  { databaseName: 'app', schemaName: 'public', tableName: 'orders' },
];
const usersKey = multiTableTargetKey(targets[0]);
const ordersKey = multiTableTargetKey(targets[1]);

assert.equal(findAutomaticTargetKey('USERS.csv', targets, new Set()), usersKey);
assert.equal(findAutomaticTargetKey('users.backup.csv', targets, new Set()), undefined);
assert.equal(findAutomaticTargetKey('users.csv', targets, new Set([usersKey])), undefined);

let sequence = 0;
const duplicateNameSelections = [
  { fileName: 'users.csv', file: { name: 'users.csv', size: 1 } as File },
  { fileName: 'users.csv', file: { name: 'users.csv', size: 2 } as File },
];
const sources = reconcileMultiTableSources([], duplicateNameSelections, targets, () => `source-${++sequence}`);
assert.deepEqual(
  sources.map(({ id, targetKey }) => ({ id, targetKey })),
  [
    { id: 'source-1', targetKey: usersKey },
    { id: 'source-2', targetKey: undefined },
  ],
);
const reconciled = reconcileMultiTableSources(
  sources,
  [duplicateNameSelections[1]],
  targets,
  () => `source-${++sequence}`,
);
assert.equal(reconciled[0].id, 'source-2');

const readySources = sources.map((source, index) => ({
  ...source,
  targetKey: index === 0 ? usersKey : ordersKey,
  fileId: `file-${index}`,
  status: 'READY' as const,
}));
assert.deepEqual(getMultiTableSourceIssues(readySources), {
  empty: false,
  staging: false,
  failed: [],
  unmapped: [],
  invalidTargets: [],
  duplicateTargets: false,
  ready: true,
});
assert.equal(
  getMultiTableSourceIssues(readySources.map((source) => ({ ...source, targetKey: usersKey }))).duplicateTargets,
  true,
);
assert.equal(getMultiTableSourceIssues(readySources.map((source) => ({ ...source, fileId: undefined }))).ready, false);

const caseDistinctTargets = [
  { databaseName: 'app', schemaName: 'public', tableName: 'Users' },
  { databaseName: 'app', schemaName: 'public', tableName: 'users' },
];
const caseDistinctSources = readySources.map((source, index) => ({
  ...source,
  targetKey: multiTableTargetKey(caseDistinctTargets[index]),
}));
assert.equal(getMultiTableSourceIssues(caseDistinctSources).duplicateTargets, false);

const selfReference: LogicalDependencyDraft = {
  id: 'dependency-1',
  constraintName: 'fk_users_manager',
  keySequence: 1,
  parentTargetKey: usersKey,
  parentColumn: 'id',
  childTargetKey: usersKey,
  childColumn: 'manager_id',
};
assert.deepEqual(getLogicalDependencyIssues([selfReference], new Set([usersKey, ordersKey])), {
  incomplete: false,
  unknownTarget: false,
  duplicate: false,
  invalidGroup: false,
});
assert.equal(
  getLogicalDependencyIssues([selfReference, { ...selfReference, id: 'dependency-2' }], new Set([usersKey])).duplicate,
  true,
);
const caseDistinctColumns: LogicalDependencyDraft[] = [
  {
    ...selfReference,
    id: 'dependency-case-1',
    constraintName: 'fk_case_distinct',
    parentColumn: 'ID',
    childColumn: 'PARENT_ID',
    keySequence: 1,
  },
  {
    ...selfReference,
    id: 'dependency-case-2',
    constraintName: 'fk_case_distinct',
    parentColumn: 'id',
    childColumn: 'parent_id',
    keySequence: 2,
  },
];
assert.deepEqual(getLogicalDependencyIssues(caseDistinctColumns, new Set([usersKey])), {
  incomplete: false,
  unknownTarget: false,
  duplicate: false,
  invalidGroup: false,
});
assert.equal(
  getLogicalDependencyIssues([{ ...selfReference, childColumn: undefined }], new Set([usersKey])).incomplete,
  true,
);

assert.deepEqual(createLogicalDependencyDraft('42'), {
  id: '42',
  constraintName: 'chat2db-logical-42',
  keySequence: 1,
});

const compositeDependency: LogicalDependencyDraft[] = [
  {
    ...selfReference,
    id: 'dependency-composite-1',
    constraintName: 'fk_orders_users',
    parentTargetKey: usersKey,
    parentColumn: 'tenant_id',
    childTargetKey: ordersKey,
    childColumn: 'user_tenant_id',
    keySequence: 1,
  },
  {
    ...selfReference,
    id: 'dependency-composite-2',
    constraintName: 'fk_orders_users',
    parentTargetKey: usersKey,
    parentColumn: 'id',
    childTargetKey: ordersKey,
    childColumn: 'user_id',
    keySequence: 2,
  },
];
assert.deepEqual(getLogicalDependencyIssues(compositeDependency, new Set([usersKey, ordersKey])), {
  incomplete: false,
  unknownTarget: false,
  duplicate: false,
  invalidGroup: false,
});
assert.equal(
  getLogicalDependencyIssues(
    [compositeDependency[0], { ...compositeDependency[1], keySequence: 3 }],
    new Set([usersKey, ordersKey]),
  ).invalidGroup,
  true,
);
assert.equal(
  getLogicalDependencyIssues(
    [compositeDependency[0], { ...compositeDependency[1], childTargetKey: usersKey }],
    new Set([usersKey, ordersKey]),
  ).invalidGroup,
  true,
);
assert.equal(normalizeLogicalDependencySequences(compositeDependency.slice(1))[0].keySequence, 1);

const archiveUsersTarget = { databaseName: 'app', schemaName: 'archive', tableName: 'users' };
const archiveUsersKey = multiTableTargetKey(archiveUsersTarget);
const remappedSources = reconcileMultiTableSourcesWithTargets([readySources[0]], [archiveUsersTarget]);
assert.equal(remappedSources[0].targetKey, archiveUsersKey);
assert.equal(getMultiTableSourceIssues(readySources, new Set([ordersKey])).invalidTargets.length, 1);
assert.equal(getMultiTableSourceIssues(readySources, new Set([ordersKey])).ready, false);

const reconciledDependencies = reconcileLogicalDependenciesWithTargets([selfReference], new Set([ordersKey]));
assert.equal(reconciledDependencies[0].parentTargetKey, undefined);
assert.equal(reconciledDependencies[0].parentColumn, undefined);
assert.equal(reconciledDependencies[0].childTargetKey, undefined);
assert.equal(reconciledDependencies[0].childColumn, undefined);

const wizardSource = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');
assert.match(wizardSource, /data-testid="multi-table-step-sources" hidden=\{currentStep !== 0\}/);
assert.match(wizardSource, /data-testid="multi-table-step-dependencies" hidden=\{currentStep !== 1\}/);
assert.match(wizardSource, /data-testid="multi-table-step-validation" hidden=\{currentStep !== 2\}/);
assert.match(wizardSource, /requestedColumns\.current\.delete\(targetKey\)/);
assert.match(wizardSource, /columnLoadFailures\.map/);
assert.match(wizardSource, /column=\{\{ xs: 1, sm: 2 \}\}/);
assert.match(wizardSource, /aria-labelledby="multi-table-source-kind-label"/);

console.log('Multi-table import model tests passed');
