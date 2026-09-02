import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const sqlServiceSource = readFileSync('src/service/sql.ts', 'utf8');
assert.match(
  sqlServiceSource,
  /const deleteFunction = createRequest<IFunctionDeleteParams, void>\('\/api\/rdb\/function\/delete'/,
  'function deletes use the function-specific request contract',
);
assert.match(
  sqlServiceSource,
  /const deleteProcedure = createRequest<IProcedureDeleteParams, void>\('\/api\/rdb\/procedure\/delete'/,
  'procedure deletes use the procedure-specific request contract',
);

const menuSource = readFileSync('src/blocks/NewTree/hooks/useCreateRightClickMenu.tsx', 'utf8');
const dropFunctionBlock = menuSource.match(/\[OperationColumn\.DropFunction\]: \{[\s\S]*?\n\s{6}\},/)?.[0] ?? '';
const dropProcedureBlock = menuSource.match(/\[OperationColumn\.DropProcedure\]: \{[\s\S]*?\n\s{6}\},/)?.[0] ?? '';

assert.match(dropFunctionBlock, /functionName:\s*fnName/, 'drop function sends functionName to the backend API');
assert.doesNotMatch(dropFunctionBlock, /tableName:\s*fnName/, 'drop function must not send the function name as tableName');

assert.match(dropProcedureBlock, /procedureName:\s*procName/, 'drop procedure sends procedureName to the backend API');
assert.doesNotMatch(
  dropProcedureBlock,
  /tableName:\s*procName/,
  'drop procedure must not send the procedure name as tableName',
);

console.log('Routine delete contract tests passed');
