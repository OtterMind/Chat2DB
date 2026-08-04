package ai.chat2db.community.domain.core.impl.task.export.json;

import ai.chat2db.community.domain.api.enums.ExportFileSuffixEnum;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.domain.core.impl.task.export.BaseExporter;
import ai.chat2db.community.domain.api.model.task.ExportAsyncContext;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import cn.hutool.core.date.DateUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.util.*;


@Slf4j
public class JsonDataExporter extends BaseExporter {

    public JsonDataExporter() {
        this.suffix = ExportFileSuffixEnum.JSON.getSuffix();
        this.contentType = "application/json";
    }


    @Override
    protected void singleExport(ExportAsyncContext asyncContext, String tableName, File file) {
        String querySql = getQuerySql(tableName);
        log.info("Start exporting table data as JSON: {}", tableName);
        Connection connection = Chat2DBContext.getConnection();
        asyncContext.info(String.format("Exporting data from table %s to %s", tableName, file.getAbsolutePath()));
        try (PrintWriter writer = new PrintWriter(file, StandardCharsets.UTF_8);) {
            writeJsonData(connection, querySql, writer,asyncContext);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private void writeJsonData(Connection connection, String querySql, PrintWriter writer,ExportAsyncContext asyncContext) {
        DefaultSQLExecutor.getInstance().execute(connection, querySql, BATCH_SIZE, resultSet -> {
            List<Map<String, Object>> dataBatch = new ArrayList<>();
            ResultSetMetaData metaData = resultSet.getMetaData();
            IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

            writer.println("[");
            boolean firstBatch = true;
            int n = 0;
            boolean hasNext = resultSet.next();
            while (hasNext) {
                asyncContext.checkCancelled();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    row.put(metaData.getColumnName(i), valueProcessor.getJdbcValue(new JDBCDataValue(resultSet, metaData, i, false)));
                }
                dataBatch.add(row);
                n++;
                hasNext = resultSet.next();
                if (dataBatch.size() >= BATCH_SIZE || !hasNext) {
                    if (!firstBatch) {
                        writer.println(",");
                    }
                    asyncContext.info(DateUtil.formatTime(new Date()) + ":" + String.format("Exported %d rows", n));
                    writeBatch(writer, objectMapper, dataBatch);
                    firstBatch = false;
                }
            }
            writer.println("]");
        }, asyncContext, asyncContext::checkCancelled);
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

}
