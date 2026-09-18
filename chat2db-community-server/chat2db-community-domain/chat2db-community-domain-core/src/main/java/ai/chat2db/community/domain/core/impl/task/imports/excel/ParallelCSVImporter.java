package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.imports.BaseImporter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportRowBatcher;
import ai.chat2db.community.domain.core.impl.task.imports.ImportRowSqlBuilder;
import ai.chat2db.community.domain.core.impl.task.imports.reader.CsvImportReader;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.io.File;

/** CSV row parsing and submission for the explicitly selected fast mode. */
final class ParallelCSVImporter extends BaseImporter {

    @Override
    protected void doImportData(ImportTaskSpec spec, TaskExecutionContext context,
            List<TableColumn> columns) {
        long startedNanos = System.nanoTime();
        CsvOptions options = (spec.getCsvOptions() == null ? CsvOptions.defaults() : spec.getCsvOptions()).validate();
        spec.setCsvOptions(options);
        ImportRowBatcher[] batcher = {null};
        ImportRowSqlBuilder rowSqlBuilder = new ImportRowSqlBuilder(spec, columns);
        try {
            CsvImportReader.read(new File(spec.getSourceFile()), options, Integer.MAX_VALUE,
                    CSVImporter.mappedSourceColumnCount(spec),
                    headers -> batcher[0] = createBatcher(context, headers, rowSqlBuilder),
                    (row, rowNumber) -> batcher[0].accept(rowNumber, rowSqlBuilder.build(row, rowNumber)),
                    context::checkCancelled);
            if (batcher[0] != null) {
                batcher[0].flush();
            }
        } catch (RuntimeException | Error failure) {
            if (batcher[0] != null) {
                batcher[0].abort(failure);
            }
            throw failure;
        } finally {
            if (batcher[0] != null) {
                batcher[0].close();
            }
        }
        context.logInfo("IMPORT_SUMMARY", "CSV import finished", Map.of(
                "importedRows", batcher[0] == null ? 0L : batcher[0].importedRows(),
                "elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)));
    }

    private ImportRowBatcher createBatcher(TaskExecutionContext context,
            Map<Integer, String> headers, ImportRowSqlBuilder rowSqlBuilder) {
        rowSqlBuilder.acceptHead(headers);
        return new ImportRowBatcher(context);
    }
}
