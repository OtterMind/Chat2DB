import assert from 'node:assert/strict';
import { ActionType } from '@/constants/redis';
import { mapDisplayedIndexToValueListIndex } from './mapDisplayedIndex';

type Item = { action: ActionType; name: string };

const make = (action: ActionType, name: string): Item => ({ action, name });

// 1. No deleted rows — identity mapping
{
  const list = [make(ActionType.ORIGINAL, 'a'), make(ActionType.ORIGINAL, 'b'), make(ActionType.ORIGINAL, 'c')];
  assert.equal(mapDisplayedIndexToValueListIndex(list, 0), 0);
  assert.equal(mapDisplayedIndexToValueListIndex(list, 1), 1);
  assert.equal(mapDisplayedIndexToValueListIndex(list, 2), 2);
  assert.equal(mapDisplayedIndexToValueListIndex(list, 3), -1); // out of range
}

// 2. Deleted rows before the target — correct mapping
{
  // valueList: [DELETE, DELETE, A, B]
  // displayed: [A(0), B(1)]
  const list = [make(ActionType.DELETE, 'd1'), make(ActionType.DELETE, 'd2'), make(ActionType.ORIGINAL, 'A'), make(ActionType.ORIGINAL, 'B')];
  assert.equal(mapDisplayedIndexToValueListIndex(list, 0), 2, 'first visible (A) should map to index 2');
  assert.equal(mapDisplayedIndexToValueListIndex(list, 1), 3, 'second visible (B) should map to index 3');
  assert.equal(mapDisplayedIndexToValueListIndex(list, 2), -1, 'out of range');
}

// 3. Deleted rows between visible rows — correct mapping
{
  // valueList: [A, DELETE, B, DELETE, C]
  // displayed: [A(0), B(1), C(2)]
  const list = [make(ActionType.ORIGINAL, 'A'), make(ActionType.DELETE, 'd1'), make(ActionType.ORIGINAL, 'B'), make(ActionType.DELETE, 'd2'), make(ActionType.ORIGINAL, 'C')];
  assert.equal(mapDisplayedIndexToValueListIndex(list, 0), 0, 'A at index 0');
  assert.equal(mapDisplayedIndexToValueListIndex(list, 1), 2, 'B at index 2');
  assert.equal(mapDisplayedIndexToValueListIndex(list, 2), 4, 'C at index 4');
  assert.equal(mapDisplayedIndexToValueListIndex(list, 3), -1, 'out of range');
}

// 4. Mixed ADD/UPDATE/ORIGINAL rows — all non-DELETE are visible
{
  // valueList: [ADD, DELETE, UPDATE, ORIGINAL]
  // displayed: [ADD(0), UPDATE(1), ORIGINAL(2)]
  const list = [make(ActionType.ADD, 'new'), make(ActionType.DELETE, 'd'), make(ActionType.UPDATE, 'upd'), make(ActionType.ORIGINAL, 'orig')];
  assert.equal(mapDisplayedIndexToValueListIndex(list, 0), 0, 'ADD at index 0');
  assert.equal(mapDisplayedIndexToValueListIndex(list, 1), 2, 'UPDATE at index 2');
  assert.equal(mapDisplayedIndexToValueListIndex(list, 2), 3, 'ORIGINAL at index 3');
  assert.equal(mapDisplayedIndexToValueListIndex(list, 3), -1, 'out of range');
}

// 5. Out-of-range displayed index — returns -1
{
  const list = [make(ActionType.ORIGINAL, 'a'), make(ActionType.DELETE, 'd'), make(ActionType.ORIGINAL, 'b')];
  assert.equal(mapDisplayedIndexToValueListIndex(list, 2), -1, 'only 2 visible rows, index 2 is out of range');
  assert.equal(mapDisplayedIndexToValueListIndex(list, -1), -1, 'negative index');
  assert.equal(mapDisplayedIndexToValueListIndex(list, 100), -1, 'large out-of-range index');
}

// 6. All rows deleted — returns -1
{
  const list = [make(ActionType.DELETE, 'a'), make(ActionType.DELETE, 'b')];
  assert.equal(mapDisplayedIndexToValueListIndex(list, 0), -1, 'no visible rows');
}

// 7. Single row — edge case
{
  const list = [make(ActionType.ORIGINAL, 'only')];
  assert.equal(mapDisplayedIndexToValueListIndex(list, 0), 0, 'single visible row');
  assert.equal(mapDisplayedIndexToValueListIndex(list, 1), -1, 'out of range');
}

// 8. Empty list — returns -1
{
  assert.equal(mapDisplayedIndexToValueListIndex([], 0), -1, 'empty list');
}

console.log('mapDisplayedIndex tests passed');
