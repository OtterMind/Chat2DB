package ai.chat2db.community.domain.api.service.file;

import ai.chat2db.community.domain.api.model.task.TaskFileFormat;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The names a staged import upload is stored under: the opaque identifier, the file extensions the
 * import wizard accepts, and the file name the two form together.
 *
 * The service that accepts an upload and the store that writes it both need this vocabulary, so it
 * lives next to the store contract instead of inside one of them. A staged file name is
 * {@code <identifier>.<extension>}; a claim sidecar or a half-written temporary file carries a
 * different name and is therefore never mistaken for an upload.
 */
public final class ImportStagingFiles {

    /** Extensions an upload may carry, in the lower-case spelling used for file names. */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            TaskFileFormat.CSV.name().toLowerCase(Locale.ROOT),
            TaskFileFormat.XLS.name().toLowerCase(Locale.ROOT),
            TaskFileFormat.XLSX.name().toLowerCase(Locale.ROOT),
            TaskFileFormat.JSON.name().toLowerCase(Locale.ROOT),
            TaskFileFormat.SQL.name().toLowerCase(Locale.ROOT));

    /** The identifier shape an upload is stored under; a name that does not match never names a file. */
    private static final Pattern FILE_ID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final char EXTENSION_SEPARATOR = '.';

    private ImportStagingFiles() {
    }

    /** Every extension the import wizard accepts. */
    public static Set<String> allowedExtensions() {
        return ALLOWED_EXTENSIONS;
    }

    public static boolean isAllowedExtension(String extension) {
        return extension != null && ALLOWED_EXTENSIONS.contains(extension);
    }

    /**
     * Whether a value is an identifier an upload can be stored under. A crafted value such as
     * {@code ../outside} never matches, so it can never take part in a file name.
     */
    public static boolean isFileId(String fileId) {
        return fileId != null && FILE_ID_PATTERN.matcher(fileId).matches();
    }

    /** The lower-case extension of a file name, or an empty string when the name carries none. */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int separator = fileName.lastIndexOf(EXTENSION_SEPARATOR);
        return separator < 1 ? "" : fileName.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    /** The file name one identifier is stored under; the caller has validated both parts. */
    public static String fileName(String fileId, String extension) {
        return fileId + EXTENSION_SEPARATOR + extension;
    }

    /** Whether a directory entry is a stored upload rather than a sidecar or a temporary file. */
    public static boolean isStagedFileName(String fileName) {
        if (fileName == null) {
            return false;
        }
        int separator = fileName.lastIndexOf(EXTENSION_SEPARATOR);
        return separator > 0
                && isFileId(fileName.substring(0, separator))
                && isAllowedExtension(fileName.substring(separator + 1).toLowerCase(Locale.ROOT));
    }

    /** The identifier of a staged file name, or the whole name when it carries no extension. */
    public static String fileIdOf(String fileName) {
        int separator = fileName.lastIndexOf(EXTENSION_SEPARATOR);
        return separator < 0 ? fileName : fileName.substring(0, separator);
    }
}
