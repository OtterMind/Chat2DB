import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import './redisRowIdentity.test';
import './redisEditSession.test';
import './redisKeyTree.test';
import './redisExpansion.test';
import './redisViewMode.test';
import './redisDetail.test';

const redisAllDataSource = readFileSync('src/blocks/RedisAllData/index.tsx', 'utf8');
assert.match(redisAllDataSource, /REDIS_TABLE_COLUMN_SIZES = \[420, 80, 100\]/);
assert.match(redisAllDataSource, /clickArea: 'cell'/);
assert.match(redisAllDataSource, /iconIndent: 0/);
assert.match(redisAllDataSource, /iconGap: 2/);
assert.match(redisAllDataSource, /onActivateRow=\{handleActivateRedisRow\}/);
assert.match(redisAllDataSource, /onRowClick=\{handleActivateRedisRow\}/);
assert.match(redisAllDataSource, /onEscapeKey=\{handleCloseEditPane\}/);
assert.match(redisAllDataSource, /\.catch\(\(\) => \{/);
assert.match(redisAllDataSource, /detailLoadStatus === 'failed'/);

console.log('Redis explorer integration contracts passed');
