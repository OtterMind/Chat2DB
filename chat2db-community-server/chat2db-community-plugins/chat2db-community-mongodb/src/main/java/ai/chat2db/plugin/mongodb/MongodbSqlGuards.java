package ai.chat2db.plugin.mongodb;

import java.util.regex.Pattern;

/**
 * Context-specific validation and JSON escaping for Mongo shell command text.
 */
public final class MongodbSqlGuards {

    private static final Pattern DATABASE_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_$-]+$");

    private MongodbSqlGuards() {
    }

    /**
     * Validates the unquoted token used by the Mongo {@code use <database>} command.
     */
    public static String requireDatabaseName(String name) {
        if (name == null || !DATABASE_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid MongoDB database name: " + name);
        }
        return name;
    }

    /**
     * Returns a property-safe collection accessor. MongoDB collection names are
     * not JavaScript identifiers, so dot-property interpolation is not valid for
     * names containing dots, hyphens, or a leading digit.
     */
    public static String collectionAccessor(String name) {
        requireNonEmptyName(name, "collection name");
        return "getCollection(" + quoteJsonString(name) + ")";
    }

    /**
     * Returns a quoted object key for a MongoDB field name.
     */
    public static String quoteFieldName(String name) {
        requireNonEmptyName(name, "field name");
        return quoteJsonString(name);
    }

    /**
     * Escape a value interpolated into a double-quoted JSON string inside a shell command
     * (surrounding quotes NOT added).
     */
    public static String escapeJsonString(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20 || c == '\u2028' || c == '\u2029' || Character.isSurrogate(c)) {
                        appendUnicodeEscape(sb, c);
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * Escapes and surrounds one JSON/JavaScript string literal.
     */
    public static String quoteJsonString(String value) {
        return value == null ? "\"null\"" : "\"" + escapeJsonString(value) + "\"";
    }

    private static void requireNonEmptyName(String name, String what) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid MongoDB " + what + ": " + name);
        }
    }

    private static void appendUnicodeEscape(StringBuilder builder, char value) {
        final char[] hex = "0123456789abcdef".toCharArray();
        builder.append("\\u")
                .append(hex[(value >>> 12) & 0xf])
                .append(hex[(value >>> 8) & 0xf])
                .append(hex[(value >>> 4) & 0xf])
                .append(hex[value & 0xf]);
    }
}
