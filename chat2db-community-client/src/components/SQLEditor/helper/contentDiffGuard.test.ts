declare const require: (moduleName: string) => any;

const WorkspaceTabType = {
  CONSOLE: 'console',
  EVENT: 'event',
  FUNCTION: 'function',
  LocalSQLFile: 'localSQLFile',
  VIEW: 'view',
};

const moduleLoader = require('module');
const originalLoad = moduleLoader._load;
moduleLoader._load = (request: string, parent: unknown, isMain: boolean) => {
  if (request === '@/constants/workspace') {
    return { WorkspaceTabType };
  }
  if (request === '@client-runtime') {
    return { clientRuntime: { supportsContentDiff: () => true } };
  }
  return originalLoad(request, parent, isMain);
};

async function run() {
const {
  ContentDiffDenyReason,
  ContentDiffSourceKind,
  getContentDiffDecorationCount,
  getContentDiffEligibility,
  getContentDiffOpenBlockReason,
  guardContentDiffTexts,
  isContentDiffHunkBudgetExceeded,
} = await import('./contentDiffGuard');

function assertEqual(actual: any, expected: any, message: string) {
  const actualJson = JSON.stringify(actual);
  const expectedJson = JSON.stringify(expected);
  if (actualJson !== expectedJson) {
    throw new Error(`${message}: expected ${expectedJson}, got ${actualJson}`);
  }
}

function assert(condition: boolean, message: string) {
  if (!condition) {
    throw new Error(message);
  }
}

assertEqual(
  getContentDiffEligibility({
    editorType: WorkspaceTabType.VIEW,
    dbInfo: {
      dataSourceId: 1,
      databaseName: 'app',
      schemaName: 'public',
      viewName: 'v_user',
    },
  }).sourceKind,
  ContentDiffSourceKind.EditableDDL,
  'allow editable ddl view',
);

assertEqual(
  getContentDiffEligibility({
    editorType: WorkspaceTabType.CONSOLE,
    savedSqlRecord: true,
    dbInfo: {
      consoleId: 12,
    },
  }),
  {
    enabled: true,
    sourceKind: ContentDiffSourceKind.SavedSQL,
    sourceId: 'console:12',
  },
  'allow saved sql record',
);

assertEqual(
  getContentDiffEligibility({
    editorType: WorkspaceTabType.LocalSQLFile,
    dbInfo: {
      filePath: '/tmp/a.sql',
    },
  }),
  {
    enabled: true,
    sourceKind: ContentDiffSourceKind.LocalSQLFile,
    sourceId: 'file:/tmp/a.sql',
  },
  'allow local sql file',
);

assertEqual(
  getContentDiffEligibility({
    editorType: WorkspaceTabType.CONSOLE,
    savedSqlRecord: false,
    dbInfo: {
      status: 'DRAFT',
    },
  }).reason,
  ContentDiffDenyReason.UnsavedSQL,
  'deny console without a stable id',
);

assertEqual(
  getContentDiffEligibility({
    editorType: WorkspaceTabType.CONSOLE,
    savedSqlRecord: true,
    dbInfo: {
      consoleId: 'chat2db_temporary_1',
    },
  }).reason,
  ContentDiffDenyReason.UnsavedSQL,
  'deny temporary sql record',
);

assertEqual(
  getContentDiffEligibility({
    editorType: WorkspaceTabType.CONSOLE,
    savedSqlRecord: true,
    dbInfo: {
      consoleId: 12,
      status: 'DRAFT',
    },
  }),
  {
    enabled: true,
    sourceKind: ContentDiffSourceKind.SavedSQL,
    sourceId: 'console:12',
  },
  'allow draft saved sql record',
);

assertEqual(
  getContentDiffEligibility({
    editorType: WorkspaceTabType.CONSOLE,
    savedSqlRecord: false,
    dbInfo: {
      consoleId: 12,
      status: 'DRAFT',
    },
  }).reason,
  ContentDiffDenyReason.UnsavedSQL,
  'deny ordinary console even when it has a persisted id',
);

assertEqual(
  getContentDiffEligibility({
    editorType: WorkspaceTabType.FUNCTION,
    dbInfo: {
      dataSourceId: 1,
      databaseName: 'app',
    },
  }).reason,
  ContentDiffDenyReason.MissingSource,
  'deny ddl without object name',
);

assertEqual(
  getContentDiffEligibility({
    editorType: WorkspaceTabType.EVENT,
    dbInfo: {
      dataSourceId: 1,
      databaseName: 'app',
      eventName: 'daily_rollup',
    },
  }).sourceKind,
  ContentDiffSourceKind.EditableDDL,
  'allow editable ddl event',
);

assertEqual(
  guardContentDiffTexts('select 1', 'select 1').reason,
  ContentDiffDenyReason.Unchanged,
  'skip unchanged content',
);

assert(guardContentDiffTexts('select 1', 'select 2').enabled, 'allow changed content');

assertEqual(
  guardContentDiffTexts('a'.repeat(200001), 'b').reason,
  ContentDiffDenyReason.TextTooLarge,
  'deny large baseline',
);

assertEqual(
  getContentDiffOpenBlockReason('select 1', 'select 1'),
  undefined,
  'allow opening unchanged content',
);

assertEqual(
  getContentDiffOpenBlockReason('a'.repeat(200001), 'b'),
  ContentDiffDenyReason.TextTooLarge,
  'block opening oversized content',
);

assertEqual(
  getContentDiffDecorationCount([
    {
      currentRange: {
        startLineNumber: 3,
        endLineNumber: 5,
      },
    },
    {
      currentRange: {
        startLineNumber: 8,
        endLineNumber: 8,
      },
    },
  ]),
  4,
  'count current-line decorations',
);

assert(
  isContentDiffHunkBudgetExceeded({ hunkCount: 501, decorationCount: 1 }),
  'deny too many hunks',
);

assert(
  isContentDiffHunkBudgetExceeded({ hunkCount: 1, decorationCount: 1001 }),
  'deny too many decorations',
);

assert(
  !isContentDiffHunkBudgetExceeded({ hunkCount: 500, decorationCount: 1000 }),
  'allow budget boundary',
);

  console.log('Content diff guard tests passed');
}

run()
  .catch((error) => {
    console.error(error);
    process.exit(1);
  })
  .finally(() => {
    moduleLoader._load = originalLoad;
  });
