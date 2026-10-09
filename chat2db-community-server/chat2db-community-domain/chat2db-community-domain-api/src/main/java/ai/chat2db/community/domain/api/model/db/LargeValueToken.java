package ai.chat2db.community.domain.api.model.db;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Server side handle for one large value of a result set.
 * <p>
 * The client only ever sees {@link #id}; everything else stays in the process. The handle points at the content of a
 * result snapshot, which is why reads never have to touch the database again.
 */
@Data
@Builder
public class LargeValueToken {

    private String id;

    /**
     * Name of the result table, used to build a readable download file name.
     */
    private String tableName;

    /**
     * Name of the column the value came from, used to build a readable download file name.
     */
    private String columnName;

    private Instant expiresAt;

    private String valueType;

    private Integer sqlType;

    private String columnType;

    private String snapshotId;

    private Integer rowIndex;

    private Integer columnIndex;

}
