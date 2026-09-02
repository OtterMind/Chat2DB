package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One explicit file-column to table-column mapping of an import.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportColumnMapping {

    /**
     * Column name (or index as text) in the source file.
     */
    private String source;

    /**
     * Column name in the target table.
     */
    private String target;
}
