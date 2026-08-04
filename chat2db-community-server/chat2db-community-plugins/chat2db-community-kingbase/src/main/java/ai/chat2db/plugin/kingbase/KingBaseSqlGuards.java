package ai.chat2db.plugin.kingbase;

import ai.chat2db.plugin.postgresql.PostgreSqlGuards;

/**
 * Validation helpers for non-escapable SQL expression positions in KingBase DDL
 * generation. KingBase shares PostgreSQL's expression grammar for these paths,
 * so the mature PostgreSQL scanners remain the source of truth. Escaping lives in
 * {@link ai.chat2db.plugin.kingbase.identifier.KingBaseSQLIdentifierProcessor}.
 */
public final class KingBaseSqlGuards {

    private KingBaseSqlGuards() {
    }

    public static String requireDefaultExpression(String value) {
        return PostgreSqlGuards.requireDefaultExpression(value);
    }

    public static String requireColumnTypeExpression(String value) {
        return PostgreSqlGuards.requireColumnTypeExpression(value);
    }

    public static boolean isTemporalExpression(String value) {
        return PostgreSqlGuards.isTemporalExpression(value);
    }

    public static boolean isFunctionOrCastExpression(String value) {
        return PostgreSqlGuards.isFunctionOrCastExpression(value);
    }

    public static String requirePrivilege(String privilege) {
        return PostgreSqlGuards.requirePrivilege(privilege);
    }
}
