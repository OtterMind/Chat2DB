package ai.chat2db.plugin.opengauss.constant;

import java.util.Set;

public final class OpenGaussMetaDataConstants {

    public static final String SEARCH_PATH_STATEMENT_PREFIX = "SET search_path";
    public static final String TABLE_DDL_SQL = """
            SELECT pg_get_tabledef(c.oid)
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
              AND c.relname = ?
            """;
    public static final Set<String> SYSTEM_DATABASES = Set.of("template0", "template1", "template2", "template3",
            "postgres");
    public static final Set<String> SYSTEM_SCHEMAS = Set.of("blockchain", "coverage", "cstore", "db4ai",
            "dbe_perf", "dbe_pldebugger", "dbe_pldeveloper", "dbe_sql_util", "information_schema", "pg_catalog",
            "pkg_service", "snapshot", "sqladvisor");

    private OpenGaussMetaDataConstants() {
    }
}
