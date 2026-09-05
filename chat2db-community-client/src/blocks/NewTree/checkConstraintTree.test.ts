import assert from 'node:assert/strict';
import { before, test } from 'node:test';
import { DatabaseTypeCode } from '@/constants/common';
import { OperationColumn, TreeNodeType } from '@/constants/tree';
import { isMysqlCheckConstraintsSupported } from '@/utils/mysqlCheckConstraints';
import {
  createCheckConstraintNodes,
  createCheckConstraintsTreeNodeKey,
  loadCheckConstraintNodes,
} from './checkConstraintTreeNodes';

const globalObject = globalThis as typeof globalThis & {
  __APP_NAME__?: string;
  __APP_VERSION__?: string;
  __APP_CAPITAL_NAME__?: string;
  __APP_DISPLAY_NAME__?: string;
  __APP_PROTOCOL_SCHEME__?: string;
  __RUNTIME_ENV__?: string;
  __ENV__?: string;
  window?: any;
  navigator?: any;
  location?: any;
  matchMedia?: any;
};

globalObject.__APP_NAME__ = 'chat2db-community-test';
globalObject.__APP_VERSION__ = '5.3.0';
globalObject.__APP_CAPITAL_NAME__ = 'Chat2DB Community';
globalObject.__APP_DISPLAY_NAME__ = 'Chat2DB Community';
globalObject.__APP_PROTOCOL_SCHEME__ = 'chat2db-community';
globalObject.__RUNTIME_ENV__ = 'community';
globalObject.__ENV__ = 'test';
globalObject.window = globalObject.window || {};
globalObject.window.javaQuery = undefined;
globalObject.window.location = globalObject.window.location || {
  href: 'http://localhost/',
  reload: () => {},
};
globalObject.location = globalObject.location || { search: '' };
globalObject.matchMedia = globalObject.matchMedia || (() => ({
  matches: false,
  addEventListener: () => {},
  removeEventListener: () => {},
}));
if (!globalObject.navigator) {
  Object.defineProperty(globalObject, 'navigator', {
    value: { userAgent: 'node' },
    configurable: true,
  });
}

let dropMenuConfig: typeof import('./menuConfig').dropMenuConfig;

before(async () => {
  ({ dropMenuConfig } = await import('./menuConfig'));
});

const baseParams = {
  dataSourceId: 1,
  databaseName: 'sales',
  schemaName: undefined,
  tableName: 'payment',
  databaseType: DatabaseTypeCode.MYSQL,
};

const constraints = [
  {
    name: 'ck_payment_amount',
    expression: '`amount` >= 0',
    enforced: true,
  },
  {
    name: 'payment_chk_1',
    expression: 'status in ("paid", "void")',
    enforced: false,
  },
];

const getCheckGroup = (dbVersion?: string | null) => {
  if (!isMysqlCheckConstraintsSupported(baseParams.databaseType, dbVersion)) {
    return undefined;
  }
  return {
    key: createCheckConstraintsTreeNodeKey(baseParams),
    treeNodeType: TreeNodeType.CHECK_CONSTRAINTS,
    children: createCheckConstraintNodes(baseParams, constraints),
  };
};

test('adds MySQL CHECK constraint child nodes only when authoritative dbVersion supports enforcement', async () => {
  const checkGroup = getCheckGroup('8.0.16');

  assert.equal(checkGroup?.treeNodeType, TreeNodeType.CHECK_CONSTRAINTS);
  assert.equal(checkGroup?.children?.length, 2);
  assert.equal(checkGroup?.children?.[0].treeNodeType, TreeNodeType.CHECK_CONSTRAINT);
  assert.equal(checkGroup?.children?.[0].originalTitle, 'ck_payment_amount');
  assert.equal(checkGroup?.children?.[0].extraParams.checkConstraintName, 'ck_payment_amount');
  assert.equal(checkGroup?.children?.[0].extraParams.checkExpression, '`amount` >= 0');
  assert.equal(checkGroup?.children?.[0].extraParams.checkEnforced, true);
  assert.deepEqual(checkGroup?.children?.[0].decorativeParams, {
    expression: '`amount` >= 0',
    enforced: true,
  });
  assert.equal(checkGroup?.children?.[1].originalTitle, 'payment_chk_1');
  assert.equal(checkGroup?.children?.[1].extraParams.checkEnforced, false);
});

test('does not add CHECK nodes for old or unknown MySQL versions', async () => {
  assert.equal(getCheckGroup('8.0.15'), undefined);
  assert.equal(getCheckGroup(null), undefined);
});

test('CHECK constraint tree nodes expose an editor-open menu path', () => {
  assert.deepEqual(dropMenuConfig.DEFAULT[TreeNodeType.CHECK_CONSTRAINTS], [
    OperationColumn.CopyName,
    OperationColumn.Refresh,
  ]);
  assert.deepEqual(dropMenuConfig.DEFAULT[TreeNodeType.CHECK_CONSTRAINT], [
    OperationColumn.OpenCheckConstraint,
    OperationColumn.CopyName,
  ]);
});

test('CHECK constraint group refresh loads current metadata when supported', async () => {
  const children = await loadCheckConstraintNodes(baseParams, async (params) => {
    assert.deepEqual(params, {
      dataSourceId: baseParams.dataSourceId,
      databaseName: baseParams.databaseName,
      schemaName: baseParams.schemaName,
      tableName: baseParams.tableName,
      refresh: true,
    });
    return {
      dbVersion: '8.0.16',
      checkConstraintList: constraints,
    };
  });

  assert.equal(children.length, 2);
  assert.equal(children[0].extraParams.checkConstraintName, 'ck_payment_amount');
});

test('CHECK constraint group refresh fails closed for unsupported metadata version', async () => {
  const children = await loadCheckConstraintNodes(baseParams, async () => ({
    dbVersion: '8.0.15',
    checkConstraintList: constraints,
  }));

  assert.deepEqual(children, []);
});

console.log('checkConstraintTree tests passed');
