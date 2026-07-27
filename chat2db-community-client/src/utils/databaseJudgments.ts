import { DatabaseTypeCode } from '@/constants/common';
import { databaseCapabilities, IdentifierQuoteMode } from '@/constants/databaseCapabilities';
import { getDatabaseInfo, normalizeDatabaseType } from '@/constants/database';
import { EditColumnOperationType } from '@/constants/editTable';

type DatabaseTypeInput = DatabaseTypeCode | string | null | undefined;

const normalize = (databaseType?: DatabaseTypeInput): DatabaseTypeCode | undefined => {
  return normalizeDatabaseType(databaseType) as DatabaseTypeCode | undefined;
};

const containsStrict = (databaseTypes: readonly DatabaseTypeCode[], databaseType?: DatabaseTypeInput): boolean => {
  return !!databaseType && databaseTypes.includes(databaseType as DatabaseTypeCode);
};

const containsNormalized = (databaseTypes: readonly DatabaseTypeCode[], databaseType?: DatabaseTypeInput): boolean => {
  const normalizedType = normalize(databaseType);
  return !!normalizedType && databaseTypes.includes(normalizedType);
};

const getIdentifierQuoteModeFromConfig = (
  config: Partial<Record<IdentifierQuoteMode, readonly DatabaseTypeCode[]>>,
  databaseType?: DatabaseTypeInput,
): IdentifierQuoteMode => {
  if (!databaseType) {
    return IdentifierQuoteMode.NONE;
  }

  const strictType = databaseType as DatabaseTypeCode;
  if (config[IdentifierQuoteMode.DOUBLE_QUOTE]?.includes(strictType)) {
    return IdentifierQuoteMode.DOUBLE_QUOTE;
  }
  if (config[IdentifierQuoteMode.SQUARE_BRACKET]?.includes(strictType)) {
    return IdentifierQuoteMode.SQUARE_BRACKET;
  }
  if (config[IdentifierQuoteMode.BACKTICK]?.includes(strictType)) {
    return IdentifierQuoteMode.BACKTICK;
  }
  return IdentifierQuoteMode.NONE;
};

export const getDatabaseSupport = (databaseType?: DatabaseTypeInput) => {
  const databaseInfo = getDatabaseInfo(databaseType);
  return {
    supportDatabase: databaseInfo?.supportDatabase || false,
    supportSchema: databaseInfo?.supportSchema || false,
    needAiDataCollections: databaseInfo?.needAiDataCollections,
  };
};

export const canUseRoutineOperation = (databaseType?: DatabaseTypeInput): boolean => {
  return containsNormalized(databaseCapabilities.routineOperationSupported, databaseType);
};

export const canUseAccountManage = (databaseType?: DatabaseTypeInput): boolean => {
  return containsNormalized(databaseCapabilities.accountManageSupported, databaseType);
};

export const canDeleteDatabase = (databaseType?: DatabaseTypeInput): boolean => {
  return containsStrict(databaseCapabilities.databaseDeleteSupported, databaseType);
};

export const canDeleteSchema = (databaseType?: DatabaseTypeInput): boolean => {
  return containsStrict(databaseCapabilities.schemaDeleteSupported, databaseType);
};

export const canCreateDatabase = (databaseType?: DatabaseTypeInput): boolean => {
  return !containsStrict(databaseCapabilities.createDatabaseUnsupported, databaseType);
};

export const canSetCreateDatabaseCharset = (databaseType?: DatabaseTypeInput): boolean => {
  return containsStrict(databaseCapabilities.createDatabaseCharsetSupported, databaseType);
};

export const canSetCreateDatabaseCollation = (databaseType?: DatabaseTypeInput): boolean => {
  return containsStrict(databaseCapabilities.createDatabaseCollationSupported, databaseType);
};

export const canCreateSchema = (databaseType?: DatabaseTypeInput): boolean => {
  return !containsStrict(databaseCapabilities.createSchemaUnsupported, databaseType);
};

export const canRunSqlFile = (databaseType?: DatabaseTypeInput): boolean => {
  return !containsStrict(databaseCapabilities.importExportUnsupported, databaseType);
};

