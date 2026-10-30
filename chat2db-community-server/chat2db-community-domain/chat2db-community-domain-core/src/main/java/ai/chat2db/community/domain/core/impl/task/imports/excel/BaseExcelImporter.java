package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.core.impl.task.imports.BaseImporter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportCheckpointWriter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportErrorRecorder;
import ai.chat2db.community.domain.core.impl.task.imports.ImportResumability;
import ai.chat2db.community.domain.core.impl.task.imports.ImportRowSkipper;
import ai.chat2db.community.domain.core.impl.task.imports.ImportSqlExecutor;
import ai.chat2db.community.domain.core.impl.task.imports.ImportProgressReporter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportRowSqlBuilder;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.api.model.task.ExcelOptions;
import ai.chat2db.community.domain.core.impl.task.imports.reader.ExcelImportReader;
import ai.chat2db.community.domain.core.impl.task.imports.reader.ImportCell;
import java.util.*;


@Slf4j
public abstract class BaseExcelImporter extends BaseImporter {
    @Override
    protected void doImportData(ImportTaskSpec spec, TaskExecutionContext context, List<TableColumn> columns) {
        context.checkCancelled();
        ExcelOptions options = (spec.getExcelOptions() == null ? new ExcelOptions() : spec.getExcelOptions()).validate();
        spec.setExcelOptions(options);
        ImportCheckpointWriter checkpoints = new ImportCheckpointWriter(context, spec);
        ImportRowSkipper skipper = new ImportRowSkipper(spec);
        try (ImportErrorRecorder errors = new ImportErrorRecorder(context, spec)) {
            NoModelDataListener listener = new NoModelDataListener(spec, context, columns, checkpoints, errors);
            ExcelImportReader.read(new File(spec.getSourceFile()), options, Integer.MAX_VALUE,
                    CSVImporter.mappedSourceColumnCount(spec), listener::acceptHead,
                    skippedRows(skipper, listener), context::checkCancelled);
            context.logInfo(TaskEventCode.FILE_READ_COMPLETED.name(), "Import file read completed");
            listener.finish();
            context.checkCancelled();
            checkpoints.flush();
        } catch (RuntimeException | Error failure) {
            // The rows of the last committed batch are the resumable position; write it even when
            // this run is about to fail.
            checkpoints.flush();
            throw failure;
        }
    }

    /**
     * Applies the resume gate between the format reader and the batch listener: the rows of an
     * already stored prefix are dropped, while the source row numbers of everything else stay
     * exactly as the original run reported them.
     */
    private static java.util.function.BiConsumer<Map<Integer, ImportCell>, Integer> skippedRows(
            ImportRowSkipper skipper, NoModelDataListener listener) {
        return (cells, rowNumber) -> {
            if (!skipper.skipNext()) {
                listener.acceptCells(cells, rowNumber);
            }
        };
    }

    /**
     * Import options may restrict the source row range; when they do, the number of source rows is
     * known and progress can be linear in the processed rows.
     */
    private static Long configuredTotalRows(ImportTaskSpec spec) {
        boolean csv = TaskFileFormat.CSV.name().equalsIgnoreCase(spec.getFormat());
        Integer dataStartRow;
        Integer dataEndRow;
        if (csv) {
            CsvOptions options = spec.getCsvOptions();
            dataStartRow = options == null ? null : options.getDataStartRow();
            dataEndRow = options == null ? null : options.getDataEndRow();
        } else {
            ExcelOptions options = spec.getExcelOptions();
            dataStartRow = options == null ? null : options.getDataStartRow();
            dataEndRow = options == null ? null : options.getDataEndRow();
        }
        if (dataStartRow == null || dataEndRow == null || dataEndRow < dataStartRow) {
            return null;
        }
        return (long) (dataEndRow - dataStartRow + 1);
    }

    public class NoModelDataListener {

        private final TaskExecutionContext taskContext;

        /** Pending parameter rows for {@link ImportRowSqlBuilder#insertTemplate()}. */
        private List<List<Object>> parameterRows;

