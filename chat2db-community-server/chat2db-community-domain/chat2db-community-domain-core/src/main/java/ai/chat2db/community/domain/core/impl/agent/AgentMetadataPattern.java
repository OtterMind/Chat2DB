package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.tools.exception.agent.AgentDatabaseException;
import java.util.regex.Pattern;

final class AgentMetadataPattern {
    private AgentMetadataPattern() { }

    static String validate(String value, String field) {
        if (value == null) return null;
        if (value.isEmpty() || value.length() > 256) throw invalid(field, "Pattern must contain 1 to 256 characters.");
        regex(value, field);
        return value;
    }

    static String literal(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static boolean matches(String value, String pattern) {
        return pattern == null || value != null && regex(pattern, "databasePattern").matcher(value).matches();
    }

    static String jdbc(String value, String escape) {
        if (value == null) return null;
        StringBuilder jdbc = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                if (++i == value.length()) throw invalid("pattern", "A backslash must escape %, _ or another backslash.");
                char literal = value.charAt(i);
                if (escape == null || escape.isEmpty()) {
                    // A one-character wildcard is the narrowest safe JDBC candidate for literal % or _.
                    // The caller reapplies the canonical pattern to the returned metadata.
                    jdbc.append(literal == '%' || literal == '_' ? '_' : literal);
                } else jdbc.append(escape).append(literal);
            } else if (escape != null && !escape.isEmpty() && value.startsWith(escape, i)) {
                jdbc.append(escape).append(escape);
                i += escape.length() - 1;
            } else jdbc.append(c);
        }
        return jdbc.toString();
    }

    private static Pattern regex(String value, String field) {
        StringBuilder expression = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                if (++i == value.length() || "%_\\".indexOf(value.charAt(i)) < 0) {
                    throw invalid(field, "A backslash must escape %, _ or another backslash.");
                }
                expression.append(Pattern.quote(String.valueOf(value.charAt(i))));
            } else if (c == '%') expression.append(".*");
            else if (c == '_') expression.append('.');
            else expression.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(expression.toString(), Pattern.DOTALL);
    }

    private static AgentDatabaseException invalid(String field, String message) {
        return new AgentDatabaseException("INVALID_ARGUMENT", field, message, null);
    }
}
