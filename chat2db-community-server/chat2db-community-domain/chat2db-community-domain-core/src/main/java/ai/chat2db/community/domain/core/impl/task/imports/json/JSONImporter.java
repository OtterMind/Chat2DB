package ai.chat2db.community.domain.core.impl.task.imports.json;

import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.domain.core.impl.task.imports.BaseImporter;
import ai.chat2db.community.domain.api.model.task.ImportAsyncContext;
import ai.chat2db.community.domain.core.impl.task.imports.IImportStrategy;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
import ai.chat2db.spi.DefaultSQLExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;


@Slf4j
public class JSONImporter extends BaseImporter implements IImportStrategy {

    @Override
    protected void doImportData(ImportAsyncContext context, List<TableColumn> columns) {
        log.info("import JSON data file");
        List<String> sqlCacheList = new ArrayList<>(BATCH_SIZE);
        int recordCount = 0;
        ObjectMapper objectMapper = new ObjectMapper();
        ISqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
        try {
            JsonNode jsonNode = objectMapper.readTree(context.getFile());
            Iterator<JsonNode> records = jsonNode.elements();

            while (records.hasNext()) {
                context.checkCancelled();
                JsonNode recordNode = records.next();
                List<String> tableColumnList = columns.stream().map(TableColumn::getName).toList();
                List<String> values = getValues(columns, context.getDataTimeFormat(), recordNode, valueProcessor);
                String sql = sqlBuilder.dml().buildInsert(SingleInsertSqlRequest.builder()
                        .databaseName(connectInfo.getDatabaseName())
                        .schemaName(connectInfo.getSchemaName())
                        .tableName(context.getTableName())
                        .columnList(tableColumnList)
                        .valueList(values)
                        .build());
                sqlCacheList.add(sql);
                if (sqlCacheList.size() >= BATCH_SIZE) {
                    context.info("import " + BATCH_SIZE + " records");
                    DefaultSQLExecutor.getInstance().executeBatchInsert(
                            Chat2DBContext.getConnection(), sqlCacheList, context, context::checkCancelled);
                    context.checkCancelled();
                    sqlCacheList = new ArrayList<>(BATCH_SIZE);
                }
            }
            if (sqlCacheList.size() > 0) {
                context.checkCancelled();
                DefaultSQLExecutor.getInstance().executeBatchInsert(
                        Chat2DBContext.getConnection(), sqlCacheList, context, context::checkCancelled);
                context.checkCancelled();
            }
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.error("import JSON data error", e);
            context.error("import JSON data error, " + e.getMessage());
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


    @NotNull
    private JsonNode getJsonNode(String rootNodeName, JsonNode jsonNode) {
        if (StringUtils.isNotBlank(rootNodeName) && jsonNode.has(rootNodeName)) {
            jsonNode = jsonNode.get(rootNodeName);
        }
        if (!jsonNode.isArray() || jsonNode.size() <= 0) {
            throw new BusinessException("jsonFile.parse.error");
        }
        return jsonNode;
    }

}