        /** Source row number per pending parameter row, for the error artifact. */
        private List<Long> rowNumbers;

        private long successCount;

        private long skippedCount;

        /** Every data row the reader emitted, so the resumable position follows the source file. */
        private long consumedRows;

        private static final int BATCH_SIZE = 1000;

        private final ImportSqlExecutor sqlExecutor;

        private final ImportRowSqlBuilder rowSqlBuilder;

        private final ImportProgressReporter progressReporter;

        private final ImportCheckpointWriter checkpoints;

        private final ImportErrorRecorder errors;

        public NoModelDataListener(ImportTaskSpec spec, TaskExecutionContext taskContext,
                List<TableColumn> columns, ImportCheckpointWriter checkpoints, ImportErrorRecorder errors) {
            this.taskContext = taskContext;
            this.sqlExecutor = new ImportSqlExecutor(taskContext, errors);
            this.rowSqlBuilder = new ImportRowSqlBuilder(spec, columns);
            this.progressReporter = new ImportProgressReporter(taskContext, configuredTotalRows(spec),
                    ImportResumability.resumeOffset(spec));
            this.checkpoints = checkpoints;
            this.errors = errors;
        }


        void acceptHead(Map<Integer, String> map) {
            this.taskContext.checkCancelled();
            rowSqlBuilder.acceptHead(map);
        }

        void acceptRow(Map<Integer, String> data, long sourceRowNumber) {
            Map<Integer, ImportCell> cells = new LinkedHashMap<>();
            if (data != null) data.forEach((index, value) -> cells.put(index, new ImportCell(value, true)));
            acceptCells(cells, sourceRowNumber);
        }

        void acceptCells(Map<Integer, ImportCell> data, long sourceRowNumber) {
            this.taskContext.checkCancelled();
            consumedRows++;
            if (data == null || data.isEmpty()) {
                skippedCount++;
                return;
            }
            if (parameterRows == null) {
                parameterRows = new ArrayList<>(BATCH_SIZE);
                rowNumbers = new ArrayList<>(BATCH_SIZE);
            }
            List<Object> parameters;
            try {
                parameters = rowSqlBuilder.parametersCells(data, sourceRowNumber);
            } catch (TaskCancelledException e) {
                throw e;
            } catch (RuntimeException e) {
                // A value the target column cannot represent is a row failure, not a file failure;
                // the policy decides whether the import continues (rejectRow throws on ABORT).
                errors.rejectRow(sourceRowNumber, rowSqlBuilder.displayValuesCells(data), e);
                skippedCount++;
                return;
            }
            parameterRows.add(parameters);
            rowNumbers.add(sourceRowNumber);
            if (parameterRows.size() >= BATCH_SIZE) {
                executeBatchInsert();
            }
        }

        void finish() {
            this.taskContext.checkCancelled();
            executeBatchInsert();
        }

        private void executeBatchInsert() {
            taskContext.checkCancelled();
            if (parameterRows != null && !parameterRows.isEmpty()) {
                int statementCount = parameterRows.size();
                int stored;
                try {
                    // One prepared statement shape per batch instead of one SQL literal per row. The
                    // executor applies the failure policy: it either rejects the batch or returns the
                    // rows it could still store one by one.
                    stored = sqlExecutor.executeParameterizedBatch(rowSqlBuilder.insertTemplate(),
                            rowNumbers, parameterRows, committed -> checkpoints.batchCommitted(consumedRows));
                } catch (TaskCancelledException e) {
                    throw e;
                } catch (Exception e) {
                    taskContext.logError(TaskEventCode.IMPORT_BATCH_FAILED.name(), "Could not import batch", Map.of(
                            "statementCount", statementCount,
                            "message", StringUtils.defaultString(e.getMessage())));
                    throw e;
                }
                successCount += stored;
                reportImportProgress();
            }
            parameterRows = new ArrayList<>();
            rowNumbers = new ArrayList<>();
        }

        private void reportImportProgress() {
            progressReporter.report(consumedRows);
        }
    }

}
