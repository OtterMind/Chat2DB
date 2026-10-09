package ai.chat2db.community.domain.api.model.db;

import lombok.Builder;
import lombok.Data;

/**
 * Resolved reading instruction for a large value: where the content lives inside a result snapshot plus the type
 * information needed to slice and label it.
 */
@Data
@Builder
public class LargeValueReference {

    /**
     * Name of the result table, used to build a readable download file name.
     */
    private String tableName;

    /**
     * Name of the column the value came from, used to build a readable download file name.
     */
    private String columnName;

    private String valueType;

    private Integer sqlType;

    private String columnType;

    private String snapshotId;

    private Integer rowIndex;

    private Integer columnIndex;

    public boolean snapshotBacked() {
        return snapshotId != null;
    }
}
