package ai.chat2db.plugin.mysql.util;

import org.apache.commons.lang3.StringUtils;

/**
 * Parses MySQL server version strings (e.g. {@code "8.0.36"}, {@code "5.7.44"}) and answers
 * version-gated capability questions. Version logic is MySQL-specific, so it lives in the plugin
 * (not in the SPI/domain layers) per the module-boundaries contract.
 */
public final class MysqlVersionUtils {

    /**
     * {@code ALTER TABLESPACE ... RENAME TO} is available on MySQL 8.0 and later. General
     * tablespaces themselves (create/drop/table-placement/migration) are supported from 5.7.6.
     */
    public static boolean supportsTablespaceRename(String dbVersion) {
        int[] version = parseMajorMinor(dbVersion);
        if (version == null) {
            return false;
        }
        int major = version[0];
        int minor = version[1];
        return major > 8 || (major == 8 && minor >= 0);
    }

    /**
     * Returns {@code [major, minor]} or {@code null} if the version cannot be parsed.
     */
    private static int[] parseMajorMinor(String dbVersion) {
        if (StringUtils.isBlank(dbVersion)) {
            return null;
        }
        // Strip any suffix after the version (e.g. "-log", "-MariaDB").
        String trimmed = dbVersion.trim();
        String numeric = trimmed.split("[^0-9]", 2)[0];
        if (numeric.isEmpty()) {
            return null;
        }
        // Re-split on any non-digit to extract the first two numeric segments.
        String[] parts = trimmed.split("[^0-9]+");
        int major = parseNonNegative(parts, 0);
        int minor = parseNonNegative(parts, 1);
        if (major < 0) {
            return null;
        }
        return new int[] {major, minor < 0 ? 0 : minor};
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
