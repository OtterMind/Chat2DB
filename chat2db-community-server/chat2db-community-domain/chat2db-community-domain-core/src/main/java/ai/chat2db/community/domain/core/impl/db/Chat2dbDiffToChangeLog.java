package ai.chat2db.community.domain.core.impl.db;

import liquibase.change.AddColumnConfig;
import liquibase.change.Change;
import liquibase.change.core.CreateIndexChange;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.core.MySQLDatabase;
import liquibase.diff.DiffResult;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.changelog.DiffToChangeLog;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repairs MySQL computed index metadata before Liquibase serializes the changelog.
 */
final class Chat2dbDiffToChangeLog extends DiffToChangeLog {

    private static final Pattern MYSQL_ESCAPED_CHARSET_LITERAL_START =
            Pattern.compile("(?i)(_[a-z0-9]+)\\\\'");

    private final Database targetDatabase;

    Chat2dbDiffToChangeLog(DiffResult diffResult, DiffOutputControl diffOutputControl) {
        super(diffResult, diffOutputControl);
        this.targetDatabase = diffResult.getComparisonSnapshot().getDatabase();
    }

    @Override
    public List<ChangeSet> generateChangeSets() {
        List<ChangeSet> changeSets = super.generateChangeSets();
        repairMySqlComputedIndexExpressions(changeSets, targetDatabase);
        return changeSets;
    }

    static void repairMySqlComputedIndexExpressions(List<ChangeSet> changeSets, Database targetDatabase) {
        if (!(targetDatabase instanceof MySQLDatabase)) {
            return;
        }
        for (ChangeSet changeSet : changeSets) {
            for (Change change : changeSet.getChanges()) {
                if (change instanceof CreateIndexChange createIndexChange) {
                    normalizeComputedColumns(createIndexChange);
                }
            }
        }
    }

    private static void normalizeComputedColumns(CreateIndexChange createIndexChange) {
        for (AddColumnConfig column : createIndexChange.getColumns()) {
            if (Boolean.TRUE.equals(column.getComputed()) && column.getName() != null) {
                String expression = normalizeCharsetLiterals(column.getName());
                String trimmedExpression = expression.trim();
                // Liquibase uses the name verbatim, while MySQL requires an extra pair around expression key parts.
                if (!trimmedExpression.isEmpty()
                        && !(trimmedExpression.startsWith("(") && trimmedExpression.endsWith(")"))) {
                    expression = "(" + trimmedExpression + ")";
                }
                column.setName(expression);
            }
        }
    }

    private static String normalizeCharsetLiterals(String expression) {
        Matcher matcher = MYSQL_ESCAPED_CHARSET_LITERAL_START.matcher(expression);
        StringBuilder normalized = new StringBuilder(expression.length());
        int cursor = 0;
        while (matcher.find(cursor)) {
            normalized.append(expression, cursor, matcher.start());

            StringBuilder body = new StringBuilder();
            int metadataCursor = matcher.end();
            boolean closed = false;
            while (metadataCursor < expression.length()) {
                char decoded = expression.charAt(metadataCursor++);
                if (decoded == '\\' && metadataCursor < expression.length()) {
                    decoded = expression.charAt(metadataCursor++);
                }
                if (decoded == '\'' && !hasOddTrailingBackslashes(body)) {
                    normalized.append(matcher.group(1)).append('\'').append(body).append('\'');
                    cursor = metadataCursor;
                    closed = true;
                    break;
                }
                body.append(decoded);
            }

            if (!closed) {
                normalized.append(expression, matcher.start(), expression.length());
                return normalized.toString();
            }
        }
        normalized.append(expression, cursor, expression.length());
        return normalized.toString();
    }

    private static boolean hasOddTrailingBackslashes(StringBuilder value) {
        int count = 0;
        for (int i = value.length() - 1; i >= 0 && value.charAt(i) == '\\'; i--) {
            count++;
        }
        return count % 2 != 0;
    }
}
