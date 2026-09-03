package ai.chat2db.plugin.mysql;

import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MysqlVersionSupport {

    private static final Pattern VERSION_PREFIX = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?.*");

    private MysqlVersionSupport() {
    }

    public static boolean supportsInvisibleColumns(String dbVersion) {
        MysqlVersion version = parseMysqlVersion(dbVersion);
        if (version == null) {
            return false;
        }
        return version.major() > 8
                || version.major() == 8 && (version.minor() > 0
                || version.minor() == 0 && version.patch() >= 23);
    }

    public static boolean currentVersionDisallowsInvisibleColumns() {
        return !supportsInvisibleColumns(getCurrentDbVersion());
    }

    public static boolean supportsInvisibleIndexes(String dbVersion) {
        MysqlVersion version = parseMysqlVersion(dbVersion);
        return version != null && version.major() >= 8;
    }

    public static boolean supportsPersistedVariables(String dbVersion) {
        MysqlVersion version = parseMysqlVersion(dbVersion);
        return version != null && version.major() >= 8;
    }

    public static boolean currentVersionDisallowsInvisibleIndexes() {
        String dbVersion = getCurrentDbVersion();
        return !supportsInvisibleIndexes(dbVersion);
    }

    public static String getCurrentDbVersion() {
        if (Chat2DBContext.getConnectInfo() == null) {
            return null;
        }
        try {
            return Chat2DBContext.getDbVersion();
        } catch (Exception e) {
            return null;
        }
    }

    private static MysqlVersion parseMysqlVersion(String dbVersion) {
        if (StringUtils.isBlank(dbVersion)) {
            return null;
        }
        String normalized = dbVersion.trim();
        if (normalized.toLowerCase(Locale.ROOT).contains("mariadb")) {
            return null;
        }
        Matcher matcher = VERSION_PREFIX.matcher(normalized);
        if (!matcher.matches()) {
            return null;
        }
        int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        return new MysqlVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), patch);
    }

    private record MysqlVersion(int major, int minor, int patch) {
    }
}
