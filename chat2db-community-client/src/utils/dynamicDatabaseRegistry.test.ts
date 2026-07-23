import assert from 'node:assert/strict';
import type { ISupportedDatabaseSummary } from '@/service/supportedDatabase';
import {
  DYNAMIC_DATABASE_ICON,
  buildDynamicDatabase,
  buildDynamicFormConfig,
  registerDynamicDatabases,
} from './dynamicDatabaseRegistry';

const summary = (dbType: string, over: Partial<ISupportedDatabaseSummary> = {}): ISupportedDatabaseSummary => ({
  dbType,
  name: over.name ?? dbType,
  supportDatabase: over.supportDatabase ?? true,
  supportSchema: over.supportSchema ?? false,
  urlSample: over.urlSample ?? `jdbc:${dbType.toLowerCase()}://localhost:1234/db`,
  ...over,
});

const freshRegistries = () => ({
  databaseMap: { MYSQL: { name: 'MySQL', code: 'MYSQL', icon: 'x', supportDatabase: true, supportSchema: false } } as any,
  databaseTypeList: [] as any[],
  dataSourceFormConfigs: [] as any[],
});

// buildDynamicDatabase maps summary fields and applies the fallback icon
{
  const db = buildDynamicDatabase(summary('FIREBIRD', { name: 'Firebird', supportSchema: true }));
  assert.equal(db.name, 'Firebird');
  assert.equal(db.code, 'FIREBIRD');
  assert.equal(db.icon, DYNAMIC_DATABASE_ICON);
  assert.equal(db.supportDatabase, true);
  assert.equal(db.supportSchema, true);
}

// name falls back to dbType
assert.equal(buildDynamicDatabase(summary('HSQLDB', { name: '' })).name, 'HSQLDB');

// buildDynamicFormConfig seeds the URL field with the sample and a permissive pattern
{
  const config = buildDynamicFormConfig(summary('FIREBIRD', { urlSample: 'jdbc:firebird://h:3050/d' }));
  assert.equal(config.type, 'FIREBIRD');
  const url = config.baseInfo.items.find((item) => item.name === 'url');
  assert.equal(url?.defaultValue, 'jdbc:firebird://h:3050/d');
  assert.equal(url?.required, true);
  assert.ok(config.baseInfo.pattern.test('jdbc:firebird://h:3050/d'));
  assert.equal(config.baseInfo.template, 'jdbc:firebird://h:3050/d');
  assert.ok(config.baseInfo.items.some((item) => item.name === 'alias'));
  assert.ok(config.baseInfo.items.some((item) => item.name === 'authenticationType'));
}

// registerDynamicDatabases adds unknown types, skips known ones, never duplicates
{
  const registries = freshRegistries();
  const added = registerDynamicDatabases(
    [summary('MYSQL'), summary('FIREBIRD'), summary('FIREBIRD'), summary('  '), null as any],
    registries,
  );
  assert.deepEqual(added, ['FIREBIRD']);
  assert.ok(registries.databaseMap.FIREBIRD);
  assert.equal(registries.databaseTypeList.length, 1);
  assert.equal(registries.dataSourceFormConfigs.length, 1);
  // second run is a no-op
  assert.deepEqual(registerDynamicDatabases([summary('FIREBIRD')], registries), []);
  assert.equal(registries.dataSourceFormConfigs.length, 1);
}

// tolerates null/undefined input
assert.deepEqual(registerDynamicDatabases(null, freshRegistries()), []);
assert.deepEqual(registerDynamicDatabases(undefined, freshRegistries()), []);

console.log('dynamicDatabaseRegistry tests passed');
