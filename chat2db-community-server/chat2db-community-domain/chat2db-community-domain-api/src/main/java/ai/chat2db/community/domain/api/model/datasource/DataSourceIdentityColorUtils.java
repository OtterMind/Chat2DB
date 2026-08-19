package ai.chat2db.community.domain.api.model.datasource;

import ai.chat2db.community.tools.exception.BusinessException;

import java.util.Locale;
import java.util.regex.Pattern;

public final class DataSourceIdentityColorUtils {

    private static final Pattern IDENTITY_COLOR_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private DataSourceIdentityColorUtils() {
    }

    public static String normalize(String identityColor) {
        if (identityColor == null) {
            return null;
        }
        String normalized = identityColor.trim();
        if (!IDENTITY_COLOR_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException("datasource.identityColor.invalid");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }
}
