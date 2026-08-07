import assert from 'node:assert/strict';
import { TreeNodeType } from '@/constants/tree';
import type { TreeNodeData } from '@/typings';
import { collectDataSourceNodes, pruneDataSourceRuntimeAvailability } from './dataSourceList';

const source = (id: number, name: string, identityColor: string): TreeNodeData => ({
  key: `dataSource_${id}`,
  originalTitle: name,
  treeNodeType: TreeNodeType.DATA_SOURCE,
  extraParams: {
    dataSourceId: id,
    dataSourceName: name,
    identityColor,
    watermarkEnabled: true,
    watermarkContent: `${name}-watermark`,
    environmentId: id,
    environment: { id, name: `${name}-environment`, shortName: name, color: identityColor },
  },
});
const updatedSource = source(7, 'renamed-orders', '#AABBCC');
const tree: TreeNodeData[] = [
  {
    key: 'group_1',
    originalTitle: 'group',
    treeNodeType: TreeNodeType.GROUP,
    extraParams: { groupId: 1 },
    children: [updatedSource, source(8, 'warehouse', '#112233')],
  },
];

const flattened = collectDataSourceNodes(tree);
assert.deepEqual(
  flattened.map((item) => item.extraParams.dataSourceId),
  [7, 8],
);
assert.equal(flattened[0], updatedSource);
assert.equal(flattened[0].extraParams.dataSourceName, 'renamed-orders');
assert.equal(flattened[0].extraParams.environment?.name, 'renamed-orders-environment');
assert.equal(flattened[0].extraParams.identityColor, '#AABBCC');
assert.equal(flattened[0].extraParams.watermarkEnabled, true);
assert.equal(flattened[0].extraParams.watermarkContent, 'renamed-orders-watermark');
assert.deepEqual(
  pruneDataSourceRuntimeAvailability(flattened, {
    7: 'available',
    8: 'unavailable',
    9: 'available',
  }),
  { 7: 'available', 8: 'unavailable' },
);

console.log('Data source list tests passed');
