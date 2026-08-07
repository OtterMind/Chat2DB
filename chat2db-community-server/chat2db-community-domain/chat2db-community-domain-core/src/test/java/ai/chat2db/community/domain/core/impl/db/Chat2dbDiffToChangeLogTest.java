package ai.chat2db.community.domain.core.impl.db;

import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.core.H2Database;
import liquibase.diff.DiffResult;
import liquibase.diff.compare.CompareControl;
import liquibase.diff.output.DiffOutputControl;
import liquibase.snapshot.EmptyDatabaseSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;

class Chat2dbDiffToChangeLogTest {

    @Test
    void delegatesGeneratedChangeSetsToTargetPluginProcessor() throws Exception {
        Database sourceDatabase = new H2Database();
        Database targetDatabase = new H2Database();
        DiffResult diffResult = new DiffResult(
                new EmptyDatabaseSnapshot(sourceDatabase),
                new EmptyDatabaseSnapshot(targetDatabase),
                new CompareControl());
        AtomicReference<List<ChangeSet>> processedChangeSets = new AtomicReference<>();
        AtomicReference<Database> processedDatabase = new AtomicReference<>();

        Chat2dbDiffToChangeLog diffToChangeLog = new Chat2dbDiffToChangeLog(
                diffResult,
                new DiffOutputControl(),
                (changeSets, database) -> {
                    processedChangeSets.set(changeSets);
                    processedDatabase.set(database);
                });

        List<ChangeSet> generatedChangeSets = diffToChangeLog.generateChangeSets();

        assertSame(generatedChangeSets, processedChangeSets.get());
        assertSame(targetDatabase, processedDatabase.get());
    }
}
