package ai.chat2db.community.domain.core.impl.task.imports.json;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.JsonOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.imports.BaseImporter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportCheckpointWriter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportErrorRecorder;
import ai.chat2db.community.domain.core.impl.task.imports.ImportProgressReporter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportResumability;
import ai.chat2db.community.domain.core.impl.task.imports.ImportRowSkipper;
import ai.chat2db.community.domain.core.impl.task.imports.ImportRowSqlBuilder;
import ai.chat2db.community.domain.core.impl.task.imports.ImportSqlExecutor;
import ai.chat2db.community.domain.core.impl.task.imports.reader.ImportCell;
import ai.chat2db.community.domain.core.impl.task.imports.reader.JsonImportReader;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class JSONImporter extends BaseImporter {
    @Override
    protected void doImportData(ImportTaskSpec spec, TaskExecutionContext context, List<TableColumn> columns) {
        JsonOptions options = (spec.getJsonOptions() == null ? new JsonOptions() : spec.getJsonOptions()).validate();
        spec.setJsonOptions(options);
        ImportRowSqlBuilder builder = new ImportRowSqlBuilder(spec, columns);
        ImportCheckpointWriter checkpoints = new ImportCheckpointWriter(context, spec);
        ImportRowSkipper skipper = new ImportRowSkipper(spec);
        try (ImportErrorRecorder errors = new ImportErrorRecorder(context, spec)) {
            ImportSqlExecutor executor = new ImportSqlExecutor(context, errors);
            Map<Integer, String> header = new LinkedHashMap<>();
            List<String> names = spec.getColumnMappings() == null ? columns.stream().map(TableColumn::getName).toList()
                    : spec.getColumnMappings().stream().map(mapping -> mapping.getSourceColumn()).toList();
            for (int index = 0; index < names.size(); index++) header.put(index, names.get(index));
            builder.acceptHead(header);
            ImportProgressReporter progressReporter = new ImportProgressReporter(context, null,
                    ImportResumability.resumeOffset(spec));
            // Counts every source record, including the ones the failure policy rejects, so the
            // resumable position never lags behind an already-stored row.
            AtomicLong consumedRows = new AtomicLong();
            List<List<Object>> batch = new ArrayList<>(BATCH_SIZE);
            List<Long> rowNumbers = new ArrayList<>(BATCH_SIZE);
            JsonImportReader.read(new File(spec.getSourceFile()), options, Integer.MAX_VALUE, (record, rowNumber) -> {
                if (skipper.skipNext()) {
                    return;
                }
                consumedRows.incrementAndGet();
                Map<Integer, ImportCell> cells = new LinkedHashMap<>();
                header.forEach((index, name) -> cells.put(index, record.get(name)));
                List<Object> parameters;
                try {
                    parameters = builder.parametersCells(cells, rowNumber);
                } catch (TaskCancelledException e) {
                    throw e;
                } catch (RuntimeException e) {
                    errors.rejectRow(rowNumber, builder.displayValuesCells(cells), e);
                    return;
                }
                batch.add(parameters);
                rowNumbers.add(rowNumber.longValue());
                if (batch.size() >= BATCH_SIZE) {
                    executeBatch(executor, builder, batch, rowNumbers, consumedRows, progressReporter, checkpoints);
                }
            }, context::checkCancelled);
            context.logInfo(TaskEventCode.FILE_READ_COMPLETED.name(), "Import file read completed");
            executeBatch(executor, builder, batch, rowNumbers, consumedRows, progressReporter, checkpoints);
            checkpoints.flush();
        } catch (RuntimeException | Error failure) {
            // Keep the position of the last committed batch even when this run fails.
            checkpoints.flush();
            throw failure;
        }
    }

    /**
     * Executes the buffered rows and moves the resumable position past the whole batch. The
     * position counts source records, so it also covers rows the failure policy had to reject.
     */
    private void executeBatch(ImportSqlExecutor executor, ImportRowSqlBuilder builder,
            List<List<Object>> batch, List<Long> rowNumbers, AtomicLong consumedRows,
            ImportProgressReporter progressReporter, ImportCheckpointWriter checkpoints) {
        if (batch.isEmpty()) {
            return;
        }
        long processed = consumedRows.longValue();
        executor.executeParameterizedBatch(builder.insertTemplate(), rowNumbers, batch,
                committed -> checkpoints.batchCommitted(processed));
        progressReporter.report(processed);
        batch.clear();
        rowNumbers.clear();
    }
}
