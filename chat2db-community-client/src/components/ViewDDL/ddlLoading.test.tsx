import assert from 'node:assert/strict';
import test from 'node:test';
import { JSDOM } from 'jsdom';

test('DDL previews load all four object types and discard replaced requests', async () => {
  const dom = new JSDOM('<!doctype html><html><body></body></html>');
  Object.assign(globalThis, { window: dom.window, document: dom.window.document, IS_REACT_ACT_ENVIRONMENT: true });
  Object.defineProperty(globalThis, 'navigator', { value: dom.window.navigator, configurable: true });
  const React = await import('react');
  const { act } = React;
  const { createRoot } = await import('react-dom/client');
  const pending: Array<{ method: string; params: any; resolve: (result: any) => void }> = [];
  const service = Object.fromEntries(
    ['exportCreateTableSql', 'getViewDetail', 'getFunctionDetail', 'getProcedureDetail'].map((method) => [
      method, (params: any) => new Promise((resolve) => pending.push({ method, params, resolve })),
    ]),
  );
  const nodeModule = (await import('node:module')) as any;
  const originalLoad = nodeModule.Module._load;
  nodeModule.Module._load = function (request: string, ...rest: unknown[]) {
    if (request.endsWith('/DdlSearch/DdlPreview')) {
      return { __esModule: true, default: ({ sql }: { sql: string }) => React.createElement('pre', null, sql) };
    }
    if (request === '@/service/sql') return { __esModule: true, default: service };
    if (request === './style') return { useStyles: () => ({ styles: {} }) };
    if (request === 'antd-style') return { createStyles: () => () => ({ styles: {} }) };
    if (request === '@/constants') return { TreeNodeType: { TABLE: 'TABLE', VIEW: 'VIEW', FUNCTION: 'FUNCTION', PROCEDURE: 'PROCEDURE' } };
    if (request === '@/store/common/components') return { openModal: () => {} };
    if (request === '@/utils/workspaceObjectTabTitle') return { buildWorkspaceObjectTabTitle: () => '' };
    return originalLoad.call(this, request, ...rest);
  };
  const mount = document.createElement('div');
  document.body.appendChild(mount);
  const root = createRoot(mount);
  try {
    const { default: ViewDDL } = await import('./index');
    const { DDLPreviewAsync } = await import('@/blocks/NewTree/functions/viewDDL');
    const show = (treeNodeType: string, names: object) => act(async () => {
      root.render(React.createElement(ViewDDL, { data: { dataSourceId: 1, databaseName: 'test', schemaName: 'DDL_TEST', treeNodeType, ...names } }));
    });
    await show('TABLE', { tableName: 'A' });
    await act(async () => pending[0].resolve('CREATE TABLE A (id INT)'));
    assert.match(mount.textContent!, /CREATE TABLE A/);
    await show('TABLE', { tableName: 'B' });
    assert.equal(mount.textContent, '', 'old content is hidden while the new object loads');
    await show('VIEW', { viewName: 'V' });
    await act(async () => pending[2].resolve({ ddl: 'SELECT id FROM A' }));
    await act(async () => pending[1].resolve('CREATE TABLE B (id INT)'));
    assert.equal(mount.textContent, 'SELECT id FROM A', 'late table response cannot replace the view');
    assert.equal(pending[2].method, 'getViewDetail');
    assert.equal(pending[2].params.tableName, 'V');
    await show('FUNCTION', { functionName: 'F' });
    await act(async () => pending[3].resolve({ functionBody: 'BEGIN\nRETURN 1;\nEND;' }));
    assert.equal(mount.textContent, 'BEGIN\nRETURN 1;\nEND;');
    assert.equal(pending[3].method, 'getFunctionDetail');
    await show('PROCEDURE', { procedureName: 'P' });
    await act(async () => pending[4].resolve({ procedureBody: 'BEGIN\n-- 中文\nEND;' }));
    assert.equal(mount.textContent, 'BEGIN\n-- 中文\nEND;');
    assert.equal(pending[4].method, 'getProcedureDetail');

    let resolveOld: (sql: string) => void = () => {};
    const oldRequest = () => new Promise<string>((resolve) => { resolveOld = resolve; });
    const newRequest = () => Promise.resolve('new modal DDL');
    await act(async () => root.render(React.createElement(DDLPreviewAsync, { getSql: oldRequest })));
    await act(async () => root.render(React.createElement(DDLPreviewAsync, { getSql: newRequest })));
    await act(async () => resolveOld('old modal DDL'));
    assert.equal(mount.textContent, 'new modal DDL', 'late modal request cannot restore old content');
  } finally {
    await act(async () => root.unmount());
    nodeModule.Module._load = originalLoad;
    dom.window.close();
  }
});
