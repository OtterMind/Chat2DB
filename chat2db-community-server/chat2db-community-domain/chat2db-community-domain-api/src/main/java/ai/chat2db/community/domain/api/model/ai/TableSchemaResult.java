package ai.chat2db.community.domain.api.model.ai;

import ai.chat2db.community.domain.api.model.metadata.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed service use-case result for table schema lookup.
 * This is not an AI payload and must not contain JSON, summaries, display text, or transport envelopes.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TableSchemaResult {

    private String tableName;

    private String ddl;

    private Table table;
}
