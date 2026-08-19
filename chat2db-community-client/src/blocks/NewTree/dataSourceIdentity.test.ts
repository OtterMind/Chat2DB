import assert from 'node:assert/strict';
import { TreeNodeType } from '@/constants/tree';
import type { TreeNodeData } from '@/typings';
import {
  DATA_SOURCE_IDENTITY_NODE_CLASS,
  DATA_SOURCE_IDENTITY_ROOT_CLASS,
  decorateDataSourceIdentityTree,
} from './dataSourceIdentity';

const descendant = {
  key: 'database',
  originalTitle: 'app',
  treeNodeType: TreeNodeType.DATABASE,
  extraParams: { dataSourceId: 1 },
} as TreeNodeData;
const source = {
  key: 'source',
  originalTitle: 'mysql',
  treeNodeType: TreeNodeType.DATA_SOURCE,
  extraParams: { dataSourceId: 1, environment: { id: 1, name: 'Prod', shortName: 'PROD', color: '#445566' } },
  children: [descendant],
} as TreeNodeData;
const identitySource = {
  ...source,
  extraParams: { ...source.extraParams, identityColor: '#112233' },
} as TreeNodeData;
const decorated = decorateDataSourceIdentityTree([source], [identitySource]);

assert.match(decorated?.[0].className || '', new RegExp(DATA_SOURCE_IDENTITY_NODE_CLASS));
assert.match(decorated?.[0].className || '', new RegExp(DATA_SOURCE_IDENTITY_ROOT_CLASS));
assert.equal(decorated?.[0].style?.['--chat2db-data-source-identity-color'], '#112233');
assert.equal(decorated?.[0].style?.['--chat2db-data-source-identity-tint'], 'rgba(17, 34, 51, 0.1)');
assert.match(decorated?.[0].children?.[0].className || '', new RegExp(DATA_SOURCE_IDENTITY_NODE_CLASS));
assert.equal(decorated?.[0].children?.[0].style?.['--chat2db-data-source-identity-color'], '#112233');
assert.equal(decorated?.[0].children?.[0].style?.['--chat2db-data-source-identity-tint'], 'rgba(17, 34, 51, 0.06)');
assert.doesNotMatch(decorated?.[0].children?.[0].className || '', new RegExp(DATA_SOURCE_IDENTITY_ROOT_CLASS));
assert.equal(source.style, undefined);

const undecorated = decorateDataSourceIdentityTree([source], [source]);
assert.doesNotMatch(undecorated?.[0].className || '', new RegExp(DATA_SOURCE_IDENTITY_NODE_CLASS));
assert.equal(undecorated?.[0].style, undefined);
assert.doesNotMatch(undecorated?.[0].children?.[0].className || '', new RegExp(DATA_SOURCE_IDENTITY_NODE_CLASS));

const cleared = decorateDataSourceIdentityTree(decorated, [source]);
assert.doesNotMatch(cleared?.[0].className || '', new RegExp(DATA_SOURCE_IDENTITY_NODE_CLASS));
assert.doesNotMatch(cleared?.[0].className || '', new RegExp(DATA_SOURCE_IDENTITY_ROOT_CLASS));
assert.equal(cleared?.[0].style, undefined);
assert.doesNotMatch(cleared?.[0].children?.[0].className || '', new RegExp(DATA_SOURCE_IDENTITY_NODE_CLASS));
assert.equal(cleared?.[0].children?.[0].style, undefined);

assert.equal(decorateDataSourceIdentityTree(null, [identitySource]), null);

console.log('Data source identity tree decoration tests passed');
