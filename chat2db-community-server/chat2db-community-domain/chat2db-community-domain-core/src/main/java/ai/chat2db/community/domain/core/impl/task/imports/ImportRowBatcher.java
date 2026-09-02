package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.metadata.DataType;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.imports.ImportColumnResolver.Resolution;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns file rows into buffered {@code INSERT} statements and executes them in JDBC batches.
 * With {@code onError=SKIP} a failing row is retried individually and recorded in a
 * {@code REJECT}-role NDJSON sub-artifact instead of aborting the task.
 */
@Slf4j
public final class ImportRowBatcher implements AutoCloseable {

    private static final int BATCH_ROWS = 500;

    private static final String ON_ERROR_SKIP = "SKIP";

    private static final String REJECT_ROLE = "REJECT";

    private final ImportTaskSpec spec;

    private final TaskExecutionContext context;

    private final Resolution resolution;

    private final ImportOptions options;

    private final IValueProcessor valueProcessor;

    private final ISqlBuilder sqlBuilder;

    private final ConnectInfo connectInfo;

    private final ImportSqlExecutor sqlExecutor;

    private final List<String> bufferedSqls = new ArrayList<>(BATCH_ROWS);

    private final List<String> bufferedRows = new ArrayList<>(BATCH_ROWS);

    private final List<Long> bufferedRowNumbers = new ArrayList<>(BATCH_ROWS);

    private long importedRows;

    private long rejectedRows;

    private BufferedWriter rejectWriter;

    public ImportRowBatcher(ImportTaskSpec spec, TaskExecutionContext context, Resolution resolution,
            IValueProcessor valueProcessor) {
        this.spec = spec;
        this.context = context;
        this.resolution = resolution;
        this.options = spec.getOptions() == null ? new ImportOptions() : spec.getOptions();
        this.valueProcessor = valueProcessor;
        this.sqlBuilder = ai.chat2db.spi.sql.Chat2DBContext.getSqlBuilder();
        this.connectInfo = ai.chat2db.spi.sql.Chat2DBContext.getConnectInfo();
        this.sqlExecutor = new ImportSqlExecutor(context);
    }

    public void accept(long fileRowNumber, List<String> fileValues) {
        context.checkCancelled();
        String sql;
        String raw;
        try {
            sql = buildInsert(fileValues);
            raw = JSON.toJSONString(fileValues);
        } catch (RuntimeException conversionFailure) {
            handleFailedRow(fileRowNumber, fileValues, conversionFailure);
            return;
        }
        bufferedSqls.add(sql);
        bufferedRows.add(raw);
        bufferedRowNumbers.add(fileRowNumber);
        if (bufferedSqls.size() >= BATCH_ROWS) {
            flush();
        }
    }

    public long importedRows() {
        return importedRows;
    }

    public long rejectedRows() {
        return rejectedRows;
    }

    /**
     * Executes whatever is buffered; called at end of stream and whenever the caller needs a sync
     * point.
     */
    public void flush() {
        context.checkCancelled();
        if (bufferedSqls.isEmpty()) {
            return;
        }
        List<String> sqls = new ArrayList<>(bufferedSqls);
        List<String> rows = new ArrayList<>(bufferedRows);
        List<Long> rowNumbers = new ArrayList<>(bufferedRowNumbers);
        try {
            sqlExecutor.executeBatch(sqls);
            importedRows += sqls.size();
        } catch (RuntimeException batchFailure) {
            if (!isSkipMode()) {
                throw batchFailure;
            }
            replayIndividually(sqls, rows, rowNumbers);
        } finally {
            bufferedSqls.clear();
            bufferedRows.clear();
            bufferedRowNumbers.clear();
        }
    }

    private void replayIndividually(List<String> sqls, List<String> rows, List<Long> rowNumbers) {
        for (int index = 0; index < sqls.size(); index++) {
            try {
                sqlExecutor.executeBatch(List.of(sqls.get(index)));
                importedRows++;
            } catch (RuntimeException rowFailure) {
                handleRejectedRow(rowNumbers.get(index), rows.get(index), rootMessage(rowFailure));
            }
        }
    }

