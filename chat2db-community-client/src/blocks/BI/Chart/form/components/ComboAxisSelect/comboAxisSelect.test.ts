import assert from 'node:assert/strict';
import { filterComboAxisActions } from './filterComboAxisActions';

interface Action {
  key: string;
}

const allActions: Action[] = [
  { key: 'move-up' },
  { key: 'move-down' },
  { key: 'delete-axis' },
];

const actionKeys = (isFirst: boolean, isLast: boolean): string[] => {
  return filterComboAxisActions(allActions, isFirst, isLast).map((action) => action.key);
};

assert.deepEqual(actionKeys(false, false), ['move-up', 'move-down', 'delete-axis']);
assert.deepEqual(actionKeys(true, false), ['move-down', 'delete-axis']);
assert.deepEqual(actionKeys(false, true), ['move-up', 'delete-axis']);
assert.deepEqual(actionKeys(true, true), ['delete-axis']);

console.log('ComboAxisSelect action filter tests passed');
