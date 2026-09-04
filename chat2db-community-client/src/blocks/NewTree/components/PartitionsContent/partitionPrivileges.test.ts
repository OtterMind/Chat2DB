import assert from 'node:assert/strict';
import clientExtension from '@client-extension';
import {
  allowsResourceOperations,
  RESOURCE_OPERATIONS,
} from '@/client-extension/resourceOperationCapabilities';
import type { ResourceOperation, ResourceOperationCapabilities } from '@/client-extension/types';
import { PARTITION_REQUIRED_OPERATIONS } from './partitionPrivileges';

const capabilities = (alterAllowed: boolean): ResourceOperationCapabilities =>
  Object.fromEntries(
    RESOURCE_OPERATIONS.map((operation: ResourceOperation) => [operation, operation === 'ALTER' ? alterAllowed : true]),
  ) as ResourceOperationCapabilities;

const originalResourceOperations = clientExtension.resourceOperations;
clientExtension.resourceOperations = async () => capabilities(true);

assert.deepEqual(PARTITION_REQUIRED_OPERATIONS, ['ALTER']);
assert.equal(
  allowsResourceOperations(capabilities(false), PARTITION_REQUIRED_OPERATIONS),
  false,
  'partition maintenance is hidden without table ALTER permission',
);
assert.equal(
  allowsResourceOperations(capabilities(true), PARTITION_REQUIRED_OPERATIONS),
  true,
  'partition maintenance is available when ALTER is allowed',
);
clientExtension.resourceOperations = originalResourceOperations;

console.log('Partition privilege tests passed');
