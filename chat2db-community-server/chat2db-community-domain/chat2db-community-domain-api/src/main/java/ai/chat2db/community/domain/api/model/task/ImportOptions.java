package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Optional import behaviour. Every field is optional; unset fields keep the historical defaults so
 * imports submitted before this contract existed behave exactly as before.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportOptions {

    /**
     * Encoding of the source file; {@code null} lets the importer detect UTF-8 with BOM, strict
     * UTF-8 or the platform fallback.
     */
    private String charset;

    /**
     * Single-character CSV delimiter; {@code null} is auto-detected from the first record.
     */
    private String delimiter;

    /**
     * Single-character quote, {@code "} by default.
     */
    private String quoteChar;

    /**
     * Data rows skipped after the header row.
     */
    private Integer skipRows;

    /**
     * Literal in the file that means SQL {@code NULL} (CSV), for example {@code \N}.
     */
    private String nullString;

    /**
     * Explicit file-column to table-column pairs; they take precedence over name matching.
     */
    private List<ImportColumnMapping> columnMappings;

    /**
     * {@code ABORT} (default) stops the task on the first failed row; {@code SKIP} records the row
     * in the reject artifact and continues until {@code maxErrors}.
     */
    private String onError;

    private Integer maxErrors;
}
