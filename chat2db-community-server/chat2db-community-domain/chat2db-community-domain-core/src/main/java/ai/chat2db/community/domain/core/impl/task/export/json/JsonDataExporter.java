package ai.chat2db.community.domain.core.impl.task.export.json;

import ai.chat2db.community.domain.api.enums.ExportFileSuffixEnum;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.extension.ExportCell;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionPlan;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.core.impl.task.export.BaseExporter;
import ai.chat2db.community.domain.core.impl.task.export.ExportCellProcessorChain;
import ai.chat2db.community.domain.core.impl.task.export.ExportProgressLogger;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.util.*;


@Slf4j
@Component
public class JsonDataExporter extends BaseExporter {

    public JsonDataExporter(ExportCellProcessorChain exportCellProcessorChain,
            SqlExecutionPolicyManager sqlExecutionPolicyManager) {
        super(exportCellProcessorChain, sqlExecutionPolicyManager);
        this.suffix = ExportFileSuffixEnum.JSON.getSuffix();
        this.contentType = "application/json";
    }

    @Override
    public String type() {
        return "json";
    }


    @Override
    protected void singleExport(ExportTaskSpec spec, TaskExecutionContext context, String tableName, File file) {
        SqlExecutionPlan executionPlan = getQueryPlan(spec, tableName);
        log.info("Start exporting table data as JSON: {}", tableName);
        Connection connection = Chat2DBContext.getConnection();
        ExportProgressLogger progressLogger = new ExportProgressLogger(context, "JSON", tableName);
        progressLogger.queryStarted("Reading table data from " + tableName);
        try (PrintWriter writer = new PrintWriter(file, StandardCharsets.UTF_8)) {
            writeJsonData(connection, executionPlan, writer, tableName, spec, context, progressLogger);
            progressLogger.queryCompleted("Table data read completed");
            progressLogger.fileFinalizing();
            requireSuccessfulWrite(writer);
        } catch (IOException e) {
            throw new TaskExecutionException(TaskErrorCode.FILE_WRITE_FAILED.name(),
                    "Could not write JSON export", e);
        }
    }


    private void writeJsonData(Connection connection, SqlExecutionPlan executionPlan, PrintWriter writer,
            String tableName, ExportTaskSpec spec, TaskExecutionContext context,
            ExportProgressLogger progressLogger) {
        DefaultSQLExecutor.getInstance().execute(connection, executionPlan.getSql(), BATCH_SIZE, resultSet -> {
            List<Map<String, Object>> dataBatch = new ArrayList<>();
            ResultSetMetaData metaData = resultSet.getMetaData();
            List<Integer> includedColumnIndexes = includedColumnIndexes(metaData, executionPlan);
            IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

            writer.println("[");
            boolean firstBatch = true;
            int exportedRows = 0;
            boolean hasNext = nextRow(resultSet, executionPlan, exportedRows);
            while (hasNext) {
                context.checkCancelled();
                Map<String, Object> row = new LinkedHashMap<>();
                for (Integer columnIndex : includedColumnIndexes) {
                    JDBCDataValue jdbcDataValue = new JDBCDataValue(resultSet, metaData, columnIndex, false);
                    Object value;
                    if (hasExportCellProcessors()) {
                        ExportCell cell = processJdbcCell(spec, metaData, columnIndex, tableName, jdbcDataValue);
                        value = cell.getValue();
                    } else {
                        value = valueProcessor.getJdbcValue(jdbcDataValue);
                    }
                    row.put(metaData.getColumnName(columnIndex), value);
                }
                dataBatch.add(row);
                progressLogger.recordExportedRow();
                exportedRows++;
                hasNext = nextRow(resultSet, executionPlan, exportedRows);
                if (dataBatch.size() >= BATCH_SIZE || !hasNext) {
                    if (!firstBatch) {
                        writer.println(",");
                    }
                    writeBatch(writer, objectMapper, dataBatch);
                    firstBatch = false;
                }
            }
            writer.println("]");
        }, context, context::checkCancelled);
    }

    private void writeBatch(PrintWriter writer, ObjectMapper objectMapper, List<Map<String, Object>> dataBatch) {
        try {
            String jsonBatch = objectMapper.writeValueAsString(dataBatch);
            writer.println(jsonBatch.substring(1, jsonBatch.length() - 1));
            writer.flush();
            dataBatch.clear();
        } catch (JsonProcessingException e) {
            throw new BusinessException("data.export.json.error", null, e);
        }
    }

    private void requireSuccessfulWrite(PrintWriter writer) {
        writer.flush();
        if (writer.checkError()) {
            throw new TaskExecutionException(TaskErrorCode.FILE_WRITE_FAILED.name(),
                    "Could not write JSON export");
        }
    }

}
