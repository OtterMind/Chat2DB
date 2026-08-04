import assert from 'node:assert/strict';
import { OrderByRule } from '../../constants';
import { compareChartValues, sortChartDataIndices } from '../sortChartData';

const dataToSort = [30, 10, 20, 10, 30];

assert.deepEqual(sortChartDataIndices(dataToSort, OrderByRule.ASC), [1, 3, 2, 0, 4]);
assert.deepEqual(sortChartDataIndices(dataToSort, OrderByRule.DESC), [0, 4, 2, 1, 3]);
assert.equal(compareChartValues(30, 30), 0);
assert.equal(compareChartValues('same', 'same'), 0);
assert.deepEqual(sortChartDataIndices([42], OrderByRule.ASC), [0]);
assert.deepEqual(sortChartDataIndices([], OrderByRule.ASC), []);

console.log('BI chart comparator tests passed');
