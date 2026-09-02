import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const sqlServiceSource = readFileSync('src/service/sql.ts', 'utf8');
assert.match(
  sqlServiceSource,
  /const deleteTrigger = createRequest<ITriggerDeleteParams, void>\('\/api\/rdb\/trigger\/delete'/,
  'trigger deletes use the trigger-specific request contract',
);

const menuSource = readFileSync('src/blocks/NewTree/hooks/useCreateRightClickMenu.tsx', 'utf8');
const dropTriggerBlock = menuSource.match(/\[OperationColumn\.DropTrigger\]: \{[\s\S]*?\n\s{6}\},/)?.[0] ?? '';

assert.match(dropTriggerBlock, /triggerName:\s*trgName/, 'drop trigger sends triggerName to the backend API');
assert.doesNotMatch(dropTriggerBlock, /tableName:\s*trgName/, 'drop trigger must not send the trigger name as tableName');

console.log('Trigger delete contract tests passed');
