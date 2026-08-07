import assert from 'node:assert/strict';
import { DatabaseTypeCode } from '@/constants/common';
import { TreeNodeType } from '@/constants/tree';
import type { TreeNodeData } from '@/typings';
import { resolveAIDataSourceContext } from './dataSourceContext';

const environment = {
  id: 22,
  name: 'Production',
  shortName: 'PROD',
  color: '#AA0000',
};
const dataSourceList = [
  {
    key: 'dataSource_11',
    originalTitle: 'shared-name',
    treeNodeType: TreeNodeType.DATA_SOURCE,
    extraParams: {
      dataSourceId: 11,
      dataSourceName: 'shared-name',
      databaseType: DatabaseTypeCode.MYSQL,
      environmentId: 11,
      identityColor: '#111111',
    },
  },
  {
    key: 'dataSource_22',
    originalTitle: 'shared-name',
    treeNodeType: TreeNodeType.DATA_SOURCE,
    extraParams: {
      dataSourceId: 22,
      dataSourceName: 'shared-name',
      databaseType: DatabaseTypeCode.POSTGRESQL,
      environment,
      identityColor: '#22AA44',
      watermarkEnabled: true,
      watermarkContent: 'Finance',
    },
  },
] as TreeNodeData[];

assert.deepEqual(resolveAIDataSourceContext(dataSourceList, 22), {
  dataSourceId: 22,
  dataSourceName: 'shared-name',
  databaseType: DatabaseTypeCode.POSTGRESQL,
  environmentId: 22,
  environment,
  identityColor: '#22AA44',
  watermarkEnabled: true,
  watermarkContent: 'Finance',
});
assert.equal(resolveAIDataSourceContext(dataSourceList, 99), undefined);
assert.equal(resolveAIDataSourceContext(dataSourceList, undefined), undefined);

console.log('AI data source context tests passed');
