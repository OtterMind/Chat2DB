import { IDatabase } from '@/typings';
import { DatabaseTypeCode } from './common';

export enum ConnectionEnvType {
  DAILY = 'DAILY',
  PRODUCT = 'PRODUCT',
}

export enum ImportConnectionType {
  NAVICAT = 'navicat',
  DATAGRIP = 'datagrip',
  DBEAVER = 'dbeaver',
  CHAT2DB = 'chat2db',
  CHAT2DB_COMMUNITY = 'chat2db_community',
}

export const databaseMap: {
  [keys: string]: IDatabase;
} = {
  [DatabaseTypeCode.MYSQL]: {
    name: 'MySQL',
    code: DatabaseTypeCode.MYSQL,
    icon: 'icon-colourful-MySQL',
    iconExistDark: true,
    supportDatabase: true,
    supportSchema: false,
  },
  [DatabaseTypeCode.ORACLE]: {
    name: 'Oracle',
    code: DatabaseTypeCode.ORACLE,
    icon: 'icon-colourful-Oracle',
    supportDatabase: false,
    supportSchema: true,
  },
  [DatabaseTypeCode.POSTGRESQL]: {
    name: 'PostgreSql',
    code: DatabaseTypeCode.POSTGRESQL,
    icon: 'icon-colourful-PostgreSQL',
    iconExistDark: true,
    supportDatabase: true,
    supportSchema: true,
  },
  [DatabaseTypeCode.SQLSERVER]: {
    name: 'SQLServer',
    code: DatabaseTypeCode.SQLSERVER,
    icon: 'icon-colourful-SQLServer',
    supportDatabase: true,
    supportSchema: true,
  },
  [DatabaseTypeCode.MARIADB]: {
    name: 'Mariadb',
    code: DatabaseTypeCode.MARIADB,
    icon: 'icon-colourful-MariaDB',
    iconExistDark: true,
    supportDatabase: true,
    supportSchema: false,
  },
  [DatabaseTypeCode.CLICKHOUSE]: {
    name: 'ClickHouse',
    code: DatabaseTypeCode.CLICKHOUSE,
    icon: 'icon-colourful-Clickhouse',
    supportDatabase: false,
    supportSchema: true,
  },
  [DatabaseTypeCode.DM]: {
    name: 'DM',
    code: DatabaseTypeCode.DM,
    icon: 'icon-colourful-DAMENGDM8',
    supportDatabase: false,
    supportSchema: true,
  },
  [DatabaseTypeCode.OSCAR]: {
    name: 'OSCAR',
    code: DatabaseTypeCode.OSCAR,
    icon: 'icon-colourful-OSCAR',
    supportDatabase: false,
    supportSchema: true,
  },
  [DatabaseTypeCode.PRESTO]: {
    name: 'Presto',
    code: DatabaseTypeCode.PRESTO,
    icon: 'icon-colourful-PrestoDB',
    supportDatabase: true,
    supportSchema: true,
  },
  [DatabaseTypeCode.DB2]: {
    name: 'DB2',
    code: DatabaseTypeCode.DB2,
    icon: 'icon-colourful-DB2',
    supportDatabase: false,
    supportSchema: true,
  },
  [DatabaseTypeCode.OCEANBASE]: {
    name: 'OceanBase',
    code: DatabaseTypeCode.OCEANBASE,
    icon: 'icon-colourful-OceanBase',
    supportDatabase: true,
    supportSchema: false,
  },
  [DatabaseTypeCode.OCEANBASE_ORACLE]: {
    name: 'OceanBase Oracle',
    code: DatabaseTypeCode.OCEANBASE_ORACLE,
    icon: 'icon-colourful-OceanBase',
    supportDatabase: false,
    supportSchema: true,
  },
  [DatabaseTypeCode.SQLITE]: {
    name: 'SQLite',
    code: DatabaseTypeCode.SQLITE,
    icon: 'icon-colourful-SQLite',
    iconExistDark: true,
    supportDatabase: true,
    supportSchema: false,
  },
  [DatabaseTypeCode.H2]: {
    name: 'H2',
    code: DatabaseTypeCode.H2,
    icon: 'icon-colourful-H2',
    supportDatabase: true,
    supportSchema: true,
  },
  [DatabaseTypeCode.HIVE]: {
    name: 'Hive',
    code: DatabaseTypeCode.HIVE,
    icon: 'icon-colourful-HIVE',
    supportDatabase: true,
    supportSchema: false,
  },
  [DatabaseTypeCode.KINGBASE]: {
    name: 'Kingbase',
    code: DatabaseTypeCode.KINGBASE,
    icon: 'icon-colourful-Kingbase',
    supportDatabase: true,
    supportSchema: true,
  },
  [DatabaseTypeCode.MONGODB]: {
    name: 'MongoDB',
    code: DatabaseTypeCode.MONGODB,
    icon: 'icon-colourful-MongoDB',
    supportDatabase: false,
    supportSchema: true,
  },
  [DatabaseTypeCode.REDIS]: {
    name: 'Redis',
    code: DatabaseTypeCode.REDIS,
    icon: 'icon-colourful-Redis',
    supportDatabase: true,
    supportSchema: false,
  },
  [DatabaseTypeCode.SNOWFLAKE]: {
    name: 'Snowflake',
    code: DatabaseTypeCode.SNOWFLAKE,
    icon: 'icon-colourful-Snowflake',
    supportDatabase: true,
    supportSchema: true,
  },
  [DatabaseTypeCode.OPENGAUSS]: {
    name: 'openGauss',
    code: DatabaseTypeCode.OPENGAUSS,
    icon: 'icon-colourful-openGauss',
    supportDatabase: true,
    supportSchema: true,
  },
  [DatabaseTypeCode.SUNDB]: {
    name: 'SUNDB',
    code: DatabaseTypeCode.SUNDB,
    icon: 'icon-colourful-SUNDB',
    iconExistDark: true,
    supportDatabase: false,
    supportSchema: true,
  },
  [DatabaseTypeCode.TIDB]: {
    name: 'TiDB',
    code: DatabaseTypeCode.TIDB,
    icon: 'icon-colourful-TiDB',
    supportDatabase: true,
    supportSchema: false,
  },
  [DatabaseTypeCode.COCKROACHDB]: {
    name: 'CockroachDB',
    code: DatabaseTypeCode.COCKROACHDB,
    icon: 'icon-colourful-cockroachdb',
    iconExistDark: true,
    supportDatabase: true,
    supportSchema: true,
  },
  [DatabaseTypeCode.KYLIN]: {
    name: 'Apache Kylin',
    code: DatabaseTypeCode.KYLIN,
    icon: 'icon-colourful-KYLIN',
    supportDatabase: true,
    supportSchema: true,
  },
  [DatabaseTypeCode.XUGUDB]: {
    name: 'xuguDB',
    code: DatabaseTypeCode.XUGUDB,
    icon: 'icon-colourful-xuguDB',
    supportDatabase: true,
    supportSchema: true,
  },
  [DatabaseTypeCode.DUCKDB]: {
    name: 'DuckDB',
    code: DatabaseTypeCode.DUCKDB,
    icon: 'icon-colourful-duckDB',
    supportDatabase: true,
    supportSchema: true,
  },
  [DatabaseTypeCode.ELASTICSEARCH]: {
    name: 'Elasticsearch',
    code: DatabaseTypeCode.ELASTICSEARCH,
    icon: 'icon-colourful-elasticsearch',
    iconExistDark: true,
    supportDatabase: true,
    supportSchema: false,
  },
  [DatabaseTypeCode.TDENGINE]: {
    name: 'TDengine',
    code: DatabaseTypeCode.TDENGINE,
    icon: 'icon-colourful-tdengine',
    iconExistDark: true,
    supportDatabase: true,
    supportSchema: false,
  },
  [DatabaseTypeCode.BIGQUERY]: {
    name: 'BigQuery',
    code: DatabaseTypeCode.BIGQUERY,
    icon: 'icon-colourful-bigquery',
    supportDatabase: true,
    supportSchema: true,
  },
  [DatabaseTypeCode.REDSHIFT]: {
    name: 'Redshift',
    code: DatabaseTypeCode.REDSHIFT,
    icon: 'icon-colourful-redshift',
    supportDatabase: true,
    supportSchema: true,
  },
  [DatabaseTypeCode.INFORMIX]: {
    name: 'Informix',
    code: DatabaseTypeCode.INFORMIX,
    icon: 'icon-colourful-informix',
    iconExistDark: true,
    supportDatabase: true,
    supportSchema: true,
  },
  [DatabaseTypeCode.DORIS]: {
    name: 'Doris',
    code: DatabaseTypeCode.DORIS,
    icon: 'icon-colourful-doris',
    supportDatabase: true,
    supportSchema: false,
  },
  [DatabaseTypeCode.STARROCKS]: {
    name: 'StarRocks',
    code: DatabaseTypeCode.STARROCKS,
    icon: 'icon-colourful-starrocks',
    supportDatabase: true,
    supportSchema: false,
  },
  [DatabaseTypeCode.GAUSSDB]: {
    name: 'GaussDB',
    code: DatabaseTypeCode.GAUSSDB,
    icon: 'icon-colourful-gaussdb',
    supportDatabase: true,
    supportSchema: true,
  },
  [DatabaseTypeCode.GBASE8S]: {
    name: 'GBase8s',
    code: DatabaseTypeCode.GBASE8S,
    icon: 'icon-colourful-gbase8s',
    supportDatabase: true,
    supportSchema: false,
  },
};

export const databaseTypeList = Object.keys(databaseMap).map((keys) => {
  return databaseMap[keys];
});

const databaseTypeAliasMap: Record<string, DatabaseTypeCode> = {
  OSCAR: DatabaseTypeCode.OSCAR,
  OSCARDB: DatabaseTypeCode.OSCAR,
  SHENTONG: DatabaseTypeCode.OSCAR,
  SHENTONGDB: DatabaseTypeCode.OSCAR,
};

export const normalizeDatabaseType = (databaseType?: string | null) => {
  if (!databaseType) {
    return undefined;
  }

  const rawType = String(databaseType).trim();
  const upperType = rawType.toUpperCase();
  const compactType = upperType.replace(/[\s_-]/g, '');

  if (databaseMap[rawType]) {
    return rawType;
  }

  if (databaseMap[upperType]) {
    return upperType;
  }

  return databaseTypeAliasMap[upperType] || databaseTypeAliasMap[compactType];
};

export const getDatabaseInfo = (databaseType?: string | null) => {
  const normalizedType = normalizeDatabaseType(databaseType);
  return normalizedType ? databaseMap[normalizedType] : undefined;
};
