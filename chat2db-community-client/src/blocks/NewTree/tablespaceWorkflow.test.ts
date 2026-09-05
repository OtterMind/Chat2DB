import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const menuSource = readFileSync('src/blocks/NewTree/hooks/useCreateRightClickMenu.tsx', 'utf8');
const confirmSource = readFileSync('src/blocks/NewTree/components/DeleteDatabaseSchemaConfirmContent/index.tsx', 'utf8');
const serviceSource = readFileSync('src/service/tablespace.ts', 'utf8');

assert.match(menuSource, /capability\(\{ dataSourceId: dataSourceId! \}\)/);
assert.match(menuSource, /!cap\.manageSupported/);
assert.match(menuSource, /tablespaceRenameSupported === false/);
assert.match(menuSource, /openTablespaceSqlPreviewModal/);
assert.match(menuSource, /createSql\(\{/);
assert.match(menuSource, /modifySql\(\{/);
assert.match(menuSource, /\[OperationColumn\.CreateTablespace\]: \{[\s\S]*?requiredOperations: \['CREATE'\]/);
assert.match(menuSource, /\[OperationColumn\.RenameTablespace\]: \{[\s\S]*?requiredOperations: \['ALTER'\]/);
assert.match(menuSource, /\[OperationColumn\.DeleteTablespace\]: \{[\s\S]*?requiredOperations: \['DROP'\]/);
assert.match(menuSource, /okButtonDisabled: occupied/);
assert.match(menuSource, /occupyingTables=\{occupyingTables\}/);
assert.match(confirmSource, /occupyingTables\.map/);
assert.match(confirmSource, /workspace\.tablespace\.occupiedObjects/);
assert.match(serviceSource, /\/api\/rdb\/tablespace\/modify_sql/);
assert.match(serviceSource, /manageSupported: boolean/);

const treeConfigSource = readFileSync('src/blocks/NewTree/treeConfig.tsx', 'utf8');
assert.match(treeConfigSource, /tablespaceService\.capability/);
assert.match(treeConfigSource, /tablespaceRenameSupported: capability\?\.renameSupported/);

console.log('Tablespace workflow source tests passed');
