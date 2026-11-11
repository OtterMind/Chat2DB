package ai.chat2db.community.domain.core.impl.task.imports;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

/**
 * Tracks whether an imported SQL script currently owns an open, script-level transaction.
 *
 * <p>A script transaction is opened by a plain {@code BEGIN} / {@code START TRANSACTION} statement,
 * which most drivers execute without touching the JDBC {@code autoCommit} flag. A batch executor
 * that only inspects {@code autoCommit} therefore believes it owns the transaction and commits in
 * the middle of the script, which silently discards the script's own {@code ROLLBACK}. The executor
 * asks this scope instead, so a script transaction always wins over batch self-management.
 */
final class SqlTransactionScope {

    /** What a statement does to the script-level transaction state. */
    enum Effect {
        /** Not a transaction control statement. */
        NONE,
        /** Opens the script transaction. */
        BEGIN,
        /** Ends the script transaction by committing it. */
        COMMIT,
        /** Ends the script transaction by discarding it. */
        ROLLBACK
    }

    private boolean open;

    boolean isOpen() {
        return open;
    }

    /** Applies the effect of a statement that was executed successfully. */
    void observe(String executedSql) {
        switch (effectOf(executedSql)) {
            case BEGIN -> open = true;
            case COMMIT, ROLLBACK -> open = false;
            case NONE -> {
                // Keeps the current state: only transaction control statements change it.
            }
        }
    }

    /** Forgets any open transaction, e.g. when the script fails and the attempt ends. */
    void reset() {
        open = false;
    }

    /**
     * Classifies one executed statement. Leading whitespace, trailing semicolons and SQL comments
     * are ignored so {@code "-- note\\n BEGIN;"} is still recognised; anything the script uses as a
     * transaction control word keeps its case-insensitive meaning because SQL keywords are not
     * case-sensitive.
     */
    static Effect effectOf(String sql) {
        String statement = stripLeadingNoise(sql);
        String keyword = leadingKeyword(statement);
        if (keyword == null) {
            return Effect.NONE;
        }
        String controls = statement.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("--[^\\r\\n]*", " ").toUpperCase(Locale.ROOT).trim();
        String[] words = controls.split("[^A-Z_]+");
        int next = 1;
        if (words.length > next && ("WORK".equals(words[next]) || "TRANSACTION".equals(words[next]))) {
            next++;
        }
        boolean chained = words.length > next + 1
                && "AND".equals(words[next]) && "CHAIN".equals(words[next + 1]);
        switch (keyword) {
            case "BEGIN":
                // BEGIN also introduces procedural blocks (Oracle, SQL Server). Only complete
                // transaction statements may affect the fallback used by dialects without a probe.
                return controls.matches("BEGIN(?:\\s+(?:WORK|TRANSACTION|TRAN)(?:\\s+[^;]+)?"
                        + "|\\s+(?:ISOLATION|READ|DEFERRABLE|NOT)\\s+[^;]+)?\\s*;?")
                        ? Effect.BEGIN : Effect.NONE;
            case "START":
                return words.length > 1 && "TRANSACTION".equals(words[1]) ? Effect.BEGIN : Effect.NONE;
            case "COMMIT":
            case "END":
                if ("END".equals(keyword) && !controls.matches("END(?:\\s+(?:WORK|TRANSACTION))?"
                        + "(?:\\s+AND\\s+(?:NO\\s+)?CHAIN)?\\s*;?")) return Effect.NONE;
                if (words.length > 1 && "PREPARED".equals(words[1])) return Effect.NONE;
                return chained ? Effect.BEGIN : Effect.COMMIT;
            case "ROLLBACK":
            case "ABORT":
                if (words.length > next && "TO".equals(words[next])) return Effect.NONE;
                if (words.length > 1 && "PREPARED".equals(words[1])) return Effect.NONE;
                return chained ? Effect.BEGIN : Effect.ROLLBACK;
            default:
                return Effect.NONE;
        }
    }

    /**
     * The first token of the statement with comments and trailing semicolons removed, or
     * {@code null} when no keyword can be identified.
     */
    private static String leadingKeyword(String sql) {
        String remaining = stripLeadingNoise(sql);
        if (remaining == null) {
            return null;
        }
        int end = 0;
        while (end < remaining.length() && (Character.isLetter(remaining.charAt(end)))) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        return remaining.substring(0, end).toUpperCase(Locale.ROOT);
    }

    private static String stripLeadingNoise(String sql) {
        String remaining = StringUtils.trimToEmpty(sql);
        boolean progressed = true;
        while (progressed && StringUtils.isNotEmpty(remaining)) {
            // Each round removes one leading comment; the whitespace that followed it has to go
            // before the next comment can be recognised.
            remaining = StringUtils.trimToEmpty(remaining);
            if (remaining.startsWith("--")) {
                int newline = remaining.indexOf('\n');
                remaining = newline < 0 ? "" : remaining.substring(newline + 1);
                progressed = true;
            } else if (remaining.startsWith("/*")) {
                int end = remaining.indexOf("*/");
                remaining = end < 0 ? "" : remaining.substring(end + 2);
                progressed = true;
            } else {
                progressed = false;
            }
        }
        // A leading parenthesis or a statement that starts with data never opens a transaction.
        if (StringUtils.isEmpty(remaining) || !Character.isLetter(remaining.charAt(0))) {
            return null;
        }
        return remaining;
    }
}