    private void handleFailedRow(long fileRowNumber, List<String> fileValues, RuntimeException failure) {
        handleRejectedRow(fileRowNumber, JSON.toJSONString(fileValues), rootMessage(failure));
    }

    private void handleFailedRowText(String rawRow, RuntimeException failure) {
        handleRejectedRow(null, rawRow, rootMessage(failure));
    }

    private void handleRejectedRow(Long fileRowNumber, String rawRow, String reason) {
        if (!isSkipMode()) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Import row failed: " + reason);
        }
        rejectedRows++;
        Integer maxErrors = options.getMaxErrors();
        if (maxErrors != null && maxErrors >= 0 && rejectedRows > maxErrors) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Import aborted after " + rejectedRows + " rejected rows");
        }
        try {
            rejectWriter().write(JSON.toJSONString(Map.of(
                    "row", fileRowNumber == null ? -1L : fileRowNumber,
                    "line", rawRow,
                    "reason", reason == null ? "unknown" : reason)));
            rejectWriter().write("\n");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write reject file", e);
        }
        context.logWarn("IMPORT_ROW_REJECTED", "Import row rejected: " + reason,
                Map.of("rejectedRows", rejectedRows));
    }

    private BufferedWriter rejectWriter() throws IOException {
        if (rejectWriter == null) {
            String fileName = StringUtils.firstNonBlank(
                    new java.io.File(StringUtils.defaultString(spec.getSourceFile())).getName(), "import")
                    + ".rejects.ndjson";
            var draft = context.createArtifact(REJECT_ROLE,
                    StringUtils.substringBeforeLast(spec.getSourceFile(), java.io.File.separator),
                    fileName, "application/x-ndjson");
            rejectWriter = Files.newBufferedWriter(draft.getTemporaryFile().toPath(), StandardCharsets.UTF_8);
        }
        return rejectWriter;
    }

    private String buildInsert(List<String> fileValues) {
        List<String> tableColumnNames = new ArrayList<>(resolution.tableColumns().size());
        List<String> values = new ArrayList<>(resolution.tableColumns().size());
        for (int index = 0; index < resolution.tableColumns().size(); index++) {
            TableColumn column = resolution.tableColumns().get(index);
            String raw = resolution.fileIndexes().get(index) < fileValues.size()
                    ? fileValues.get(resolution.fileIndexes().get(index)) : null;
            tableColumnNames.add(column.getName());
            values.add(toSqlLiteral(column, raw));
        }
        return sqlBuilder.dml().buildInsert(SingleInsertSqlRequest.builder()
                .databaseName(connectInfo.getDatabaseName())
                .schemaName(connectInfo.getSchemaName())
                .tableName(spec.getTarget().getTableName())
                .columnList(tableColumnNames)
                .valueList(values)
                .build());
    }

    private String toSqlLiteral(TableColumn column, String raw) {
        if (raw == null || (options.getNullString() != null && options.getNullString().equals(raw))) {
            return null;
        }
        if (raw.isEmpty()) {
            return null;
        }
        DataType dataType = new DataType();
        dataType.setDataTypeName(column.getColumnType());
        dataType.setScale(column.getDecimalDigits());
        dataType.setPrecision(column.getColumnSize());
        SQLDataValue sqlDataValue = new SQLDataValue();
        sqlDataValue.setDataType(dataType);
        sqlDataValue.setValue(raw);
        return valueProcessor.getSqlValueString(sqlDataValue);
    }

    private boolean isSkipMode() {
        return ON_ERROR_SKIP.equalsIgnoreCase(StringUtils.trimToEmpty(options.getOnError()));
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    @Override
    public void close() {
        flush();
        if (rejectWriter != null) {
            try {
                rejectWriter.flush();
                rejectWriter.close();
            } catch (IOException e) {
                log.warn("Could not close import reject writer", e);
            }
        }
    }
}
