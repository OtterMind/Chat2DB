package ai.chat2db.community.domain.api.model.task;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Set;

/** Supported target scopes for data-file imports. */
public final class ImportScope {

    public static final String TABLE = "TABLE";

    public static final String SCHEMA = "SCHEMA";

    public static final String DATABASE = "DATABASE";

    private static final Set<String> SUPPORTED = Set.of(TABLE, SCHEMA, DATABASE);

    private ImportScope() {
    }

    public static String normalize(String scope) {
        String normalized = StringUtils.defaultIfBlank(scope, TABLE).trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported import scope: " + scope);
        }
        return normalized;
    }
}
