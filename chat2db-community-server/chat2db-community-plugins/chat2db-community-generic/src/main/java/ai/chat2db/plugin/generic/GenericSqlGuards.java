package ai.chat2db.plugin.generic;

import ai.chat2db.plugin.generic.identifier.GenericIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

/**
 * Validation helpers for values substituted into generic adapter SQL templates
 * (generic.json sqlMap) (#1914).
 *
 * The generic adapter serves mixed dialects via DBConfig templates (e.g. DuckDB wraps
 * placeholders in single quotes, TDengine uses bare identifier positions), so treatment
 * is chosen per placeholder by inspecting the template; no single dialect quote char is
 * hard-coded. Escaping itself lives in {@link GenericIdentifierProcessor}.
 */
public final class GenericSqlGuards {

    private static final Pattern SAFE_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9_$]+$");

    private GenericSqlGuards() {
    }

    /**
     * Validate a strict identifier token for bare-identifier template positions, where the
     * generic adapter cannot know the dialect's identifier quote char.
     */
    public static String requireSafeIdentifier(String value, String what) {
        if (value == null || !SAFE_IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid generic " + what + ": " + value);
        }
        return value;
    }

    /**
     * Sanitize a value that DBConfig substitutes for {@code placeholder} in the given
     * generic.json SQL template. A placeholder wrapped in single quotes ('{database}')
     * lands in string-literal position and gets literal escaping; a bare placeholder
     * ({database}) lands in identifier position and must pass the identifier whitelist.
     */
    public static String sanitizeTemplateValue(String template, String placeholder, String value) {
        if (template == null || StringUtils.isBlank(value)) {
            return value;
        }
        if (template.contains("'" + placeholder + "'")) {
            return GenericIdentifierProcessor.INSTANCE.escapeString(value);
        }
        return requireSafeIdentifier(value, placeholder);
    }
}
