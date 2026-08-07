package ai.chat2db.spi;

import liquibase.changelog.ChangeSet;
import liquibase.database.Database;

import java.util.List;

/**
 * Database plugin hook for normalizing Liquibase change sets before serialization.
 */
@FunctionalInterface
public interface IDbDiffChangeSetProcessor {

    IDbDiffChangeSetProcessor NO_OP = (changeSets, targetDatabase) -> {
    };

    /**
     * Applies database-specific corrections to generated change sets.
     *
     * @param changeSets generated Liquibase change sets
     * @param targetDatabase target Liquibase database
     */
    void process(List<ChangeSet> changeSets, Database targetDatabase);
}
