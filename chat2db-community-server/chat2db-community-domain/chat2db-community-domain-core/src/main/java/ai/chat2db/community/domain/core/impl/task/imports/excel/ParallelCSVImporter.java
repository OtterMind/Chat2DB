package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.imports.BaseImporter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportCheckpointWriter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportErrorRecorder;
import ai.chat2db.community.domain.core.impl.task.imports.ImportResumability;
import ai.chat2db.community.domain.core.impl.task.imports.ImportRowBatcher;
import ai.chat2db.community.domain.core.impl.task.imports.ImportRowSkipper;
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
        ImportCheckpointWriter checkpoints = new ImportCheckpointWriter(context, spec);
        ImportRowSkipper skipper = new ImportRowSkipper(spec);
        ImportErrorRecorder errors = new ImportErrorRecorder(context, spec);
        try {
            CsvImportReader.read(new File(spec.getSourceFile()), options, Integer.MAX_VALUE,
                    CSVImporter.mappedSourceColumnCount(spec),
                    headers -> batcher[0] = createBatcher(context, headers, rowSqlBuilder, checkpoints, errors,
                            ImportResumability.resumeOffset(spec)),
                    (row, rowNumber) -> {
                        if (skipper.skipNext()) {
                            return;
                        }
                        List<Object> parameters;
                        try {
                            parameters = rowSqlBuilder.parameters(row, rowNumber);
                        } catch (TaskCancelledException e) {
                            throw e;
                        } catch (RuntimeException e) {
                            errors.rejectRow(rowNumber, rowSqlBuilder.displayValues(row), e);
                            return;
                        }
                        batcher[0].accept(rowNumber, rowSqlBuilder.insertTemplate(), parameters);
                    },
                    context::checkCancelled);
            // Already submitted batches may still be running; EOF precedes their final drain.
            context.logInfo(TaskEventCode.FILE_READ_COMPLETED.name(), "Import file read completed");
            if (batcher[0] != null) {
                batcher[0].flush();
            }
        } catch (RuntimeException | Error failure) {
            if (batcher[0] != null) {
                batcher[0].abort(failure);
            }
            throw failure;
        } finally {
            // Workers must be drained before the error file and the checkpoint are finished; a
            // worker that completed its batch just before the failure still counts as committed.
            try {
                if (batcher[0] != null) {
                    batcher[0].close();
                }
            } finally {
                errors.close();
                checkpoints.flush();
            }
        }
        context.logInfo("IMPORT_SUMMARY", "CSV import finished", Map.of(
                "importedRows", batcher[0] == null ? 0L : batcher[0].importedRows(),
                "rejectedRows", errors.rejectedRows(),
                "elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)));
    }

    private ImportRowBatcher createBatcher(TaskExecutionContext context,
            Map<Integer, String> headers, ImportRowSqlBuilder rowSqlBuilder,
            ImportCheckpointWriter checkpoints, ImportErrorRecorder errors, long resumeOffset) {
        rowSqlBuilder.acceptHead(headers);
        return new ImportRowBatcher(context, checkpoints, errors, resumeOffset);
    }
}
