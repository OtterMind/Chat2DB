package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.core.impl.task.imports.BaseImporter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportSqlExecutor;
import ai.chat2db.community.domain.core.impl.task.imports.ImportRowSqlBuilder;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
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
        NoModelDataListener listener = new NoModelDataListener(spec, context, columns);
        ExcelImportReader.read(new File(spec.getSourceFile()), options, Integer.MAX_VALUE,
                CSVImporter.mappedSourceColumnCount(spec), listener::acceptHead, listener::acceptCells,
                context::checkCancelled);
        listener.finish();
        context.checkCancelled();
    }

    public class NoModelDataListener {

        private final TaskExecutionContext taskContext;

        private List<String> sqlList;

        private long successCount;

        private long skippedCount;

        private static final int BATCH_SIZE = 1000;

        private final ImportSqlExecutor sqlExecutor;

        private final ImportRowSqlBuilder rowSqlBuilder;

        public NoModelDataListener(ImportTaskSpec spec, TaskExecutionContext taskContext,
                List<TableColumn> columns) {
            this.taskContext = taskContext;
            this.sqlExecutor = new ImportSqlExecutor(taskContext);
            this.rowSqlBuilder = new ImportRowSqlBuilder(spec, columns);
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
            if (data == null || data.isEmpty()) {
                skippedCount++;
                return;
            }
            String sql = rowSqlBuilder.buildCells(data, sourceRowNumber);

            if (StringUtils.isBlank(sql)) {
                skippedCount++;
                return;
            }
            if (sqlList == null) {
                sqlList = new ArrayList<>();
            }
            sqlList.add(sql);
            if (sqlList.size() >= BATCH_SIZE) {
                executeBatchInsert();
            } else {

            }
        }

        void finish() {
            this.taskContext.checkCancelled();
            executeBatchInsert();
        }

        private void executeBatchInsert() {
            taskContext.checkCancelled();
            if (sqlList != null && !sqlList.isEmpty()) {
                taskContext.logInfo(TaskEventCode.BATCH_EXECUTED.name(),
                        String.format("Executing batch insert: %s", sqlList.size()));
                int statementCount = sqlList.size();
                try {
                    sqlExecutor.executeBatch(sqlList);
                    successCount += statementCount;
                    reportImportProgress();
                } catch (TaskCancelledException e) {
                    throw e;
                } catch (Exception e) {
                    taskContext.logError(TaskEventCode.IMPORT_BATCH_FAILED.name(), "Could not import batch", Map.of(
                            "statementCount", statementCount,
                            "message", StringUtils.defaultString(e.getMessage())));
                    throw e;
                }
            }
            sqlList = new ArrayList<>();
        }

        private void reportImportProgress() {
            long processedRows = successCount + skippedCount;
            int progress = (int) Math.min(TaskConstants.MAX_RUNNING_PROGRESS,
                    20 + Math.min(70, processedRows / 100));
            taskContext.reportProgress(progress, TaskStage.IMPORTING.name(),
                    String.format("Imported %s rows", successCount));
        }
    }

}
