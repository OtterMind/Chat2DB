import {
  appendMissingOldDataList,
  buildUpdateSqlRequestParams,
  getRequestErrorMessage,
  resolveUpdateExecuteParams,
} from './updateSql';

function assertEqual(actual: any, expected: any, message: string) {
  const actualJson = JSON.stringify(actual);
  const expectedJson = JSON.stringify(expected);
  if (actualJson !== expectedJson) {
    throw new Error(`${message}: expected ${expectedJson}, got ${actualJson}`);
  }
}

const changedMultilineSql = 'select \n  * \nfrom \n  ai_chat_message;';
const oldMultilineText = 'ins\n\ns\nda\nsd';
const resultData = {
  tableName: 'ai_chat_message',
  headerList: [{ name: 'id' }, { name: 'content' }],
  executeSqlParams: {
    databaseType: 'MYSQL',
    dataSourceId: 46,
    databaseName: 'enterprise_gateway_dev',
    schemaName: 'test_schema',
    sql: 'SELECT * FROM ai_chat_message',
    single: true,
    explain: true,
    errorContinue: true,
    pageNo: 3,
    pageSize: 50,
    consoleId: 12,
    applyId: 13,
    resultSetId: 1,
  },
  dataList: [
    [{ value: 'row-1' }, { value: '42' }, { value: oldMultilineText }],
  ],
};

const operationWithOldDataList = {
  rowId: 'row-1',
  type: 'UPDATE',
  dataList: ['row-1', '42', changedMultilineSql],
  oldDataList: ['row-1', '42', oldMultilineText],
};

assertEqual(
  appendMissingOldDataList([operationWithOldDataList], resultData),
  [operationWithOldDataList],
  'existing oldDataList is preserved and not rebuilt from possibly changed grid data',
);

assertEqual(
  appendMissingOldDataList([{ rowId: 'row-1', type: 'UPDATE', dataList: ['row-1', '42', changedMultilineSql] }], resultData),
  [
    {
      rowId: 'row-1',
      type: 'UPDATE',
      dataList: ['row-1', '42', changedMultilineSql],
      oldDataList: ['row-1', '42', oldMultilineText],
    },
  ],
  'missing oldDataList is filled from the original result row for compatibility',
);

assertEqual(
  buildUpdateSqlRequestParams([operationWithOldDataList], resultData),
  {
    dataSourceId: 46,
    databaseName: 'enterprise_gateway_dev',
    schemaName: 'test_schema',
    tableName: 'ai_chat_message',
    headerList: resultData.headerList,
    operations: [operationWithOldDataList],
  },
  'update SQL generation uses connection context and table edits without query execution options',
);

assertEqual(
  getRequestErrorMessage({ errorMessage: 'SQL syntax error near where' }),
  'SQL syntax error near where',
  'request error message prefers server wrapper errorMessage',
);

assertEqual(
  getRequestErrorMessage('SQL execution failed'),
  'SQL execution failed',
  'request error message keeps string errors',
);

async function main() {
  let getUpdateDataSqlParams: any;
  const resolved = await resolveUpdateExecuteParams({
    operations: [operationWithOldDataList],
    resultData,
    getUpdateDataSql: async (params) => {
      getUpdateDataSqlParams = params;
      return `UPDATE ai_chat_message set \`content\` = '${changedMultilineSql}' where \`id\` = '42' LIMIT 1;`;
    },
  });

  assertEqual(
    getUpdateDataSqlParams.operations[0].dataList[2],
    changedMultilineSql,
    'get_update_sql receives the full multiline changed value',
  );
  assertEqual(
    resolved.sql,
    `UPDATE ai_chat_message set \`content\` = '${changedMultilineSql}' where \`id\` = '42' LIMIT 1;`,
    'resolved execute params keep the full multiline generated SQL',
  );

  assertEqual(
    resolved,
    {
      dataSourceId: 46,
      databaseName: 'enterprise_gateway_dev',
      schemaName: 'test_schema',
      sql: resolved.sql,
    },
    'table edits only inherit connection context from the original query',
  );

  const batchSql = 'UPDATE example_table SET value = 904 WHERE id = 1;\nUPDATE example_table SET value = 904 WHERE id = 2;';
  const batch = await resolveUpdateExecuteParams({
    operations: [operationWithOldDataList, { ...operationWithOldDataList, rowId: 'row-2' }],
    resultData,
    getUpdateDataSql: async () => batchSql,
  });
  assertEqual(
    batch,
    { dataSourceId: 46, databaseName: 'enterprise_gateway_dev', schemaName: 'test_schema', sql: batchSql },
    'multi-row edits preserve the generated script without inheriting single-statement mode',
  );

  const empty = await resolveUpdateExecuteParams({
    operations: [],
    resultData,
    getUpdateDataSql: async () => { throw new Error('empty edits must not request SQL generation'); },
  });
  assertEqual(empty.sql, '', 'empty edits do not execute the original query');

  let generationCalled = false;
  try {
    await resolveUpdateExecuteParams({
      operations: [operationWithOldDataList],
      resultData: { ...resultData, executeSqlParams: undefined },
      getUpdateDataSql: async () => { generationCalled = true; return batchSql; },
    });
    throw new Error('missing connection context must be rejected');
  } catch (error) {
    assertEqual(getRequestErrorMessage(error), 'dataSourceId is required', 'missing connection is rejected');
  }
  assertEqual(generationCalled, false, 'missing connection cannot generate or execute SQL');

  console.log('SQLPreviewExecute update SQL tests passed');
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
