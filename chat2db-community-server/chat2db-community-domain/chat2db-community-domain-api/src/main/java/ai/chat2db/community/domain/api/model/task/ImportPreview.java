package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Dry-run view of an import source: detected format details, the column-mapping table and the
 * first data rows, produced before anything is written to the database.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportPreview {

    private List<String> fileColumns;

    private List<ImportColumnMatch> columnMatches;

    private List<List<String>> sampleRows;

    private String detectedCharset;

    private String detectedDelimiter;

    /**
     * Table columns the source file does not supply; they will be imported as {@code NULL} or
     * their defaults.
     */
    private List<String> missingTableColumns;
}
