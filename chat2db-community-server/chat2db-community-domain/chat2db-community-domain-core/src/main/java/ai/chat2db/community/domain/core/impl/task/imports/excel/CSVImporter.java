package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskExecutionMode;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.db.CsvParser;
import ai.chat2db.community.domain.core.impl.task.imports.IImportStrategy;
import ai.chat2db.community.domain.core.impl.task.imports.reader.SourceColumnName;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

public class CSVImporter extends BaseExcelImporter implements IImportStrategy {

    @Override
    protected void doImportData(ImportTaskSpec spec, TaskExecutionContext context, List<TableColumn> columns) {
        if (TaskExecutionMode.isFast(spec.getMode())) {
            new ParallelCSVImporter().doImportData(spec, context, columns);
            return;
        }
        CsvOptions options = (spec.getCsvOptions() == null ? CsvOptions.defaults() : spec.getCsvOptions()).validate();
        spec.setCsvOptions(options);
        NoModelDataListener listener = new NoModelDataListener(spec, context, columns);
        boolean[] initialized = {false};
        int[] sourceRow = {0};
        new CsvParser(options).forEachRow(Path.of(spec.getSourceFile()), row -> {
            int rowNumber = ++sourceRow[0];
            if (Boolean.TRUE.equals(options.getHasHeader()) && rowNumber == options.getHeaderRow()) {
                initialized[0] = true;
                listener.acceptHead(row);
                return;
            }
            if (rowNumber < options.getDataStartRow()
                    || options.getDataEndRow() != null && rowNumber > options.getDataEndRow()) {
                return;
            }
            if (!initialized[0]) {
                initialized[0] = true;
                listener.acceptHead(syntheticHeader(Math.max(row.size(), mappedSourceColumnCount(spec))));
            }
            listener.acceptRow(row, rowNumber);
        }, context::checkCancelled);
        if (initialized[0]) {
            listener.finish();
        }
    }

    static Map<Integer, String> syntheticHeader(int columnCount) {
        Map<Integer, String> header = new LinkedHashMap<>();
        for (int index = 0; index < columnCount; index++) {
            header.put(index, SourceColumnName.of(index));
        }
        return header;
    }

    static int mappedSourceColumnCount(ImportTaskSpec spec) {
        if (spec.getColumnMappings() == null) {
            return 0;
        }
        return spec.getColumnMappings().stream()
                .mapToInt(mapping -> SourceColumnName.columnNumber(mapping.getSourceColumn()))
                .max()
                .orElse(0);
    }
}
