package ai.chat2db.community.domain.core.impl.task.imports.reader;

import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.core.impl.db.CsvParser;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Selected rows of a CSV file: the configured header row plus the data rows inside the configured
 * range. Preview and import task share it so both apply the same header and range rules.
 */
public final class CsvImportReader {

    private CsvImportReader() {
    }

    public static void read(File file, CsvOptions settings, int limit, int minimumColumns,
            Consumer<Map<Integer, String>> headerConsumer,
            BiConsumer<Map<Integer, String>, Integer> rowConsumer, Runnable checkCancelled) {
        CsvOptions options = (settings == null ? CsvOptions.defaults() : settings).validate();
        if (limit == Integer.MAX_VALUE) {
            streamRows(file, options, minimumColumns, headerConsumer, rowConsumer, checkCancelled);
            return;
        }
        readRows(file, options, limit, minimumColumns, headerConsumer, rowConsumer);
    }

    public static Map<Integer, String> syntheticHeader(int columnCount) {
        Map<Integer, String> header = new LinkedHashMap<>();
        for (int index = 0; index < columnCount; index++) {
            header.put(index, SourceColumnName.of(index));
        }
        return header;
    }

    /** Streams the selected rows; the execution path must not materialize the file. */
    private static void streamRows(File file, CsvOptions options, int minimumColumns,
            Consumer<Map<Integer, String>> headerConsumer,
            BiConsumer<Map<Integer, String>, Integer> rowConsumer, Runnable checkCancelled) {
        boolean[] headerSent = {false};
        int[] rowNumber = {0};
        new CsvParser(options).forEachRow(file.toPath(), row -> {
            int currentRow = ++rowNumber[0];
            if (Boolean.TRUE.equals(options.getHasHeader()) && currentRow == options.getHeaderRow()) {
                headerSent[0] = true;
                headerConsumer.accept(row);
                return;
            }
            if (currentRow < options.getDataStartRow()
                    || options.getDataEndRow() != null && currentRow > options.getDataEndRow()) {
                return;
            }
            if (!headerSent[0]) {
                headerSent[0] = true;
                headerConsumer.accept(syntheticHeader(Math.max(row.size(), minimumColumns)));
            }
            rowConsumer.accept(row, currentRow);
        }, checkCancelled);
    }

    /** Reads at most {@code limit} data rows; the preview only needs the visible window. */
    private static void readRows(File file, CsvOptions options, int limit, int minimumColumns,
            Consumer<Map<Integer, String>> headerConsumer,
            BiConsumer<Map<Integer, String>, Integer> rowConsumer) {
        int previewEndRow = options.getDataStartRow() + limit - 1;
        if (options.getDataEndRow() != null) {
            previewEndRow = Math.min(previewEndRow, options.getDataEndRow());
        }
        int parseLimit = Math.max(previewEndRow,
                Boolean.TRUE.equals(options.getHasHeader()) ? options.getHeaderRow() : 0);
        List<Map<Integer, String>> rows = new CsvParser(options).parse(file.toPath(), parseLimit).rows();
        if (rows.isEmpty()) {
            return;
        }
        int firstDataIndex = options.getDataStartRow() - 1;
        int dataEndIndex = Math.min(rows.size(), previewEndRow);
        List<Map<Integer, String>> data = firstDataIndex >= dataEndIndex
                ? List.of() : rows.subList(firstDataIndex, dataEndIndex);
        if (Boolean.TRUE.equals(options.getHasHeader())) {
            int headerIndex = options.getHeaderRow() - 1;
            if (headerIndex >= rows.size()) {
                return;
            }
            headerConsumer.accept(rows.get(headerIndex));
        } else {
            int sourceColumnCount = data.stream().mapToInt(Map::size).max().orElse(0);
            headerConsumer.accept(syntheticHeader(Math.max(sourceColumnCount, minimumColumns)));
        }
        for (int index = firstDataIndex; index < dataEndIndex; index++) {
            rowConsumer.accept(rows.get(index), index + 1);
        }
    }
}
