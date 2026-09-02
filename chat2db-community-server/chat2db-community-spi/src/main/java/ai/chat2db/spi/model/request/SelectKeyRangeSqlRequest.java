package ai.chat2db.spi.model.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request for the minimum and maximum value of one key column, used to derive shard boundaries.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelectKeyRangeSqlRequest {

    private String databaseName;

    private String schemaName;

    private String tableName;

    private String keyColumn;
}
