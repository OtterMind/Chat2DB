import { DatabaseTypeCode } from '@/constants/common';
import { DatabaseCapability, IdentifierQuoteMode } from '@/constants/databaseCapabilities';
import { getDatabaseInfo, normalizeDatabaseType } from '@/constants/database';

type DatabaseTypeInput = DatabaseTypeCode | string | null | undefined;

type DatabaseCapabilityJudgment = (
  | {
      implementedBy: readonly DatabaseTypeCode[];
      implementedByDefaultExcept?: never;
    }
  | {
      implementedBy?: never;
      implementedByDefaultExcept: readonly DatabaseTypeCode[];
    }
) & {
  normalizeDatabaseType?: boolean;
};

const databaseJudgments: Record<DatabaseCapability, DatabaseCapabilityJudgment> = {
  [DatabaseCapability.ROUTINE_OPERATION]: {
    implementedBy: [DatabaseTypeCode.MYSQL],
    normalizeDatabaseType: true,
  },
  [DatabaseCapability.ACCOUNT_MANAGEMENT]: {
    implementedBy: [DatabaseTypeCode.MYSQL],
    normalizeDatabaseType: true,
  },
  [DatabaseCapability.ACTIVE_TRANSACTION_INSPECTION]: {
    implementedBy: [DatabaseTypeCode.MYSQL],
  },
  [DatabaseCapability.MANUAL_TRANSACTIONS]: {
    implementedBy: [DatabaseTypeCode.MYSQL],
  },
  [DatabaseCapability.DATABASE_DELETE]: {
    implementedBy: [DatabaseTypeCode.MYSQL, DatabaseTypeCode.POSTGRESQL],
  },
  [DatabaseCapability.SCHEMA_DELETE]: {
    implementedBy: [DatabaseTypeCode.POSTGRESQL],
  },
  [DatabaseCapability.DATABASE_CREATE]: {
    implementedByDefaultExcept: [DatabaseTypeCode.H2],
  },
  [DatabaseCapability.DATABASE_CREATE_CHARSET]: {
    implementedBy: [DatabaseTypeCode.MYSQL],
  },
  [DatabaseCapability.DATABASE_CREATE_COLLATION]: {
    implementedBy: [DatabaseTypeCode.MYSQL],
  },
  [DatabaseCapability.SCHEMA_CREATE]: {
    implementedByDefaultExcept: [DatabaseTypeCode.ORACLE, DatabaseTypeCode.OSCAR],
  },
  [DatabaseCapability.IMPORT_EXPORT]: {
    implementedByDefaultExcept: [
      DatabaseTypeCode.REDIS,
      DatabaseTypeCode.H2,
      DatabaseTypeCode.PRESTO,
      DatabaseTypeCode.MONGODB,
      DatabaseTypeCode.SNOWFLAKE,
      DatabaseTypeCode.KYLIN,
      DatabaseTypeCode.KINGBASE,
      DatabaseTypeCode.HIVE,
    ],
  },
  [DatabaseCapability.JAVA_CLASS_GENERATION]: {
    implementedBy: [
      DatabaseTypeCode.CLICKHOUSE,
      DatabaseTypeCode.DB2,
      DatabaseTypeCode.DM,
      DatabaseTypeCode.KINGBASE,
      DatabaseTypeCode.POSTGRESQL,
      DatabaseTypeCode.SQLITE,
      DatabaseTypeCode.SQLSERVER,
      DatabaseTypeCode.MYSQL,
      DatabaseTypeCode.ORACLE,
    ],
  },
  [DatabaseCapability.BACKEND_COMPLETION]: {
    implementedBy: [DatabaseTypeCode.MYSQL],
  },
  [DatabaseCapability.BACKEND_EDITOR_HINTS]: {
    implementedBy: [DatabaseTypeCode.MYSQL, DatabaseTypeCode.POSTGRESQL, DatabaseTypeCode.GAUSSDB],
  },
  [DatabaseCapability.TABLE_EDITOR_BASE_INFO]: {
    implementedBy: [DatabaseTypeCode.MYSQL],
  },
  [DatabaseCapability.TABLE_EDITOR_INDEX_METHOD]: {
    implementedBy: [DatabaseTypeCode.MYSQL],
  },
  [DatabaseCapability.TABLE_EDITOR_COLUMN_VISIBILITY]: {
    implementedBy: [DatabaseTypeCode.MYSQL],
  },
  [DatabaseCapability.TABLE_EDITOR_INDEX_VISIBILITY]: {
    implementedBy: [DatabaseTypeCode.MYSQL],
  },
  [DatabaseCapability.TABLE_EDITOR_INDEX_COLUMN]: {
    implementedByDefaultExcept: [DatabaseTypeCode.ORACLE],
  },
  [DatabaseCapability.TABLE_EDITOR_INCLUDE_COLLATION]: {
    implementedBy: [DatabaseTypeCode.SQLITE],
  },
  [DatabaseCapability.TABLE_EDITOR_EXISTING_COLUMN_EDIT]: {
    implementedByDefaultExcept: [DatabaseTypeCode.SQLITE],
  },
  [DatabaseCapability.TABLE_EDITOR_SPARSE_COLUMN]: {
    implementedBy: [DatabaseTypeCode.SQLSERVER],
  },
  [DatabaseCapability.REDIS_TREE]: {
    implementedBy: [DatabaseTypeCode.REDIS],
  },
  [DatabaseCapability.MONGODB_TREE]: {
    implementedBy: [DatabaseTypeCode.MONGODB],
  },
};

