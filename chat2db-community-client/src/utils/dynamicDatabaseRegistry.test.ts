import assert from 'node:assert/strict';
import type { ISupportedDatabaseSummary } from '@/service/supportedDatabase';
import {
  DYNAMIC_DATABASE_ICON,
  buildDynamicDatabase,
  buildDynamicFormConfig,
  buildIconSprite,
  buildIconSymbol,
  dynamicIconCode,
  parseFileUrlSample,
  parseHostPortUrlSample,
  registerDynamicDatabases,
} from './dynamicDatabaseRegistry';

const SVG = '<svg viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg"><rect fill="#F0491F"/></svg>';

const summary = (dbType: string, over: Partial<ISupportedDatabaseSummary> = {}): ISupportedDatabaseSummary => ({
  dbType,
  name: over.name ?? dbType,
  supportDatabase: over.supportDatabase ?? true,
  supportSchema: over.supportSchema ?? false,
  urlSample: over.urlSample ?? `jdbc:${dbType.toLowerCase()}://localhost:1234/db`,
  ...over,
});

const shared = {
  envItem: { name: 'environmentId', marker: 'env' } as any,
  storageItem: { name: 'storageType', marker: 'storage' } as any,
  portItem: { name: 'port', styles: { width: '30%', labelAlign: 'right' } } as any,
  sshConfig: { items: [{ name: 'use' }] } as any,
};

const freshRegistries = () => ({
  databaseMap: { MYSQL: { name: 'MySQL', code: 'MYSQL', icon: 'x', supportDatabase: true, supportSchema: false } } as any,
  databaseTypeList: [] as any[],
  dataSourceFormConfigs: [] as any[],
});

// icon: backend SVG becomes a sprite symbol keyed by dynamic code
{
  const withIcon = summary('FIREBIRD', { icon: SVG });
  assert.equal(dynamicIconCode('FIREBIRD'), 'icon-colourful-dyn-firebird');
  const symbol = buildIconSymbol(withIcon)!;
  assert.ok(symbol.startsWith('<symbol id="icon-colourful-dyn-firebird" viewBox="0 0 1024 1024">'));
  assert.ok(symbol.includes('<rect fill="#F0491F"/>'));
  assert.ok(buildIconSprite([withIcon]).includes(symbol));
  assert.equal(buildIconSymbol(summary('X', { icon: 'not-svg' })), null);
  assert.equal(buildIconSprite([summary('X')]), '');
  // database entry uses its own icon when provided, fallback glyph otherwise
  assert.equal(buildDynamicDatabase(withIcon).icon, 'icon-colourful-dyn-firebird');
  const fallback = buildDynamicDatabase(summary('HSQLDB'));
  assert.equal(fallback.icon, DYNAMIC_DATABASE_ICON);
  assert.equal(fallback.iconExistDark, true);
}

// host/port form: shared env/storage/port items included, sync template/pattern derived
{
  const config = buildDynamicFormConfig(summary('QUESTDB', { urlSample: 'jdbc:postgresql://localhost:8812/qdb' }), shared);
  const names = config.baseInfo.items.map((item) => item.name);
  assert.deepEqual(names, ['alias', 'environmentId', 'storageType', 'host', 'port', 'authenticationType', 'database', 'url']);
  const byName = (name: string) => config.baseInfo.items.find((item) => item.name === name) as any;
  assert.equal(byName('environmentId').marker, 'env', 'reuses the exact built-in env item');
  assert.equal(byName('storageType').marker, 'storage');
  assert.equal(byName('port').styles.labelAlign, 'right', 'reuses built-in port styles');
  assert.equal(byName('port').defaultValue, '8812');
  assert.equal(byName('host').defaultValue, 'localhost');
  assert.equal(byName('database').defaultValue, 'qdb');
  assert.equal(config.baseInfo.template, 'jdbc:postgresql://{host}:{port}/{database}');
  const match = 'jdbc:postgresql://myhost:5555/mydb'.match(config.baseInfo.pattern);
  assert.equal(match?.[1], 'myhost');
  assert.equal(match?.[2], '5555');
  assert.equal(match?.[4], 'mydb');
  assert.deepEqual(config.ssh, shared.sshConfig, 'reuses the built-in ssh section');
}

// file form: real file picker plus file<->url sync, mirroring the SQLite shape
{
  const config = buildDynamicFormConfig(summary('HSQLDB', { urlSample: 'jdbc:hsqldb:file:demo' }), shared);
  const file = config.baseInfo.items.find((item) => item.name === 'file')!;
  assert.equal(file.inputType, 'file');
  assert.equal(file.defaultValue, 'demo');
  assert.equal(config.baseInfo.template, 'jdbc:hsqldb:file:{file}');
  assert.equal('jdbc:hsqldb:file:mydata.db'.match(config.baseInfo.pattern)?.[1], 'mydata.db');
  assert.ok(config.baseInfo.items.some((item) => item.name === 'environmentId'));
}

// sample parsers
{
  assert.deepEqual(parseHostPortUrlSample('jdbc:iotdb://localhost:6667/'),
    { scheme: 'jdbc:iotdb', host: 'localhost', port: '6667', database: '' });
  assert.equal(parseHostPortUrlSample('jdbc:hsqldb:file:demo'), null);
  assert.equal(parseHostPortUrlSample(null), null);
  assert.equal(parseHostPortUrlSample('jdbc:firebird://localhost:3050//var/lib/fb/demo.fdb')?.database,
    '/var/lib/fb/demo.fdb');
  assert.deepEqual(parseFileUrlSample('jdbc:derby:demo;create=true'),
    { prefix: 'jdbc:derby:', file: 'demo;create=true' });
  assert.equal(parseFileUrlSample('jdbc:trino://localhost:8080'), null);
}

// registration: adds unknown types, skips known, dedupes, idempotent, null-safe
{
  const registries = freshRegistries();
  const added = registerDynamicDatabases(
    [summary('MYSQL'), summary('FIREBIRD', { icon: SVG }), summary('FIREBIRD'), summary('  '), null as any],
    registries, shared,
  );
  assert.deepEqual(added, ['FIREBIRD']);
  assert.equal(registries.databaseMap.FIREBIRD.icon, 'icon-colourful-dyn-firebird');
  assert.equal(registries.databaseTypeList.length, 1);
  assert.equal(registries.dataSourceFormConfigs.length, 1);
  assert.deepEqual(registerDynamicDatabases([summary('FIREBIRD')], registries, shared), []);
  assert.deepEqual(registerDynamicDatabases(null, freshRegistries(), shared), []);
}

console.log('dynamicDatabaseRegistry tests passed');
