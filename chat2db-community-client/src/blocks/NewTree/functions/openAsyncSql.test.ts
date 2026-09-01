import assert from 'node:assert/strict';

declare const require: (moduleName: string) => any;

const WorkspaceTabType = { VIEW: 'view' };
const TreeNodeType = { VIEWS: 'views' };
const moduleLoader = require('module');
const originalLoad = moduleLoader._load;

moduleLoader._load = (request: string, parent: unknown, isMain: boolean) => {
  if (request === '@/constants') {
    return { WorkspaceTabType };
  }
  if (request === '@/constants/tree') {
    return { TreeNodeType };
  }
  if (request === '@/service/sql') {
    return {};
  }
  if (request === '@/utils') {
    return { randomLargeLong: () => 123456789 };
  }
  if (request === '@/utils/workspaceObjectTabTitle') {
    return { buildWorkspaceObjectTabTitle: ({ objectName }: { objectName: string }) => objectName };
  }
  return originalLoad(request, parent, isMain);
};

async function runTests() {
  const actions = await import('./openAsyncSql');
  const createView = (actions as any).createView;
  const formatQuotedQualifiedName = (actions as any).formatQuotedQualifiedName;

  assert.equal(typeof createView, 'function', 'Create View uses an explicit create-tab helper');
  assert.equal(typeof formatQuotedQualifiedName, 'function', 'Drop View formats quoted qualified names');

  const addedTabs: any[] = [];
  const viewsNode = {
    key: 'views-node',
    originalTitle: 'Views',
    treeNodeType: TreeNodeType.VIEWS,
    extraParams: {
      dataSourceId: 42,
      dataSourceName: 'Warehouse',
      databaseType: 'MYSQL',
      databaseName: 'analytics',
      schemaName: 'reporting',
    },
  };

  createView({
    treeNodeData: viewsNode,
    addWorkspaceTab: (tab: any) => addedTabs.push(tab),
    submitCallback: () => undefined,
  });

  assert.equal(addedTabs.length, 1);
  assert.equal(addedTabs[0].type, WorkspaceTabType.VIEW);
  assert.equal(addedTabs[0].title, 'Create view');
  assert.equal(addedTabs[0].uniqueData.dataSourceId, 42);
  assert.equal(addedTabs[0].uniqueData.databaseName, 'analytics');
  assert.equal(addedTabs[0].uniqueData.schemaName, 'reporting');
  assert.equal(addedTabs[0].uniqueData.tableName, undefined, 'create view must not load the VIEWS node title as tableName');
  assert.equal(addedTabs[0].uniqueData.viewName, undefined, 'create view must not load the VIEWS node title as viewName');
  assert.equal(addedTabs[0].uniqueData.loadSQL, undefined, 'create view must not load existing DDL');
  assert.match(addedTabs[0].uniqueData.ddl, /^CREATE VIEW `analytics`\.`/);
  assert.match(addedTabs[0].uniqueData.ddl, /\nAS\nSELECT /);

  assert.equal(formatQuotedQualifiedName(['analytics', 'reporting', 'monthly_summary']), '"analytics"."reporting"."monthly_summary"');
  assert.equal(formatQuotedQualifiedName(['analytics', '', 'name " with quote']), '"analytics"."name "" with quote"');
}

runTests()
  .then(() => {
    console.log('Open async SQL view action tests passed');
  })
  .finally(() => {
    moduleLoader._load = originalLoad;
  });
