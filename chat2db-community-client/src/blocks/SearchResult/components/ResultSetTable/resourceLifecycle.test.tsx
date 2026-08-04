import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { useEffect, useState } from 'react';
import TestRenderer, { act } from 'react-test-renderer';
import { getActiveTableInstance } from '@/blocks/CanvasTable/lifecycle';

interface FakeTableInstance {
  id: string;
}

const lifecycleEvents: string[] = [];
let editListenerAttached = false;
const tableInstance: FakeTableInstance = { id: 'table-1' };

const ReleaseInactiveTable = ({
  active,
  instance,
  onRelease,
}: {
  active: boolean;
  instance: FakeTableInstance | null;
  onRelease: () => void;
}) => {
  useEffect(() => {
    if (active || !instance) return;
    lifecycleEvents.push('complete-edit');
    if (editListenerAttached) {
      lifecycleEvents.push('edit-tracked');
    }
    lifecycleEvents.push('release');
    onRelease();
  }, [active, instance, onRelease]);
  return null;
};

const ResultTableOwner = ({ active }: { active: boolean }) => {
  const [instance, setInstance] = useState<FakeTableInstance | null>(tableInstance);
  const activeInstance = getActiveTableInstance(active, instance);

  useEffect(() => {
    if (!activeInstance) return;
    lifecycleEvents.push('observer-attach');
    return () => lifecycleEvents.push('observer-cleanup');
  }, [activeInstance]);

  useEffect(() => {
    if (!instance) return;
    editListenerAttached = true;
    lifecycleEvents.push('edit-listener-attach');
    return () => {
      editListenerAttached = false;
      lifecycleEvents.push('edit-listener-cleanup');
    };
  }, [instance]);

  return <ReleaseInactiveTable active={active} instance={instance} onRelease={() => setInstance(null)} />;
};

let renderer: TestRenderer.ReactTestRenderer;
act(() => {
  renderer = TestRenderer.create(<ResultTableOwner active />);
});
act(() => {
  renderer.update(<ResultTableOwner active={false} />);
});

const eventIndex = (event: string) => lifecycleEvents.indexOf(event);
assert.ok(eventIndex('observer-cleanup') < eventIndex('complete-edit'), 'discardable observers detach before release');
assert.ok(eventIndex('complete-edit') < eventIndex('edit-tracked'), 'the active editor completes before its value is tracked');
assert.ok(eventIndex('edit-tracked') < eventIndex('release'), 'the final edit is tracked before release');
assert.ok(eventIndex('release') < eventIndex('edit-listener-cleanup'), 'edit tracking remains attached through release');

const resultSetTableSource = readFileSync('src/blocks/SearchResult/components/ResultSetTable/index.tsx', 'utf8');
assert.match(resultSetTableSource, /useOperationRecord\(\{\s*\/\/[^\n]+\n\s*tableInstance,/);
assert.match(resultSetTableSource, /useHeaderTooltip\(\{ tableInstance: activeTableInstance \}\)/);
assert.match(resultSetTableSource, /onChangeCellValue\(tableInstance, operationRecordUtils\.handleCellValueChange\)/);

act(() => renderer!.unmount());

console.log('ResultSet table resource lifecycle tests passed');
