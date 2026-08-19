package ai.chat2db.community.domain.core.impl.task.imports.json;

import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.domain.core.impl.task.imports.BaseImporter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportSqlExecutor;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.imports.IImportStrategy;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.io.File;
import java.util.List;
import java.util.Objects;


@Slf4j
public class JSONImporter extends BaseImporter implements IImportStrategy {

    @Override
    protected void doImportData(ImportTaskSpec spec, TaskExecutionContext context, List<TableColumn> columns) {
        log.info("import JSON data file");
        List<String> sqlCacheList = new ArrayList<>(BATCH_SIZE);
        ObjectMapper objectMapper = new ObjectMapper();
        ISqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
        ImportSqlExecutor sqlExecutor = new ImportSqlExecutor(context);
        try (JsonParser parser = objectMapper.getFactory().createParser(new File(spec.getSourceFile()))) {
            context.checkCancelled();
            JsonToken token = parser.nextToken();
            if (token != JsonToken.START_ARRAY) {
                throw new BusinessException("jsonFile.parse.error");
            }

            token = parser.nextToken();
            if (token == JsonToken.END_ARRAY) {
                throw new BusinessException("jsonFile.parse.error");
            }
            while (token != JsonToken.END_ARRAY) {
                if (token == null) {
                    throw new BusinessException("jsonFile.parse.error");
                }
                context.checkCancelled();
                JsonNode recordNode = objectMapper.readTree(parser);
                List<String> tableColumnList = columns.stream().map(TableColumn::getName).toList();
                List<String> values = getValues(columns, spec.getDataTimeFormat(), recordNode, valueProcessor);
                String sql = sqlBuilder.dml().buildInsert(SingleInsertSqlRequest.builder()
                        .databaseName(connectInfo.getDatabaseName())
                        .schemaName(connectInfo.getSchemaName())
                        .tableName(spec.getTarget().getTableName())
                        .columnList(tableColumnList)
                        .valueList(values)
                        .build());
                sqlCacheList.add(sql);
                if (sqlCacheList.size() >= BATCH_SIZE) {
                    context.logInfo(TaskEventCode.BATCH_EXECUTED.name(),
                            "Importing " + BATCH_SIZE + " records");
                    sqlExecutor.executeBatch(sqlCacheList);
                    context.checkCancelled();
                    sqlCacheList = new ArrayList<>(BATCH_SIZE);
                }
                token = parser.nextToken();
            }
            if (sqlCacheList.size() > 0) {
                context.checkCancelled();
                sqlExecutor.executeBatch(sqlCacheList);
                context.checkCancelled();
            }
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("import JSON data error", e);
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not import JSON data", e);
        }

    }


    private List<String> getValues(List<TableColumn> fileColumns, String dataTimeFormat,
                                   JsonNode recordNode, IValueProcessor valueProcessor) {
        List<String> values = new ArrayList<>();
        for (TableColumn c : fileColumns) {
            JsonNode columnValueNode = recordNode.get(c.getName());
            if (Objects.isNull(columnValueNode)) {
                values.add(null);
            } else {
                SQLDataValue sqlDataValue = getSQLDataValue(columnValueNode.asText(), c);
                String value = valueProcessor.getSqlValueString(sqlDataValue);
                values.add(value);
            }
        }
        return values;
    }


}
