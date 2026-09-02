import assert from 'node:assert/strict';
import { DatabaseTypeCode } from '@/constants/common';
import { DatabaseCapability } from '@/constants/databaseCapabilities';
import { isDatabaseCapabilitySupported } from '@/utils/databaseJudgments';
import { isBackendCompletionModel, setBackendCompletionModel } from './sqlCompletionModelMode';

const model = {} as any;

assert.equal(
  isDatabaseCapabilitySupported(DatabaseTypeCode.MYSQL, DatabaseCapability.BACKEND_COMPLETION),
  true,
  'MySQL uses backend completion mode',
);
assert.equal(
  isDatabaseCapabilitySupported(DatabaseTypeCode.POSTGRESQL, DatabaseCapability.BACKEND_COMPLETION),
  false,
  'non-configured databases keep legacy completion mode',
);
assert.equal(
  isDatabaseCapabilitySupported(DatabaseTypeCode.GAUSSDB, DatabaseCapability.BACKEND_COMPLETION),
  false,
  'GaussDB keeps legacy completion mode while using backend editor hints',
);
assert.equal(
  isDatabaseCapabilitySupported(undefined, DatabaseCapability.BACKEND_COMPLETION),
  false,
  'missing database type keeps legacy completion mode',
);
assert.equal(
  isDatabaseCapabilitySupported(DatabaseTypeCode.MYSQL, DatabaseCapability.BACKEND_EDITOR_HINTS),
  true,
  'MySQL supports backend editor hints',
);
assert.equal(
  isDatabaseCapabilitySupported(DatabaseTypeCode.POSTGRESQL, DatabaseCapability.BACKEND_EDITOR_HINTS),
  true,
  'PostgreSQL supports backend editor hints without switching completion mode',
);
assert.equal(
  isDatabaseCapabilitySupported(DatabaseTypeCode.GAUSSDB, DatabaseCapability.BACKEND_EDITOR_HINTS),
  true,
  'GaussDB supports PostgreSQL-compatible backend editor hints',
);
assert.equal(
  isDatabaseCapabilitySupported(DatabaseTypeCode.SQLSERVER, DatabaseCapability.BACKEND_EDITOR_HINTS),
  false,
  'unvalidated SQL dialects do not request INSERT editor hints',
);
assert.equal(
  isDatabaseCapabilitySupported(DatabaseTypeCode.MONGODB, DatabaseCapability.BACKEND_EDITOR_HINTS),
  false,
  'non-relational databases do not request SQL INSERT editor hints',
);
assert.equal(
  isDatabaseCapabilitySupported(DatabaseTypeCode.GBASE8S, DatabaseCapability.BACKEND_EDITOR_HINTS),
  false,
  'databases without a SQL syntax plugin do not advertise backend editor hints',
);

assert.equal(isBackendCompletionModel(model), false, 'model is not marked by default');

setBackendCompletionModel(model, true);
assert.equal(isBackendCompletionModel(model), true, 'model can be marked as backend completion');

setBackendCompletionModel(model, false);
assert.equal(isBackendCompletionModel(model), false, 'model can be unmarked');

setBackendCompletionModel(null, true);
setBackendCompletionModel(undefined, false);
assert.equal(isBackendCompletionModel(null), false, 'null model is ignored');
assert.equal(isBackendCompletionModel(undefined), false, 'undefined model is ignored');

console.log('sqlCompletionModelMode tests passed');
