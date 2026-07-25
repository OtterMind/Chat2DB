package ai.chat2db.plugin.sqlserver.identifier;

public final class SqlServerIdentifierUtils {

    private SqlServerIdentifierUtils() {
    }

    public static String quoteIdentifierPart(String identifier) {
        return "[" + identifier.replace("]", "]]") + "]";
    }

    public static String escapeStringLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
