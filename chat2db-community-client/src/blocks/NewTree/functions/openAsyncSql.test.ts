type AsyncSqlOpener = (props: { treeNodeData: any; addWorkspaceTab: (tab: any) => void }) => void;
type SqlServiceMethod = 'getViewDetail' | 'getFunctionDetail' | 'getProcedureDetail' | 'getTriggerDetail';

declare const require: {
  (moduleName: string): any;
  extensions?: Record<string, (module: { exports: unknown }) => void>;
};

const sqlService: Record<SqlServiceMethod, (...args: any[]) => Promise<any>> = {
  getFunctionDetail: async () => ({ functionBody: 'SELECT 1' }),
  getProcedureDetail: async () => ({ procedureBody: 'SELECT 1' }),
  getTriggerDetail: async () => ({ triggerBody: 'SELECT 1' }),
  getViewDetail: async () => ({ ddl: 'SELECT 1' }),
};

const WorkspaceTabType = {
  FUNCTION: 'function',
  PROCEDURE: 'procedure',
  TRIGGER: 'trigger',
  VIEW: 'view',
  ViewView: 'viewView',
};

const moduleLoader = require('module');
const originalLoad = moduleLoader._load;

moduleLoader._load = (request: string, parent: unknown, isMain: boolean) => {
  if (request === '@/constants') {
    return { WorkspaceTabType };
  }
  if (request === '@/service/sql') {
    return sqlService;
  }
  if (request === '@/utils') {
    return { randomLargeLong: () => 123456789 };
  }
  if (request === '@/utils/workspaceObjectTabTitle') {
    return { buildWorkspaceObjectTabTitle: ({ objectName }: { objectName: string }) => objectName };
  }
  return originalLoad(request, parent, isMain);
};

const timeoutMarker = Symbol('timeout');

async function assertRejectsBeforeTimeout(promise: Promise<unknown>, message: string) {
  const result = await Promise.race([
    promise.then(
      () => {
        throw new Error(`${message}: expected rejection`);
      },
      (error) => error,
    ),
    new Promise((resolve) => {
      setTimeout(() => resolve(timeoutMarker), 25);
    }),
  ]);

  if (result === timeoutMarker) {
    throw new Error(`${message}: loadSQL did not settle`);
  }
}

function treeNodeData(objectName: string) {
  return {
    originalTitle: objectName,
    extraParams: {
      dataSourceId: 12,
      databaseName: 'shop',
      databaseType: 'MYSQL',
      dataSourceName: 'local',
      schemaName: 'public',
    },
  };
}

async function assertOpenAsyncSqlRejects(
  service: Record<SqlServiceMethod, (...args: any[]) => Promise<any>>,
  opener: AsyncSqlOpener,
  serviceMethod: SqlServiceMethod,
  message: string,
) {
  const originalMethod = service[serviceMethod];
  const expectedError = new Error(`${serviceMethod} failed`);
  let capturedTab: any;

  (service as any)[serviceMethod] = () => Promise.reject(expectedError);

  try {
    opener({
      treeNodeData: treeNodeData('orders'),
      addWorkspaceTab: (tab) => {
        capturedTab = tab;
      },
    });

    if (typeof capturedTab?.uniqueData?.loadSQL !== 'function') {
      throw new Error(`${message}: loadSQL was not registered`);
    }

    await assertRejectsBeforeTimeout(capturedTab.uniqueData.loadSQL(), message);
  } finally {
    (service as any)[serviceMethod] = originalMethod;
  }
}

async function main() {
  const { editView, openFunction, openProcedure, openTrigger } = await import('./openAsyncSql');

  await assertOpenAsyncSqlRejects(sqlService, editView, 'getViewDetail', 'view detail service rejection rejects');
  await assertOpenAsyncSqlRejects(
    sqlService,
    openFunction,
    'getFunctionDetail',
    'function detail service rejection rejects',
  );
  await assertOpenAsyncSqlRejects(
    sqlService,
    openProcedure,
    'getProcedureDetail',
    'procedure detail service rejection rejects',
  );
  await assertOpenAsyncSqlRejects(sqlService, openTrigger, 'getTriggerDetail', 'trigger detail service rejection rejects');

  console.log('open async SQL rejection tests passed');
}

main()
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  })
  .finally(() => {
    moduleLoader._load = originalLoad;
  });
