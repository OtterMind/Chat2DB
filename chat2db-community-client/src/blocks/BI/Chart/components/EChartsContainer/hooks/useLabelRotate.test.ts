import assert from 'node:assert/strict';
import { calculateLabelRotation, getChartLabelText } from './useLabelRotate';

assert.equal(getChartLabelText('hello'), 'hello');
assert.equal(getChartLabelText(42), '42');
assert.equal(getChartLabelText(0), '0');
assert.equal(getChartLabelText(-1.5), '-1.5');
assert.equal(getChartLabelText({ formattedLabel: 'fmt', rawLabel: 'raw' }), 'fmt');
assert.equal(getChartLabelText({ rawLabel: 'raw' }), 'raw');
assert.equal(getChartLabelText({}), '');
assert.equal(getChartLabelText(null), 'null');
assert.equal(getChartLabelText(undefined), 'undefined');

assert.deepEqual(calculateLabelRotation(400, 4, 50), { rotate: 0, interval: 0 });
assert.deepEqual(calculateLabelRotation(120, 4, 40), { rotate: 45, interval: 0 });
assert.deepEqual(calculateLabelRotation(80, 4, 40), { rotate: 90, interval: 0 });
assert.deepEqual(calculateLabelRotation(40, 4, 40), { rotate: 90, interval: 2 });

console.log('useLabelRotate label normalization and rotation tests passed');
