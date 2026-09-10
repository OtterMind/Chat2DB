package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A physical or operator-supplied logical parent-to-child dependency. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportTableDependency {

    private String parentDatabaseName;

    private String parentSchemaName;

    private String parentTable;

    private String parentColumn;

    /** Canonical qualified node used by SCC/DAG planning. */
    private String parentTableKey;

    private String childDatabaseName;

    private String childSchemaName;

    private String childTable;

    private String childColumn;

    /** Canonical qualified node used by SCC/DAG planning. */
    private String childTableKey;

    private String constraintName;

    private Short keySequence;

    private Short deferrability;

    private boolean logical;
}
