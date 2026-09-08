package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportColumnMatch;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.tools.exception.ParamBusinessException;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves which file column feeds which table column. Explicit mappings win; otherwise matching is
 * case-insensitive on trimmed names. Unmatched file columns are reported instead of silently
 * dropping data as the old upper-case-equality rule did.
 */
public final class ImportColumnResolver {

    /**
     * Ordered pair lists: entry {@code i} binds {@code fileValues[fileIndexes[i]]} to
     * {@code tableColumns[i]}.
     */
    public record Resolution(List<TableColumn> tableColumns, List<Integer> fileIndexes,
                             List<ImportColumnMatch> matches, List<String> missingTableColumns) {
    }

    private ImportColumnResolver() {
    }

    public static Resolution resolve(List<TableColumn> tableColumns, List<String> fileHeaders,
            ImportOptions options) {
        Map<String, Integer> byNormalizedName = new LinkedHashMap<>();
        for (int index = 0; index < fileHeaders.size(); index++) {
            byNormalizedName.putIfAbsent(normalize(fileHeaders.get(index)), index);
        }
        Map<String, Integer> explicitTargets = new LinkedHashMap<>();
        if (options != null && options.getColumnMappings() != null) {
            for (ImportColumnMapping mapping : options.getColumnMappings()) {
                if (mapping == null || StringUtils.isBlank(mapping.getSourceColumn())
                        || StringUtils.isBlank(mapping.getTargetColumn())) {
                    throw new ParamBusinessException("columnMappings");
                }
                Integer sourceIndex = indexOfSource(mapping.getSourceColumn().trim(), fileHeaders,
                        byNormalizedName);
                if (sourceIndex == null) {
                    throw new ParamBusinessException("columnMappings source: " + mapping.getSourceColumn());
                }
                explicitTargets.put(normalize(mapping.getTargetColumn()), sourceIndex);
            }
        }

        List<TableColumn> resolvedColumns = new ArrayList<>();
        List<Integer> fileIndexes = new ArrayList<>();
        List<String> missingTableColumns = new ArrayList<>();
        for (TableColumn column : tableColumns) {
            Integer sourceIndex = explicitTargets.get(normalize(column.getName()));
            if (sourceIndex == null) {
                sourceIndex = byNormalizedName.get(normalize(column.getName()));
            }
            if (sourceIndex != null) {
                resolvedColumns.add(column);
                fileIndexes.add(sourceIndex);
            } else {
                missingTableColumns.add(column.getName());
            }
        }

        java.util.Set<Integer> usedFileIndexes = new java.util.HashSet<>(fileIndexes);
        List<ImportColumnMatch> matches = new ArrayList<>(fileHeaders.size());
        for (int index = 0; index < fileHeaders.size(); index++) {
            String tableColumn = null;
            for (int resolved = 0; resolved < fileIndexes.size(); resolved++) {
                if (fileIndexes.get(resolved) == index) {
                    tableColumn = resolvedColumns.get(resolved).getName();
                    break;
                }
            }
            matches.add(ImportColumnMatch.builder()
                    .fileColumn(fileHeaders.get(index))
                    .tableColumn(tableColumn)
                    .matched(usedFileIndexes.contains(index))
                    .build());
        }
        return new Resolution(resolvedColumns, fileIndexes, matches, missingTableColumns);
    }

    private static Integer indexOfSource(String source, List<String> fileHeaders,
            Map<String, Integer> byNormalizedName) {
        try {
            int index = Integer.parseInt(source);
            return index >= 0 && index < fileHeaders.size() ? index : null;
        } catch (NumberFormatException ignored) {
            return byNormalizedName.get(normalize(source));
        }
    }

    /**
     * Case-insensitive match on trimmed names, ignoring a leading UTF-8 BOM: commons-csv does not
     * strip it, and without this the first column of every BOM-prefixed file (including files
     * written by our own CsvSink) would never match.
     */
    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name;
        if (!trimmed.isEmpty() && trimmed.charAt(0) == '\ufeff') {
            trimmed = trimmed.substring(1);
        }
        return trimmed.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
