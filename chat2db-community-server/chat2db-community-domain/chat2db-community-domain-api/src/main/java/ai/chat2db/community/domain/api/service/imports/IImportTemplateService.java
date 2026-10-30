package ai.chat2db.community.domain.api.service.imports;

import ai.chat2db.community.domain.api.model.imports.ImportTemplate;

import java.util.List;

/**
 * Global import format presets, persisted as one JSON file per preset under the user data directory.
 *
 * A preset is the option group of one file format. Presets are not scoped to a data source,
 * database, or table, so listing, upserting, and deleting never need such a scope; mutating methods
 * return the stored list after the change so callers can refresh their view without a second read.
 * A preset does belong to one file format, and the store groups the files by it.
 */
public interface IImportTemplateService {

    /** Lists the presets of every file format, including the ones whose format is unknown. */
    List<ImportTemplate> list();

    /**
     * Lists the presets of one file format; a blank or unsupported value reads as no presets.
     *
     * @param fileType a {@code TaskFileFormat} name such as {@code CSV} or {@code JSON}.
     */
    List<ImportTemplate> list(String fileType);

    /** Creates the template or replaces the stored template with the same name. */
    List<ImportTemplate> upsert(ImportTemplate importTemplate);

    /** Removes the template with this name; deleting an absent name changes nothing. */
    List<ImportTemplate> delete(String name);

    /**
     * Removes the template with this name, using the file type as the fallback way to find its file
     * when no readable document carries the name.
     */
    List<ImportTemplate> delete(String name, String fileType);
}
