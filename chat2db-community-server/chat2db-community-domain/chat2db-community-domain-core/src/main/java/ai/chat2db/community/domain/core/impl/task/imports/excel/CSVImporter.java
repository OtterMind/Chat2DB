package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskExecutionMode;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.imports.IImportStrategy;
import ai.chat2db.community.domain.core.impl.task.imports.reader.SourceColumnName;
import ai.chat2db.community.domain.core.impl.task.imports.reader.CsvImportReader;

import java.util.List;
import java.io.File;

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
        CsvImportReader.read(new File(spec.getSourceFile()), options, Integer.MAX_VALUE,
                mappedSourceColumnCount(spec), listener::acceptHead, listener::acceptRow, context::checkCancelled);
        listener.finish();
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
