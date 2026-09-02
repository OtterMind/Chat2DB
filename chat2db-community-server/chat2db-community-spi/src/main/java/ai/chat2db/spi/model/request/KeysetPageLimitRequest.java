package ai.chat2db.spi.model.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Keyset (seek) pagination over a stable key: an exclusive {@code bounds} predicate, the key order,
 * and the per-page row count. With empty bounds it degenerates into the plain ordered cursor query.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeysetPageLimitRequest {

    private String databaseName;

    private String schemaName;

    private String tableName;

    /**
     * Selected columns; {@code null} or empty selects all columns.
     */
    private List<String> columnList;

    /**
     * Key columns in cursor order; composite cursors extend the bounds predicate key by key.
     */
    private List<String> keyColumns;

    private List<KeyBound> bounds;

    private int fetchSize;
}
