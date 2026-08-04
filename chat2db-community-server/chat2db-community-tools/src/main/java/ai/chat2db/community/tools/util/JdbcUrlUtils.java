package ai.chat2db.community.tools.util;

import org.apache.commons.lang3.StringUtils;

public final class JdbcUrlUtils {

    private JdbcUrlUtils() {
    }

    public static String resetUrl(String url, String type, String serviceType) {
        if (StringUtils.isBlank(url)) {
            return url;
        }
        if (!"LocalFile".equalsIgnoreCase(serviceType)) {
            return url;
        }
        String userHome = System.getProperty("user.home");
        String osName = System.getProperty("os.name");
        if (osName != null && osName.toLowerCase().contains("win")) {
            userHome = userHome.replace("/", "\\");
        }
        if ("SQLite".equalsIgnoreCase(type)) {
            return expandHomeMarker(url, "jdbc:sqlite:", userHome);
        }
        if ("H2".equalsIgnoreCase(type)) {
            String expanded = expandHomeMarker(url, "jdbc:h2:file:", userHome);
            return expanded.equals(url) ? expandHomeMarker(url, "jdbc:h2:", userHome) : expanded;
        }
        return url;
    }

    private static String expandHomeMarker(String url, String prefix, String userHome) {
        if (!url.regionMatches(true, 0, prefix, 0, prefix.length()) || url.length() <= prefix.length()
            || url.charAt(prefix.length()) != '~') {
            return url;
        }
        int markerEnd = prefix.length() + 1;
        if (markerEnd < url.length() && url.charAt(markerEnd) != '/' && url.charAt(markerEnd) != '\\') {
            return url;
        }
        return url.substring(0, prefix.length()) + userHome + url.substring(markerEnd);
    }
}
