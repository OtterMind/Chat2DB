import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  getDirectoryJumpIndex,
  getTableKeyboardNavigationIndex,
  shouldActivateTableAction,
  shouldToggleTreeRowWithArrow,
} from './treeInteraction';

assert.equal(getTableKeyboardNavigationIndex('ArrowDown', 0, 3), 1);
assert.equal(getTableKeyboardNavigationIndex('ArrowDown', 2, 3), 2);
assert.equal(getTableKeyboardNavigationIndex('ArrowUp', 2, 3), 1);
assert.equal(getTableKeyboardNavigationIndex('ArrowUp', 0, 3), 0);
assert.equal(getTableKeyboardNavigationIndex('Home', 2, 3), 0);
assert.equal(getTableKeyboardNavigationIndex('End', 0, 3), 2);
assert.equal(getTableKeyboardNavigationIndex('Enter', 0, 3), null);
const directoryFlags = [true, false, false, true, false, true];
assert.equal(getDirectoryJumpIndex('ArrowUp', 2, directoryFlags), 0);
assert.equal(getDirectoryJumpIndex('ArrowDown', 2, directoryFlags), 3);
assert.equal(getDirectoryJumpIndex('ArrowUp', 0, directoryFlags), 0);
assert.equal(getDirectoryJumpIndex('ArrowDown', 5, directoryFlags), 5);
assert.equal(getDirectoryJumpIndex('Home', 2, directoryFlags), null);
const virtualizedDirectoryFlags = Array.from({ length: 160 }, (_, index) =>
  index === 0 || index === 120,
);
assert.equal(
  getDirectoryJumpIndex('ArrowDown', 1, virtualizedDirectoryFlags),
  120,
  'directory jumps use the complete logical row model, not one mounted DOM window',
);
assert.equal(getTableKeyboardNavigationIndex('End', 1, virtualizedDirectoryFlags.length), 159);
assert.equal(shouldActivateTableAction('Enter'), true);
assert.equal(shouldActivateTableAction(' '), true);
assert.equal(shouldActivateTableAction('Escape'), false);
assert.equal(shouldToggleTreeRowWithArrow('ArrowLeft', true), true);
assert.equal(shouldToggleTreeRowWithArrow('ArrowLeft', false), false);
assert.equal(shouldToggleTreeRowWithArrow('ArrowRight', false), true);
assert.equal(shouldToggleTreeRowWithArrow('ArrowRight', true), false);
assert.equal(shouldToggleTreeRowWithArrow('Enter', false), false);

const source = readFileSync('src/components/BaseTable/index.tsx', 'utf8');
assert.match(source, /<div\s+role="button"/);
assert.match(source, /aria-expanded=\{treeMeta\.expanded\}/);
assert.match(source, /data-base-table-keyboard-action="true"/);
assert.match(source, /data-base-table-tree-toggle="true"/);
assert.match(source, /tabIndex=\{-1\}/);
assert.match(source, /tabIndex=\{treeMode \|\| onActivateRow \? 0 : undefined\}/);
assert.match(source, /onFocus=\{treeMode \|\| onActivateRow \? handleTableRootFocus : undefined\}/);
assert.match(source, /event\.metaKey && \(event\.key === 'ArrowUp' \|\| event\.key === 'ArrowDown'\)/);
assert.match(source, /pipeline\s*\.getDataSource\(\)\s*\.flatMap/);
assert.match(source, /top: nextRow\.rowIndex \* BASE_TABLE_ROW_HEIGHT/);
assert.match(source, /window\.requestAnimationFrame/);
assert.match(source, /requestTableKeyboardFocus\(currentRowKey, true\)/);
assert.doesNotMatch(source, /keyboardActions\.indexOf/);
assert.match(source, /onActivateRow\?\.\(index, rowData\)/);
assert.match(source, /onRowClick\(sourceRowIndex, rowData\)/);
assert.match(source, /pipeline\.appendRowPropsGetter/);
assert.match(source, /event\.key === 'Escape' && onEscapeKey/);
assert.match(
  source,
  /pipeline\.use\(\s*features\.treeMode\(\{\s*\.\.\.treeMode,\s*treeMetaKey\s*\}\)\s*\)/,
);
assert.doesNotMatch(source, /'aria-expanded':/);

console.log('BaseTable tree interaction contract tests passed');
