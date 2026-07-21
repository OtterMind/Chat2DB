package ai.chat2db.plugin.gaussdb.constant;

public final class GaussDBMetaDataConstants {

    public static final String SEARCH_PATH_STATEMENT_PREFIX = "SET search_path";
    public static final String TABLE_DDL_SQL = """
            SELECT pg_get_tabledef(c.oid)
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
              AND c.relname = ?
            """;

    private GaussDBMetaDataConstants() {
    }
}
