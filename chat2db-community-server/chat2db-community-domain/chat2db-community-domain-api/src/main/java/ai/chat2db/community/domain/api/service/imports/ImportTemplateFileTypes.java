package ai.chat2db.community.domain.api.service.imports;

import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The file formats an import template can carry and the directory each of them is stored in.
 *
 * The names are {@link TaskFileFormat} names, the directories are their lower-case form, so {@code CSV}
 * lives in {@code csv/} and {@code XLSX} in {@code xlsx/}. A template whose format is unknown - a
 * legacy document whose option group cannot be attributed - has no directory and stays in the
 * template root.
 *
 * The mapping is shared by the domain service, which reports the file type of a template, and by the
 * store, which files it.
 */
public final class ImportTemplateFileTypes {

    /** Option group that identifies a CSV document. */
    public static final String CSV_OPTIONS = "csvOptions";

    /** Option group that identifies an Excel document. */
    public static final String EXCEL_OPTIONS = "excelOptions";

    /** Option group that identifies a JSON document. */
    public static final String JSON_OPTIONS = "jsonOptions";

    /**
     * Stored file types and their directories, in the order a fallback read walks them. Excel comes
     * before JSON only to keep the order stable.
     */
    private static final Map<String, String> DIRECTORIES_BY_TYPE = directoriesByType();

    private ImportTemplateFileTypes() {
    }

    /**
     * The stored form of a file type: the {@link TaskFileFormat} name for a type templates support,
     * null for a blank or unsupported value.
     */
    public static String normalize(String fileType) {
        if (StringUtils.isBlank(fileType)) {
            return null;
        }
        String candidate = fileType.trim().toUpperCase(Locale.ROOT);
        return DIRECTORIES_BY_TYPE.containsKey(candidate) ? candidate : null;
    }

    /**
     * The file type of a document that does not record one, read from the option group it carries.
     * Excel options are shared by XLS and XLSX, so they resolve to the modern XLSX; a document with
     * several groups resolves in the {@code csvOptions}, {@code excelOptions}, {@code jsonOptions}
     * order; anything else is unknown.
     */
    public static String infer(Map<String, Object> formatOptions) {
        if (formatOptions == null || formatOptions.isEmpty()) {
            return null;
        }
        if (formatOptions.containsKey(CSV_OPTIONS)) {
            return TaskFileFormat.CSV.name();
        }
        if (formatOptions.containsKey(EXCEL_OPTIONS)) {
            return TaskFileFormat.XLSX.name();
        }
        if (formatOptions.containsKey(JSON_OPTIONS)) {
            return TaskFileFormat.JSON.name();
        }
        return null;
    }

    /**
     * The type a stored template belongs to: the recorded type when it is usable, otherwise the one
     * its option group implies, otherwise null for an unknown type.
     */
    public static String resolve(String fileType, Map<String, Object> formatOptions) {
        String normalized = normalize(fileType);
        return normalized == null ? infer(formatOptions) : normalized;
    }

    /** The directory a file type is stored in; null when the type is unknown. */
    public static String directoryName(String fileType) {
        String normalized = normalize(fileType);
        return normalized == null ? null : DIRECTORIES_BY_TYPE.get(normalized);
    }

    /** Every template directory, in the order a read that is not narrowed by a type walks them. */
    public static List<String> directoryNames() {
        return List.copyOf(DIRECTORIES_BY_TYPE.values());
    }

    private static Map<String, String> directoriesByType() {
        Map<String, String> directories = new LinkedHashMap<>();
        directories.put(TaskFileFormat.CSV.name(), TaskFileFormat.CSV.name().toLowerCase(Locale.ROOT));
        directories.put(TaskFileFormat.XLSX.name(), TaskFileFormat.XLSX.name().toLowerCase(Locale.ROOT));
        directories.put(TaskFileFormat.XLS.name(), TaskFileFormat.XLS.name().toLowerCase(Locale.ROOT));
        directories.put(TaskFileFormat.JSON.name(), TaskFileFormat.JSON.name().toLowerCase(Locale.ROOT));
        // A plain immutable copy would drop the declared order the fallback read depends on.
        return Collections.unmodifiableMap(directories);
    }
}
