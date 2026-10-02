package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of the import column-mapping table: a file column and the table column it resolves to.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportColumnMatch {

    private String fileColumn;

    private String tableColumn;

    private boolean matched;
}
