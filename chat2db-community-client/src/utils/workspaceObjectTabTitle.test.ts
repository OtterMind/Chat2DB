import { buildWorkspaceObjectTabTitle } from './workspaceObjectTabTitle';

const assertEqual = (actual: unknown, expected: unknown, message: string) => {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${String(expected)}, received ${String(actual)}`);
  }
};

assertEqual(
  buildWorkspaceObjectTabTitle({
    dataSourceName: 'SALES_MYSQL',
    databaseName: 'sales_app',
    objectName: 'sales_order',
  }),
  'sales_app.sales_order[SALES_MYSQL]',
  'MySQL objects include database and data source',
);

assertEqual(
  buildWorkspaceObjectTabTitle({
    dataSourceName: 'PostgreSQL',
    databaseName: 'sales',
    schemaName: 'reporting',
    objectName: 'monthly_summary',
  }),
  'sales.reporting.monthly_summary[PostgreSQL]',
  'schema databases include the complete qualified name',
);

assertEqual(
  buildWorkspaceObjectTabTitle({
    objectName: 'local_table',
  }),
  'local_table',
  'missing optional context does not add empty punctuation',
);

console.log('Workspace object tab title tests passed');
