package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.core.impl.task.imports.BaseImporter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportSqlExecutor;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.metadata.data.ReadCellData;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.alibaba.excel.util.ConverterUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.*;


@Slf4j
public abstract class BaseExcelImporter extends BaseImporter {
    @Override
    protected void doImportData(ImportTaskSpec spec, TaskExecutionContext context, List<TableColumn> columns) {
        context.checkCancelled();
        ExcelTypeEnum excelType = getExcelType();
        NoModelDataListener noModelDataListener = new NoModelDataListener(spec, context, columns);
        runInTransaction(() -> {
            EasyExcel.read(new File(spec.getSourceFile()), noModelDataListener)
                    .excelType(excelType)
                    .sheet()
                    .headRowNumber(1)
                    .doRead();
            context.checkCancelled();
        });

    }

    protected abstract ExcelTypeEnum getExcelType();

    private void runInTransaction(Runnable importAction) {
        Connection connection = Chat2DBContext.getConnection();
        boolean autoCommit;
        Savepoint savepoint = null;
        try {
            autoCommit = connection.getAutoCommit();
            if (autoCommit) {
                connection.setAutoCommit(false);
            } else {
                savepoint = connection.setSavepoint();
            }
        } catch (SQLException e) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not start import transaction", e);
        }

        RuntimeException failure = null;
        boolean committed = false;
        try {
            importAction.run();
            if (savepoint != null) {
                connection.releaseSavepoint(savepoint);
            } else {
                connection.commit();
            }
            committed = true;
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } catch (Exception e) {
            failure = new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not import data file", e);
            throw failure;
        } finally {
            if (!committed) {
                rollbackImport(connection, savepoint, failure);
            }
            if (savepoint == null) {
                restoreAutoCommit(connection, failure);
            }
        }
    }

    private void rollbackImport(Connection connection, Savepoint savepoint, RuntimeException failure) {
        try {
            if (savepoint != null) {
                connection.rollback(savepoint);
            } else {
                connection.rollback();
            }
        } catch (Exception rollbackFailure) {
            if (failure != null) {
                failure.addSuppressed(rollbackFailure);
                return;
            }
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not roll back failed import", rollbackFailure);
        }
    }

    private void restoreAutoCommit(Connection connection, RuntimeException failure) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException restoreFailure) {
            if (failure != null) {
                failure.addSuppressed(restoreFailure);
                return;
            }
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not restore import connection", restoreFailure);
        }
    }


    public class NoModelDataListener extends AnalysisEventListener<Map<Integer, String>> {


        private final ImportTaskSpec spec;

        private final TaskExecutionContext taskContext;

        private final List<TableColumn> columns;

        private Map<String, Integer> headMap;

        private Map<String, Integer> mappedHeadMap;

        private List<TableColumn> tableColumns;

        private List<String> tableColumnList;

        private List<String> sqlList;

        private long successCount;

        private long skippedCount;

        private long totalRows;

        private static final int BATCH_SIZE = 1000;

        private final IValueProcessor valueProcessor;

        private final ConnectInfo connectInfo;

        private final ISqlBuilder sqlBuilder;

        private final ImportSqlExecutor sqlExecutor;

        public NoModelDataListener(ImportTaskSpec spec, TaskExecutionContext taskContext,
                List<TableColumn> columns) {
            this.spec = spec;
            this.columns = columns;
            this.taskContext = taskContext;
            this.valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
            this.connectInfo = Chat2DBContext.getConnectInfo();
            this.sqlBuilder = Chat2DBContext.getSqlBuilder();
            this.sqlExecutor = new ImportSqlExecutor(taskContext);
        }


        @Override
        public void invokeHead(Map<Integer, ReadCellData<?>> headMap, AnalysisContext context) {
            this.taskContext.checkCancelled();
            Map<Integer, String> map = ConverterUtils.convertToStringMap(headMap, context);
            this.headMap = invertMap(map);
            this.mappedHeadMap = mappedHeadMap();
            this.tableColumns = getTableColumns(columns, this.headMap);
        }

        private List<TableColumn> getTableColumns(List<TableColumn> columns, Map<String, Integer> headMap) {
            List<TableColumn> tableColumns = new ArrayList<>();
            this.tableColumnList = new ArrayList<>();
            for (TableColumn column : columns) {
                if (shouldInclude(column)) {
                    tableColumns.add(column);
                    this.tableColumnList.add(column.getName());
                }
            }
            return tableColumns;
        }

        private Map<String, Integer> invertMap(Map<Integer, String> map) {
            Map<String, Integer> out = new HashMap(map.size());
            Iterator it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, String> entry = (Map.Entry) it.next();
                if (entry.getValue() != null) {
                    out.put(entry.getValue().toUpperCase(Locale.ROOT), entry.getKey());
                }
            }
            return out;
        }


        @Override
        public void invoke(Map<Integer, String> data, AnalysisContext context) {
            this.taskContext.checkCancelled();
            if (data == null || data.isEmpty()) {
                skippedCount++;
                totalRows++;
                return;
            }
            List<String> values = getValueList(data);

            String sql = getInsertSql(values);

            if (StringUtils.isBlank(sql)) {
                skippedCount++;
                totalRows++;
                return;
            }
            if (sqlList == null) {
                sqlList = new ArrayList<>();
            }
            sqlList.add(sql);
            totalRows++;
            if (sqlList.size() >= BATCH_SIZE) {
                executeBatchInsert();
            } else {

            }
        }

        private List<String> getValueList(Map<Integer, String> data) {
            List<String> values = new ArrayList<>();
            for (TableColumn column : tableColumns) {
                Integer index = sourceIndex(column.getName());
                if (index == null) {
                    values.add(null);
                    continue;
                }
                String value = data.get(index);
                if (value == null) {
                    values.add(null);
                } else {
                    String stringValue = valueProcessor.getSqlValueString(getSQLDataValue(value, column));
                    values.add(stringValue);
                }
            }
            return values;
        }

        private Map<String, Integer> mappedHeadMap() {
            Map<String, Integer> mapped = new HashMap<>();
            if (spec.getMappings() == null) {
                return mapped;
            }
            for (Map<String, String> mapping : spec.getMappings()) {
                String source = mapping.get("sourceColumn");
                String target = mapping.get("targetColumn");
                Integer sourceIndex = headMap.get(source == null ? null : source.toUpperCase(Locale.ROOT));
                if (sourceIndex != null && StringUtils.isNotBlank(target)) {
                    mapped.put(target.toUpperCase(Locale.ROOT), sourceIndex);
                }
            }
            return mapped;
        }

        private Integer sourceIndex(String targetColumn) {
            String target = targetColumn.toUpperCase(Locale.ROOT);
            if (spec.getMappings() != null) {
                return mappedHeadMap.get(target);
            }
            return headMap.get(target);
        }

        private boolean shouldInclude(TableColumn column) {
            if (spec.getMappings() == null) {
                return sourceIndex(column.getName()) != null;
            }
            if (sourceIndex(column.getName()) != null) {
                return true;
            }
            return "NULL".equalsIgnoreCase(spec.getUnmappedTarget())
                    && !Boolean.TRUE.equals(column.getAutoIncrement());
        }

        private String getInsertSql(List<String> values) {
            return sqlBuilder.dml().buildInsert(SingleInsertSqlRequest.builder()
                    .databaseName(connectInfo.getDatabaseName())
                    .schemaName(connectInfo.getSchemaName())
                    .tableName(spec.getTarget().getTableName())
                    .columnList(this.tableColumnList)
                    .valueList(values)
                    .build());
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            this.taskContext.checkCancelled();
            executeBatchInsert();
            taskContext.logInfo("IMPORT_SUMMARY", "Data import completed", Map.of(
                    "totalRows", totalRows,
                    "successCount", successCount,
                    "failedCount", 0L,
                    "skippedCount", skippedCount));
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
                    taskContext.logError("IMPORT_BATCH_FAILED", "Could not import batch", Map.of(
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