export const canExportSqlFile = (databaseType?: DatabaseTypeInput): boolean => {
  return !containsStrict(databaseCapabilities.importExportUnsupported, databaseType);
};

export const canExportData = (databaseType?: DatabaseTypeInput): boolean => {
  return !containsStrict(databaseCapabilities.importExportUnsupported, databaseType);
};

export const canImportData = (databaseType?: DatabaseTypeInput): boolean => {
  return !containsStrict(databaseCapabilities.importExportUnsupported, databaseType);
};

export const canGenerateJavaClass = (databaseType?: DatabaseTypeInput): boolean => {
  return containsStrict(databaseCapabilities.generateJavaClassSupported, databaseType);
};

export const canUseBackendCompletion = (databaseType?: DatabaseTypeInput): boolean => {
  return containsStrict(databaseCapabilities.backendCompletionSupported, databaseType);
};

export const canUseBackendEditorHints = (databaseType?: DatabaseTypeInput): boolean => {
  return containsStrict(databaseCapabilities.backendEditorHintsSupported, databaseType);
};

export const getOpenTableIdentifierQuoteMode = (databaseType?: DatabaseTypeInput): IdentifierQuoteMode => {
  return getIdentifierQuoteModeFromConfig(databaseCapabilities.openTableIdentifierQuote, databaseType);
};

export const getSqlCompletionIdentifierQuoteMode = (databaseType?: DatabaseTypeInput): IdentifierQuoteMode => {
  return getIdentifierQuoteModeFromConfig(databaseCapabilities.sqlCompletionIdentifierQuote, databaseType);
};

export const quoteIdentifierByMode = (name: string, quoteMode: IdentifierQuoteMode): string => {
  switch (quoteMode) {
    case IdentifierQuoteMode.DOUBLE_QUOTE:
      return `"${name}"`;
    case IdentifierQuoteMode.SQUARE_BRACKET:
      return `[${name}]`;
    case IdentifierQuoteMode.BACKTICK:
      return `\`${name}\``;
    default:
      return name;
  }
};

export const quoteOpenTableIdentifier = (name: string, databaseType?: DatabaseTypeInput): string => {
  return quoteIdentifierByMode(name, getOpenTableIdentifierQuoteMode(databaseType));
};

export const quoteSqlCompletionIdentifier = (name: string, databaseType?: DatabaseTypeInput): string => {
  return quoteIdentifierByMode(name, getSqlCompletionIdentifierQuoteMode(databaseType));
};

export const isRedisTreeDataSource = (databaseType?: DatabaseTypeInput): boolean => {
  return containsStrict(databaseCapabilities.redisTreeDataSourceTypes, databaseType);
};

export const isMongodbTreeDataSource = (databaseType?: DatabaseTypeInput): boolean => {
  return containsStrict(databaseCapabilities.mongodbTreeDataSourceTypes, databaseType);
};

export const shouldShowMysqlTableBaseInfo = (databaseType?: DatabaseTypeInput): boolean => {
  return containsStrict(databaseCapabilities.tableEditorMysqlBaseInfoSupported, databaseType);
};

export const shouldShowMysqlIndexMethod = (databaseType?: DatabaseTypeInput): boolean => {
  return containsStrict(databaseCapabilities.tableEditorMysqlIndexMethodSupported, databaseType);
};

export const shouldHideOracleIndexColumn = (databaseType?: DatabaseTypeInput): boolean => {
  return containsStrict(databaseCapabilities.tableEditorOracleIndexColumnHidden, databaseType);
};

export const shouldShowSqliteIncludeCollation = (databaseType?: DatabaseTypeInput): boolean => {
  return containsStrict(databaseCapabilities.tableEditorSqliteIncludeCollationSupported, databaseType);
};

export const isSqliteExistingColumnReadonly = (
  databaseType?: DatabaseTypeInput,
  editStatus?: EditColumnOperationType | null,
): boolean => {
  return (
    containsStrict(databaseCapabilities.tableEditorSqliteExistingColumnReadonly, databaseType) &&
    editStatus !== EditColumnOperationType.Add
  );
};

export const shouldShowSqlServerSparse = (databaseType?: DatabaseTypeInput): boolean => {
  return containsStrict(databaseCapabilities.tableEditorSqlServerSparseSupported, databaseType);
};
