package ai.chat2db.community.domain.core.impl.task.imports.reader;

/** Source field names for files without a header row; preview and execution must agree on them. */
public final class SourceColumnName {
    private static final String PREFIX = "column_";

    private SourceColumnName() {
    }

    public static String of(int zeroBasedColumn) {
        return PREFIX + (zeroBasedColumn + 1);
    }

    /** Returns the one-based column encoded in a synthetic name, or {@code 0} for a real field name. */
    public static int columnNumber(String sourceColumn) {
        if (sourceColumn == null || !sourceColumn.startsWith(PREFIX)) {
            return 0;
        }
        try {
            return Integer.parseInt(sourceColumn.substring(PREFIX.length()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
