import assert from 'node:assert/strict';
import {
  normalizeIdentityColor,
  resolveDataSourceIdentityColor,
  withIdentityColorAlpha,
} from './dataSourceIdentity';

assert.equal(normalizeIdentityColor(' #12abEF '), '#12ABEF');
assert.equal(normalizeIdentityColor('#abc'), null);
assert.equal(normalizeIdentityColor('red'), null);
assert.equal(normalizeIdentityColor(null), null);

assert.equal(resolveDataSourceIdentityColor({ identityColor: '#112233' }), '#112233');
assert.equal(resolveDataSourceIdentityColor({ identityColor: null }), null);
assert.equal(resolveDataSourceIdentityColor({ environment: { color: '#445566' } } as any), null);
assert.equal(resolveDataSourceIdentityColor(), null);

assert.equal(withIdentityColorAlpha('#123456', 0.12), 'rgba(18, 52, 86, 0.12)');
assert.equal(withIdentityColorAlpha('#abc', 0.16), 'rgba(170, 187, 204, 0.16)');
assert.equal(withIdentityColorAlpha('rgb(1, 2, 3)', 2), 'rgba(1, 2, 3, 1)');
assert.equal(
  withIdentityColorAlpha('var(--identity-color)', -1),
  'color-mix(in srgb, var(--identity-color) 0%, transparent)',
);

console.log('Data source identity color tests passed');