const openTableIdentifierQuoteJudgments: Partial<Record<IdentifierQuoteMode, readonly DatabaseTypeCode[]>> = {
  [IdentifierQuoteMode.DOUBLE_QUOTE]: [
    DatabaseTypeCode.ORACLE,
    DatabaseTypeCode.OSCAR,
    DatabaseTypeCode.SQLITE,
    DatabaseTypeCode.POSTGRESQL,
    DatabaseTypeCode.H2,
    DatabaseTypeCode.DB2,
    DatabaseTypeCode.KINGBASE,
    DatabaseTypeCode.DM,
  ],
  [IdentifierQuoteMode.SQUARE_BRACKET]: [DatabaseTypeCode.SQLSERVER],
  [IdentifierQuoteMode.BACKTICK]: [DatabaseTypeCode.MYSQL, DatabaseTypeCode.CLICKHOUSE, DatabaseTypeCode.MARIADB],
};

const sqlCompletionIdentifierQuoteJudgments: Partial<Record<IdentifierQuoteMode, readonly DatabaseTypeCode[]>> = {
  [IdentifierQuoteMode.DOUBLE_QUOTE]: [
    DatabaseTypeCode.POSTGRESQL,
    DatabaseTypeCode.ORACLE,
    DatabaseTypeCode.OSCAR,
    DatabaseTypeCode.DB2,
    DatabaseTypeCode.DM,
    DatabaseTypeCode.H2,
    DatabaseTypeCode.SQLITE,
    DatabaseTypeCode.OCEANBASE_ORACLE,
    DatabaseTypeCode.KINGBASE,
    DatabaseTypeCode.SNOWFLAKE,
    DatabaseTypeCode.OPENGAUSS,
    DatabaseTypeCode.SUNDB,
    DatabaseTypeCode.COCKROACHDB,
    DatabaseTypeCode.KYLIN,
    DatabaseTypeCode.XUGUDB,
    DatabaseTypeCode.PRESTO,
  ],
  [IdentifierQuoteMode.SQUARE_BRACKET]: [DatabaseTypeCode.SQLSERVER],
  [IdentifierQuoteMode.BACKTICK]: [
    DatabaseTypeCode.MYSQL,
    DatabaseTypeCode.MARIADB,
    DatabaseTypeCode.TIDB,
    DatabaseTypeCode.CLICKHOUSE,
    DatabaseTypeCode.OCEANBASE,
    DatabaseTypeCode.HIVE,
  ],
};

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

export const isDatabaseCapabilitySupported = (
  databaseType: DatabaseTypeInput,
  capability: DatabaseCapability,
): boolean => {
  const judgment = databaseJudgments[capability];
  const usesAllowList = judgment.implementedBy !== undefined;
  const databaseTypes = usesAllowList ? judgment.implementedBy : judgment.implementedByDefaultExcept;
  const matches = judgment.normalizeDatabaseType
    ? containsNormalized(databaseTypes, databaseType)
    : containsStrict(databaseTypes, databaseType);
  return usesAllowList ? matches : !matches;
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
  };
};

export const getOpenTableIdentifierQuoteMode = (databaseType?: DatabaseTypeInput): IdentifierQuoteMode => {
  return getIdentifierQuoteModeFromConfig(openTableIdentifierQuoteJudgments, databaseType);
};

export const getSqlCompletionIdentifierQuoteMode = (databaseType?: DatabaseTypeInput): IdentifierQuoteMode => {
  return getIdentifierQuoteModeFromConfig(sqlCompletionIdentifierQuoteJudgments, databaseType);
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
