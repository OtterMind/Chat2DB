package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.tools.exception.BusinessException;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

final class SqlObjectIdentifierGuard {

    private static final Pattern IDENTIFIER =
            Pattern.compile("(?!.*--)[\\p{L}_$][\\p{L}\\p{N}_$-]*");

    private SqlObjectIdentifierGuard() {
    }

    static String required(String value) {
        String identifier = StringUtils.trimToNull(value);
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new BusinessException("common.paramError");
        }
        return identifier;
    }

    static String optional(String value) {
        String identifier = StringUtils.trimToNull(value);
        if (identifier == null) {
            return null;
        }
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw new BusinessException("common.paramError");
        }
        return identifier;
    }
}
