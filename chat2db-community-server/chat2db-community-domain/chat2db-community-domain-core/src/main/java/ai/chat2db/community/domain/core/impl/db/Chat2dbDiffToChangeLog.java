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
import java.util.regex.Pattern;

/**
 * Repairs MySQL computed index metadata before Liquibase serializes the changelog.
 */
final class Chat2dbDiffToChangeLog extends DiffToChangeLog {

    private static final Pattern MYSQL_ESCAPED_CHARSET_LITERAL =
            Pattern.compile("(?i)(_[a-z0-9]+)\\\\'([^'\\\\\\r\\n]*)\\\\'");

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
                String expression = MYSQL_ESCAPED_CHARSET_LITERAL.matcher(column.getName()).replaceAll("$1'$2'");
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
}
