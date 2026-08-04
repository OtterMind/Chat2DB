package ai.chat2db.community.domain.api.enums.parser;

import java.util.EnumSet;
import java.util.Locale;

public enum DatabaseTypeEnum {

    MYSQL,
    ORACLE,
    POSTGRESQL,
    SQLSERVER,
    CLICKHOUSE,
    HIVE,
    SQLITE,
    MARIADB,
    SNOWFLAKE,
    COCKROACH,
    DB2,
    DM,
    OSCAR,
    H2,
    KINGBASE,
    KYLIN,
    OCEANBASE,
    OCEANBASE_ORACLE,
    OPENGAUSS,
    PRESTO,
    SUNDB,
    TIDB,
    XUGUDB,
    REDIS,
    MONGODB,
    INFOMIX,
    ELASTICSEARCH,
    TDENGINE,
    BIGQUERY,
    REDSHIFT,
    DORIS,
    STARROCKS,
    GAUSSDB,
    GBASE8S,
    DUCKDB, DEFAULT;

    private static final EnumSet<DatabaseTypeEnum> MYSQL_PROTOCOL_FAMILY = EnumSet.of(
            MYSQL, MARIADB, TIDB, STARROCKS, DORIS, OCEANBASE);

    public static DatabaseTypeEnum from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public boolean isMysqlProtocolFamily() {
        return MYSQL_PROTOCOL_FAMILY.contains(this);
    }
}
