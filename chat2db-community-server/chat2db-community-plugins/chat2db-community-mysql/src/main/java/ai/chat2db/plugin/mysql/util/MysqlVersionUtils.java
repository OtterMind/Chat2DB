package ai.chat2db.plugin.mysql.util;

import org.apache.commons.lang3.StringUtils;

/**
 * Parses MySQL server version strings (e.g. {@code "8.0.36"}, {@code "5.7.44"}) and answers
 * version-gated capability questions. Version logic is MySQL-specific, so it lives in the plugin
 * (not in the SPI/domain layers) per the module-boundaries contract.
 */
public final class MysqlVersionUtils {

    /**
     * General tablespace create/drop/table-placement/migration is supported from MySQL 5.7.6.
     */
    public static boolean supportsGeneralTablespace(String dbVersion) {
        int[] version = parseVersion(dbVersion);
        if (version == null) {
            return false;
        }
        int major = version[0];
        int minor = version[1];
        int patch = version[2];
        return major > 5 || (major == 5 && (minor > 7 || (minor == 7 && patch >= 6)));
    }

    /**
     * {@code ALTER TABLESPACE ... RENAME TO} is available on MySQL 8.0 and later.
     */
    public static boolean supportsTablespaceRename(String dbVersion) {
        int[] version = parseVersion(dbVersion);
        if (version == null) {
            return false;
        }
        int major = version[0];
        int minor = version[1];
        return major > 8 || (major == 8 && minor >= 0);
    }

    /**
     * Returns {@code [major, minor, patch]} or {@code null} if the version cannot be parsed.
     */
    private static int[] parseVersion(String dbVersion) {
        if (StringUtils.isBlank(dbVersion)) {
            return null;
        }
        String trimmed = dbVersion.trim();
        String[] parts = trimmed.split("[^0-9]+");
        int major = parseNonNegative(parts, 0);
        int minor = parseNonNegative(parts, 1);
        int patch = parseNonNegative(parts, 2);
        if (major < 0) {
            return null;
        }
        return new int[] {major, minor < 0 ? 0 : minor, patch < 0 ? 0 : patch};
    }

    private static int parseNonNegative(String[] parts, int index) {
        if (index >= parts.length || parts[index].isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private MysqlVersionUtils() {
    }
}
