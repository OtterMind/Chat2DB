import assert from 'node:assert/strict';
import type { ISupportedDatabaseSummary } from '@/service/supportedDatabase';
import {
  DYNAMIC_DATABASE_ICON,
  buildDynamicDatabase,
  buildDynamicFormConfig,
  parseHostPortUrlSample,
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
  assert.equal(db.iconExistDark, true);
  assert.equal(db.supportDatabase, true);
  assert.equal(db.supportSchema, true);
}

// name falls back to dbType
assert.equal(buildDynamicDatabase(summary('HSQLDB', { name: '' })).name, 'HSQLDB');

// host/port style samples produce a full form with sync template and pattern
{
  const config = buildDynamicFormConfig(summary('QUESTDB', { urlSample: 'jdbc:postgresql://localhost:8812/qdb' }));
  assert.equal(config.type, 'QUESTDB');
  const byName = (name: string) => config.baseInfo.items.find((item) => item.name === name);
  assert.equal(byName('host')?.defaultValue, 'localhost');
  assert.equal(byName('port')?.defaultValue, '8812');
  assert.equal(byName('database')?.defaultValue, 'qdb');
  assert.equal(byName('url')?.defaultValue, 'jdbc:postgresql://localhost:8812/qdb');
  assert.equal(config.baseInfo.template, 'jdbc:postgresql://{host}:{port}/{database}');
  // Group convention shared with built-ins: 1=host, 2=port, 4=database.
  const match = 'jdbc:postgresql://myhost:5555/mydb'.match(config.baseInfo.pattern);
  assert.equal(match?.[1], 'myhost');
  assert.equal(match?.[2], '5555');
  assert.equal(match?.[4], 'mydb');
  assert.ok(config.baseInfo.items.some((item) => item.name === 'authenticationType'));
}

// parseHostPortUrlSample handles ports, paths, and rejects file-style URLs
{
  assert.deepEqual(parseHostPortUrlSample('jdbc:iotdb://localhost:6667/'),
    { scheme: 'jdbc:iotdb', host: 'localhost', port: '6667', database: '' });
  assert.equal(parseHostPortUrlSample('jdbc:hsqldb:file:demo'), null);
  assert.equal(parseHostPortUrlSample(null), null);
  assert.deepEqual(parseHostPortUrlSample('jdbc:firebird://localhost:3050//var/lib/fb/demo.fdb')?.database,
    '/var/lib/fb/demo.fdb');
}

// file/embedded style samples fall back to a URL-only form
{
  const config = buildDynamicFormConfig(summary('HSQLDB', { urlSample: 'jdbc:hsqldb:file:demo' }));
  const url = config.baseInfo.items.find((item) => item.name === 'url');
  assert.equal(url?.defaultValue, 'jdbc:hsqldb:file:demo');
  assert.equal(url?.required, true);
  assert.ok(!config.baseInfo.items.some((item) => item.name === 'host'));
  assert.ok(config.baseInfo.pattern.test('jdbc:hsqldb:file:demo'));
  assert.ok(config.baseInfo.items.some((item) => item.name === 'alias'));
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
