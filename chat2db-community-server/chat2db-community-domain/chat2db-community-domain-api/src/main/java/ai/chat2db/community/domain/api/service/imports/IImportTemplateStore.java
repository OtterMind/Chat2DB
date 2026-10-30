package ai.chat2db.community.domain.api.service.imports;

import ai.chat2db.community.domain.api.model.imports.ImportTemplate;

import java.util.List;

/**
 * Stores the import format presets as one JSON document per template under the workspace storage
 * directory, so a template survives a browser change, a port change, and a backend restart.
 *
 * The store owns the physical layout: a template lives in
 * {@code <storage base path>/import-templates/<type>/<file name>.json}, where {@code <type>} is the
 * lower-case file type ({@code csv}, {@code xlsx}, {@code xls}, or {@code json}) and the file name is
 * the template name turned into a safe one. A document that names no usable file type has no
 * directory and stays in the template root. The file type inside the document stays the authority -
 * the directory is only the physical grouping.
 *
 * Listing reads every stored document and skips one that cannot be read with a warning instead of
 * failing the whole read. A document is replaced atomically, so a crash never leaves a truncated
 * template behind.
 */
public interface IImportTemplateStore {

    /** Reads every stored template, including the ones whose file type is unknown. */
    List<ImportTemplate> read();

    /**
     * Reads the templates stored under one file type; a blank or unsupported value reads as no
     * templates.
     *
     * @param fileType a {@code TaskFileFormat} name such as {@code CSV} or {@code JSON}.
     */
    List<ImportTemplate> read(String fileType);

    /**
     * Writes one template, replacing the stored template with the same name wherever it is filed.
     * Saving a name under another file type moves it into the directory of that type.
     *
     * @throws java.io.UncheckedIOException when the document cannot be written
     */
    void save(ImportTemplate template);

    /**
     * Removes the stored template with this name; the file type is the fallback way to find its file
     * when no readable document carries the name. A blank name changes nothing.
     *
     * @throws java.io.UncheckedIOException when a matching document exists but cannot be removed
     */
    void delete(String name, String fileType);

    /**
     * The file name a template name is stored under, or {@code null} when nothing usable is left of
     * the name. Unsafe characters are replaced, the edges are trimmed, and a name that is too long is
     * cut to the file-system limit, so two template names can share one file name.
     */
    String sanitizedFileName(String name);

    /**
     * Moves the templates written to the location that predates the workspace storage directory into
     * this store, keeping every document and preserving its type directory. A document that names no
     * file type is stored under the type its option group implies and rewritten with that type. The
     * migration is idempotent: a second call finds nothing to move, and a document that cannot be
     * moved stays where it was.
     *
     * @return how many documents were moved
     */
    int migrateLegacyTemplates();
}
