package ai.chat2db.spi.util;

import java.util.Locale;

/**
 * Applies connection parameters Chat2DB relies on for batching, without overriding what the user
 * already asked for in the JDBC URL.
 */
public final class JdbcUrlRewriter {

    /** MySQL-family driver entry point; MariaDB uses a different prefix and is left untouched. */
    private static final String MYSQL_URL_PREFIX = "jdbc:mysql:";

    private static final String BATCH_REWRITE_PARAM = "rewriteBatchedStatements";

    private JdbcUrlRewriter() {
    }

    /**
     * Enables driver-side batch rewriting for MySQL-family connections. The parameter lets the driver
     * merge a batched set of INSERT statements into one multi-row statement, which is what turns one
     * round trip per row into one round trip per batch. An explicit user value (either true or false)
     * always wins.
     */
    public static String withBatchRewrite(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        if (!url.toLowerCase(Locale.ROOT).startsWith(MYSQL_URL_PREFIX)) {
            return url;
        }
        if (containsParameter(url, BATCH_REWRITE_PARAM)) {
            return url;
        }
        int fragmentIndex = url.indexOf('#');
        String base = fragmentIndex < 0 ? url : url.substring(0, fragmentIndex);
        String fragment = fragmentIndex < 0 ? "" : url.substring(fragmentIndex);
        String separator = base.indexOf('?') >= 0 ? "&" : "?";
        return base + separator + BATCH_REWRITE_PARAM + "=true" + fragment;
    }

    private static boolean containsParameter(String url, String parameter) {
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        String lowerParameter = parameter.toLowerCase(Locale.ROOT);
        int index = lowerUrl.indexOf(lowerParameter);
        while (index >= 0) {
            boolean startsToken = index == 0 || isParameterBoundary(url.charAt(index - 1));
            int end = index + parameter.length();
            boolean endsToken = end >= url.length() || url.charAt(end) == '=' || isParameterBoundary(url.charAt(end));
            if (startsToken && endsToken) {
                return true;
            }
            index = lowerUrl.indexOf(lowerParameter, index + 1);
        }
        return false;
    }

    private static boolean isParameterBoundary(char value) {
        return value == '?' || value == '&' || value == ';';
    }
}