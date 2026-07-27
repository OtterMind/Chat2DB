package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.spi.IDbDiffChangeSetProcessor;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.diff.DiffResult;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.changelog.DiffToChangeLog;

import java.util.List;
import java.util.Objects;

/**
 * Applies the target database plugin hook before Liquibase serializes the changelog.
 */
final class Chat2dbDiffToChangeLog extends DiffToChangeLog {

    private final Database targetDatabase;
    private final IDbDiffChangeSetProcessor changeSetProcessor;

    Chat2dbDiffToChangeLog(DiffResult diffResult, DiffOutputControl diffOutputControl,
            IDbDiffChangeSetProcessor changeSetProcessor) {
        super(diffResult, diffOutputControl);
        this.targetDatabase = diffResult.getComparisonSnapshot().getDatabase();
        this.changeSetProcessor = Objects.requireNonNull(changeSetProcessor, "changeSetProcessor");
    }

    @Override
    public List<ChangeSet> generateChangeSets() {
        List<ChangeSet> changeSets = super.generateChangeSets();
        changeSetProcessor.process(changeSets, targetDatabase);
        return changeSets;
    }
}
