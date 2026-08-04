package ai.chat2db.plugin.mongodb;

import ai.chat2db.spi.constant.SQLConstants;

import java.util.List;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Lists;

import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.QueryResponse;
import ai.chat2db.community.domain.api.model.result.ResultOperation;
import lombok.extern.slf4j.Slf4j;


import static ai.chat2db.plugin.mongodb.constant.MongodbSqlBuilderConstants.*;
@Slf4j
public class MongodbSqlBuilder extends DefaultSqlBuilder {













    private static final MongodbSqlBuilder INSTANCE = new MongodbSqlBuilder();

    public static MongodbSqlBuilder getInstance() {
        return INSTANCE;
    }

    public MongodbSqlBuilder() {

    }

    @Override
    public String buildByQueryResult(QueryResponse queryResult) {
        List<Header> headerList = queryResult.getHeaderList();
        List<ResultOperation> operationList = queryResult.getOperations();
        String tableName = queryResult.getTableName();
        if (StringUtils.isEmpty(tableName) || CollectionUtils.isEmpty(operationList)) {
            return StringUtils.EMPTY;
        }
        List<String> operateSqlCommands = Lists.newArrayList();
        for (ResultOperation operation : operationList) {
            String operationType = operation.getType();
            if (StringUtils.equals(operationType, SQLConstants.CREATE_KEYWORD)) {
                String insertSql = buildInsertSql(tableName, headerList, operation);
                operateSqlCommands.add(insertSql);
            } else if (StringUtils.equals(operationType, SQLConstants.UPDATE_KEYWORD)) {
                String updateSql = buildUpdate(tableName, headerList, operation);
                operateSqlCommands.add(updateSql);
            } else if (StringUtils.equals(operationType, SQLConstants.DELETE_KEYWORD)) {
                String deleteSql = buildDeleteSql(tableName, operateSqlCommands, operation);
                operateSqlCommands.add(deleteSql);
            } else {
                log.error(LOG_UNSUPPORTED_OPERATION_TYPE, operationType);
            }
        }
        return StringUtils.join(operateSqlCommands, SQLConstants.SEMICOLON);
    }

    private String buildDeleteSql(String tableName, List<String> deleteSqlCommands, ResultOperation operation) {
        List<String> oldDataList = operation.getOldDataList();
        if (CollectionUtils.isEmpty(oldDataList)) {
            log.warn(ERROR_DELETE_OLD_DATA_LIST_EMPTY);
            return StringUtils.EMPTY;
        }
        String idValue = oldDataList.get(1);
        if (StringUtils.isEmpty(idValue)) {
            return StringUtils.EMPTY;
        }
        String sql = String.format(SQL_DB_DOT_FORMAT_DOT_DELETEONE_OPEN_PAREN_OPEN_BRACE,
            MongodbSqlGuards.collectionAccessor(tableName), MongodbSqlGuards.escapeJsonString(idValue));
        log.info(LOG_DELETE_SQL, sql);
        return sql;

    }


    private String buildInsertSql(String tableName, List<Header> headerList, ResultOperation operation) {
        List<String> newDataList = operation.getDataList();
        if (CollectionUtils.isEmpty(newDataList)) {
            return StringUtils.EMPTY;
        }
        StringBuffer sql = new StringBuffer();
        for (int i = 2; i < newDataList.size(); i++) {
            Header header = headerList.get(i);
            String newValue = newDataList.get(i);
            sql.append(MongodbSqlGuards.quoteFieldName(header.getName())).append(SQLConstants.COLON)
                .append(MongodbSqlGuards.quoteJsonString(newValue)).append(SQLConstants.COMMA);
        }
        if (sql.isEmpty()) {
            return StringUtils.EMPTY;
        }
        StringBuffer insertSql = new StringBuffer();
        insertSql.append(String.format(SQL_DB_DOT_FORMAT_DOT_INSERTONE, MongodbSqlGuards.collectionAccessor(tableName))).append(SQLConstants.OPEN_PARENTHESIS)
            .append(SQLConstants.OPEN_CURLY_BRACE)
            .append(sql.deleteCharAt(sql.length() - 1))
            .append(SQLConstants.CLOSE_CURLY_BRACE)
            .append(SQLConstants.CLOSE_PARENTHESIS);
        log.info(LOG_INSERT_SQL, insertSql.toString());
        return insertSql.toString();

    }


    private String buildUpdate(String tableName, List<Header> headerList, ResultOperation operation) {
        List<String> oldDataList = operation.getOldDataList();
        List<String> newDataList = operation.getDataList();
        StringBuffer setSql = new StringBuffer();
        StringBuffer _idValue = new StringBuffer();
        for (int i = 1; i < headerList.size(); i++) {
            Header header = headerList.get(i);
            String newValue = newDataList.get(i);
            String oldValue = oldDataList.get(i);
            if (StringUtils.equals(newValue, oldValue)) {
                continue;
            }
            if (_idValue.isEmpty()) {
                _idValue.append(oldDataList.get(1));
            }
            setSql.append(MongodbSqlGuards.quoteFieldName(header.getName())).append(SQLConstants.COLON)
                .append(MongodbSqlGuards.quoteJsonString(newValue)).append(SQLConstants.COMMA);
        }
        if (_idValue.isEmpty() || setSql.isEmpty()) {
            return StringUtils.EMPTY;
        }
        StringBuffer sql = new StringBuffer();
        sql.append(String.format(SQL_DB_DOT_FORMAT_DOT_UPDATEONE, MongodbSqlGuards.collectionAccessor(tableName))).append(SQLConstants.OPEN_PARENTHESIS)
            .append(SQLConstants.OPEN_CURLY_BRACE)
            .append(MONGODB_ID_FIELD)
            .append(SQLConstants.COLON)
            .append(MONGODB_OBJECT_ID_TYPE)
            .append(SQLConstants.OPEN_PARENTHESIS)
            .append(SQLConstants.DOUBLE_QUOTE)
            .append(MongodbSqlGuards.escapeJsonString(_idValue.toString()))
            .append(SQLConstants.DOUBLE_QUOTE)
            .append(SQLConstants.CLOSE_PARENTHESIS)
            .append(SQLConstants.CLOSE_CURLY_BRACE)
            .append(SQLConstants.COMMA)
            .append(SQLConstants.OPEN_CURLY_BRACE)
            .append(MONGODB_SET_OPERATOR_PREFIX).append(SQLConstants.OPEN_CURLY_BRACE)
            .append(setSql.deleteCharAt(setSql.length() - 1)).append(SQLConstants.CLOSE_CURLY_BRACE)
            .append(SQLConstants.CLOSE_CURLY_BRACE)
            .append(SQLConstants.CLOSE_PARENTHESIS);
        log.info(LOG_UPDATE_SQL, sql.toString());
        return sql.toString();
    }
}
