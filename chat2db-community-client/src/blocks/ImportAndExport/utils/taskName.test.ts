import assert from 'node:assert/strict';
import { formatTaskName, type TaskNameMessageKey } from './taskName';
import zhWorkspace from '../../../i18n/zh-CN/workspace';
import enWorkspace from '../../../i18n/en-US/workspace';
import jaWorkspace from '../../../i18n/ja-JP/workspace';
import koWorkspace from '../../../i18n/ko-KR/workspace';
import esWorkspace from '../../../i18n/es-ES/workspace';

const zh: Record<TaskNameMessageKey, string> = {
  'workspace.task.name.importSql': '导入 SQL 文件',
  'workspace.task.name.importTable': '导入表数据',
  'workspace.task.name.exportTable': '导出表数据',
  'workspace.task.name.exportDatabaseStructure': '导出数据库结构',
  'workspace.task.name.exportDatabaseData': '导出数据库数据',
  'workspace.task.name.exportDatabaseStructureAndData': '导出数据库结构和数据',
  'workspace.task.name.exportDatabaseSql': '导出数据库 SQL',
  'workspace.task.name.importTarget': '导入 {1}',
  'workspace.task.name.exportAllQueryResultsCsv': '导出全部查询结果（CSV）',
  'workspace.task.name.exportAllQueryResultsXlsx': '导出全部查询结果（XLSX）',
  'workspace.task.name.exportAllQueryResultsInsertSql': '导出全部查询结果（INSERT SQL）',
  'workspace.task.name.exportCurrentPageQueryResultsCsv': '导出当前页查询结果（CSV）',
  'workspace.task.name.exportCurrentPageQueryResultsXlsx': '导出当前页查询结果（XLSX）',
  'workspace.task.name.exportCurrentPageQueryResultsInsertSql': '导出当前页查询结果（INSERT SQL）',
};

const translate = (key: TaskNameMessageKey, ...args: Array<string | number>) =>
  args.reduce(
    (message, value) => message.replace('{1}', String(value)),
    zh[key],
  );

assert.equal(formatTaskName('Import SQL file', translate), '导入 SQL 文件');
assert.equal(formatTaskName('Import table data - app.orders', translate), '导入表数据 - app.orders');
assert.equal(formatTaskName('Export table data - app.orders', translate), '导出表数据 - app.orders');
assert.equal(formatTaskName('Import orders', translate), '导入 orders');
assert.equal(formatTaskName('用户自定义任务', translate), '用户自定义任务');
for (const [name, key] of [
  ['Export database structure', 'workspace.task.name.exportDatabaseStructure'],
  ['Export database data', 'workspace.task.name.exportDatabaseData'],
  ['Export database structure and data', 'workspace.task.name.exportDatabaseStructureAndData'],
  ['Export database SQL', 'workspace.task.name.exportDatabaseSql'],
] as const) {
  assert.equal(formatTaskName(name, translate), zh[key]);
  assert.equal(formatTaskName(`${name} - app.orders`, translate), `${zh[key]} - app.orders`);
  assert.equal(formatTaskName(`${name} backup`, translate), `${name} backup`);
  assert.equal(formatTaskName(`${name} - app.orders`, (message) => zhWorkspace[message]),
    `${zh[key]} - app.orders`);
  assert.equal(formatTaskName(`${name} - app.orders`, (message) => enWorkspace[message]),
    `${name} - app.orders`);
  for (const locale of [jaWorkspace, koWorkspace, esWorkspace]) {
    assert.ok(locale[key]);
    assert.notEqual(locale[key], key);
    assert.equal(formatTaskName(`${name} - app.orders`, (message) => locale[message]),
      `${locale[key]} - app.orders`);
  }
}
assert.equal(formatTaskName('Export all query results as CSV - `app`.`orders`', translate),
  '导出全部查询结果（CSV） - `app`.`orders`');
assert.equal(formatTaskName('Export all query results as XLSX - `app`.`orders`', translate),
  '导出全部查询结果（XLSX） - `app`.`orders`');
assert.equal(formatTaskName('Export all query results as INSERT SQL - `app`.`orders`', translate),
  '导出全部查询结果（INSERT SQL） - `app`.`orders`');
assert.equal(formatTaskName('Export current page results as CSV - `app`.`orders`', translate),
  '导出当前页查询结果（CSV） - `app`.`orders`');
assert.equal(formatTaskName('Export current page results as XLSX - `app`.`orders`', translate),
  '导出当前页查询结果（XLSX） - `app`.`orders`');
assert.equal(formatTaskName('Export current page results as INSERT SQL - `app`.`orders`', translate),
  '导出当前页查询结果（INSERT SQL） - `app`.`orders`');
