package ai.chat2db.community.domain.api.model.imports;

import lombok.Data;

import java.util.Map;

/**
 * One saved import format preset.
 *
 * A template is the option group of one file format - the CSV, Excel, or JSON options - stored under
 * one name, so it is a general import configuration rather than a per-table object: nothing here
 * scopes it to a data source, database, or table.
 *
 * A template also belongs to exactly one file format, and the store keeps it under the directory of
 * that format, so the wizard can list the presets of the file it is importing. {@link #fileType} is
 * the authority; the directory is only the physical grouping.
 */
@Data
public class ImportTemplate {

    /** Version of the persisted template document; written by the store and not part of the API. */
    private Integer version;

    /** User-visible template name; the store keeps at most one template per name. */
    private String name;

    /** Epoch milliseconds, used for ordering and display only. */
    private Long createdAt;

    /**
     * File format this preset applies to, named after {@code TaskFileFormat}: {@code CSV},
     * {@code XLS}, {@code XLSX}, or {@code JSON}. Null means the format is unknown, which is what a
     * legacy document whose format options cannot be attributed reports.
     */
    private String fileType;

    /**
     * The complete option group the wizard had on its format step; it holds exactly the group of
     * {@link #fileType} ({@code csvOptions}, {@code excelOptions}, or {@code jsonOptions}).
     */
    private Map<String, Object> formatOptions;
}
