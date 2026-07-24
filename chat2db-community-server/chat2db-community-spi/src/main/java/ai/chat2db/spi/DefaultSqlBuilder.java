package ai.chat2db.spi;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.community.tools.util.EasyStringUtils;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.constant.SqlValueConstants;
import ai.chat2db.community.domain.api.enums.plugin.DmlTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.IndexTypeEnum;
import ai.chat2db.community.domain.api.enums.value.LargeValueTypeEnum;
import ai.chat2db.community.domain.api.model.account.*;
import ai.chat2db.community.domain.api.model.async.*;
import ai.chat2db.community.domain.api.config.*;
import ai.chat2db.spi.model.datasource.*;
import ai.chat2db.community.domain.api.model.form.*;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.community.domain.api.model.sql.*;
import ai.chat2db.spi.model.value.*;
import ai.chat2db.community.domain.api.model.view.*;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.spi.model.request.*;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.builder.IDatabaseSqlBuilder;
import ai.chat2db.spi.sql.builder.IDdlSqlBuilder;
import ai.chat2db.spi.sql.builder.IDmlSqlBuilder;
import ai.chat2db.spi.sql.builder.IDqlSqlBuilder;
import ai.chat2db.spi.sql.builder.IIdentifierSqlBuilder;
import ai.chat2db.spi.sql.builder.ISchemaSqlBuilder;
import ai.chat2db.spi.sql.builder.ITableSqlBuilder;
import ai.chat2db.spi.sql.builder.IViewSqlBuilder;
import ai.chat2db.spi.util.DBStructUtils;
import ai.chat2db.spi.util.SqlUtils;
import ai.chat2db.spi.util.SqlStringUtil;
import com.google.common.collect.Lists;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static ai.chat2db.spi.constant.DefaultSqlBuilderConstants.*;

public class DefaultSqlBuilder implements ISqlBuilder, IIdentifierSqlBuilder, IDqlSqlBuilder, IDmlSqlBuilder,
        IDdlSqlBuilder, IDatabaseSqlBuilder, ISchemaSqlBuilder, ITableSqlBuilder, IViewSqlBuilder {

    @Override
    public IIdentifierSqlBuilder identifier() {
        return this;
    }

    @Override
    public IDqlSqlBuilder dql() {
        return this;
    }

    @Override
    public IDmlSqlBuilder dml() {
        return this;
    }

    @Override
    public IDdlSqlBuilder ddl() {
        return this;
    }

    @Override
    public IDatabaseSqlBuilder database() {
        return this;
    }

    @Override
    public ISchemaSqlBuilder schema() {
        return this;
    }

    @Override
    public ITableSqlBuilder table() {
        return this;
    }

    @Override
    public IViewSqlBuilder view() {
        return this;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return identifier;
    }

    @Override
    public String quoteQualifiedIdentifier(String... identifiers) {
        return Arrays.stream(identifiers)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining(SQLConstants.DOT));
    }

    @Override
    public String quoteAlias(String alias) {
        return quoteIdentifier(alias);
    }

    @Override
    public String buildSelectTable(String databaseName, String schemaName, String tableName) {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append(SQL_SELECT_2);
        buildTableName(databaseName, schemaName, tableName, sqlBuilder);
        return sqlBuilder.toString();
    }

    @Override
    public String buildSelectCount(String databaseName, String schemaName, String tableName) {
        return SQL_SELECT_COUNT_FROM + quoteQualifiedIdentifier(databaseName, schemaName, tableName);
    }

    public String buildDefaultCreateColumnSql(TableColumn column) {
        StringBuilder script = new StringBuilder();
        script.append(SQLConstants.BACK_QUOTE).append(column.getName()).append(SQLConstants.BACK_QUOTE).append(SQLConstants.SPACE);
        script.append(column.getColumnType()).append(SQLConstants.SPACE);
        return script.toString();
    }

    @Override
    public String buildCreateTable(Table table, TableBuilderConfig tableBuilderConfig) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_CREATE_TABLE);
        script.append(SQLConstants.DOUBLE_QUOTE).append(table.getName()).append(SQLConstants.DOUBLE_QUOTE).append(SQLConstants.SPACE_OPEN_PARENTHESIS).append(SQLConstants.LINE_SEPARATOR);

        List<String> comments = new ArrayList<>();
        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            script.append(VALUE_DOUBLE_QUOTE).append(column.getName()).append(VALUE_DOUBLE_QUOTE_2).append(column.getColumnType());
            if (column.getColumnSize() != null) {
                script.append(SQLConstants.OPEN_PARENTHESIS).append(column.getColumnSize());
                if (column.getDecimalDigits() != null) {
                    script.append(SQLConstants.COMMA).append(column.getDecimalDigits());
                }
                script.append(SQLConstants.CLOSE_PARENTHESIS);
            }

            if (column.getNullable() != null && 1 == column.getNullable()) {
                script.append(SQLConstants.NULL_SQL);
            } else {
                script.append(SQLConstants.NOT_NULL_SQL_WITH_PREFIX);
            }
            if (StringUtils.isNotBlank(column.getComment())) {
                comments.add(generateCommentSql(column));
            }
            script.append(SQLConstants.COMMA_LINE_SEPARATOR);
        }
        script = new StringBuilder(script.substring(0, script.length() - 2));
        script.append(SQLConstants.LINE_SEPARATOR_CLOSE_PARENTHESIS);
        script.append(SQLConstants.SEMICOLON);
        if (CollectionUtils.isNotEmpty(comments)) {
            script.append(SQLConstants.LINE_SEPARATOR);
            comments.forEach(script::append);
        }
        if (CollectionUtils.isNotEmpty(table.getIndexList())) {
            String indexSql = generateIndexSql(table.getIndexList());
            script.append(indexSql);
        }
        return script.toString();
    }

    private String generateIndexSql(List<TableIndex> indexList) {
        StringBuilder script = new StringBuilder();
        for (TableIndex index : indexList) {
            List<TableIndexColumn> columnList = index.getColumnList();
            if (CollectionUtils.isEmpty(columnList)) {
                continue;
            }
            script.append(SQL_CREATE);
            if (IndexTypeEnum.UNIQUE.getName().equals(index.getType())) {
                script.append(SQLConstants.UNIQUE_SQL);
            }
            script.append(SQLConstants.INDEX_SQL);
            script.append(index.getName());
            script.append(SQL_ON);
            script.append(index.getTableName());
            script.append(SQLConstants.SPACE_OPEN_PARENTHESIS);
            boolean first = true;
            for (TableIndexColumn column : columnList) {
                if (!first) {
                    script.append(SQLConstants.COMMA);
                }
                script.append(column.getColumnName());
                first = false;
            }
            script.append(SQLConstants.CLOSE_PARENTHESIS_SEMICOLON_LINE_SEPARATOR);
        }
        return script.toString();
    }


    private String generateCommentSql(TableColumn column) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_COMMENT_COLUMN);
        script.append(column.getTableName() + SQLConstants.DOT + column.getName());
        script.append(SQLConstants.SQL_IS_SINGLE_QUOTE);
        script.append(column.getComment());
        script.append(SQLConstants.SINGLE_QUOTE_SEMICOLON_LINE_SEPARATOR);
        return script.toString();
    }

    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        return DBStructUtils.buildAlterTable(oldTable, newTable);
    }

    @Override
    public String buildDropTable(DropTableRequest request) {
        return SQLConstants.DROP_TABLE_SQL_PREFIX + quoteQualifiedIdentifier(
                request.getDatabaseName(), request.getSchemaName(), request.getTableName());
    }

    @Override
    public String buildTruncateTable(TruncateTableRequest request) {
        return SQLConstants.TRUNCATE_TABLE_SQL_PREFIX + quoteQualifiedIdentifier(
                request.getDatabaseName(), request.getSchemaName(), request.getTableName());
    }

    @Override
    public String buildPageLimit(PageLimitRequest request) {
        String sql = request.getSql();
        int offset = request.getOffset();
        int pageSize = request.getPageSize();
        StringBuilder sqlBuilder = new StringBuilder(sql.length() + 14);
        sqlBuilder.append(sql);
        if (offset == 0) {
            sqlBuilder.append(SQLConstants.LINE_SEPARATOR_LIMIT_SQL);
            sqlBuilder.append(pageSize);
        } else {
            sqlBuilder.append(SQLConstants.LINE_SEPARATOR_LIMIT_SQL);
            sqlBuilder.append(offset);
            sqlBuilder.append(SQLConstants.COMMA);
            sqlBuilder.append(pageSize);
        }
        return sqlBuilder.toString();
    }

    @Override
    public String buildCreateDatabase(Database database) {
        return SQLConstants.CREATE_DATABASE_SQL_PREFIX + database.getName();
    }

    @Override
    public String buildAlterDatabase(Database oldDatabase, Database newDatabase) {
        throw unsupported(METHOD_BUILD_ALTER_DATABASE);
    }

    @Override
    public String buildDropDatabase(String databaseName) {
        return SQLConstants.DROP_DATABASE_SQL_PREFIX + quoteIdentifier(databaseName);
    }

    @Override
    public String buildUseDatabase(String databaseName) {
        return SQLConstants.USE_DATABASE_SQL_PREFIX + quoteIdentifier(databaseName);
    }

    @Override
    public String buildCreateSchema(Schema schema) {
        return SQLConstants.CREATE_SCHEMA_SQL_PREFIX + schema.getName();
    }

    @Override
    public String buildAlterSchema(String oldSchemaName, String newSchemaName) {
        throw unsupported(METHOD_BUILD_ALTER_SCHEMA);
    }

    @Override
    public String buildDropSchema(String schemaName) {
        return SQL_DROP_SCHEMA_PREFIX + quoteIdentifier(schemaName);
    }

    @Override
    public String buildOrderBy(String originSql, List<OrderBy> orderByList) {
        if (CollectionUtils.isEmpty(orderByList)) {
            return originSql;
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(originSql);
            if (statement instanceof Select) {
                Select selectStatement = (Select) statement;
                PlainSelect plainSelect = (PlainSelect) selectStatement.getSelectBody();
                List<OrderByElement> orderByElements = new ArrayList<>();

                for (OrderBy orderBy : orderByList) {
                    OrderByElement orderByElement = new OrderByElement();
                    orderByElement.setExpression(CCJSqlParserUtil.parseExpression(orderBy.getColumnName()));
                    orderByElement.setAsc(orderBy.isAsc());
                    orderByElements.add(orderByElement);
                }
                plainSelect.setOrderByElements(orderByElements);
                return plainSelect.toString();
            }
        } catch (Exception e) {
            throw new BusinessException(ERROR_KEY_SQL_BUILDER_ORDER_BY_FAILED, new Object[]{originSql}, e);
        }
        return originSql;
    }

    @Override
    public String buildByQueryResult(QueryResponse queryResult) {
        List<Header> headerList = queryResult.getHeaderList();
        List<ResultOperation> operations = queryResult.getOperations();
        String tableName = queryResult.getTableName();
        StringBuilder stringBuilder = new StringBuilder();
        IDbMetaData metaSchema = Chat2DBContext.getDbMetaData();
        IValueProcessor valueProcessor = metaSchema.getValueProcessor();
        List<String> keyColumns = getPrimaryColumns(headerList);
        boolean needSingleRowLimit = CollectionUtils.isEmpty(keyColumns);
        for (int i = 0; i < operations.size(); i++) {
            ResultOperation operation = operations.get(i);
            List<String> row = operation.getDataList();
            List<String> odlRow = operation.getOldDataList();
            String sql = SQLConstants.EMPTY;
            if (SQLConstants.UPDATE_KEYWORD.equalsIgnoreCase(operation.getType())) {
                sql = getUpdateSql(tableName, headerList, row, odlRow, metaSchema, keyColumns, false);
                if (needSingleRowLimit && StringUtils.isNotBlank(sql)) {
                    String whereClause = buildWhere(headerList, odlRow, metaSchema, keyColumns, valueProcessor);
                    sql = appendSingleRowLimit(SQLConstants.UPDATE_KEYWORD, tableName, whereClause, sql);
                }
            } else if (SQLConstants.CREATE_KEYWORD.equalsIgnoreCase(operation.getType())) {
                sql = getInsertSql(tableName, headerList, row, metaSchema);
            } else if (SQLConstants.DELETE_KEYWORD.equalsIgnoreCase(operation.getType())) {
                sql = getDeleteSql(tableName, headerList, odlRow, metaSchema, keyColumns);
                if (needSingleRowLimit && StringUtils.isNotBlank(sql)) {
                    String whereClause = buildWhere(headerList, odlRow, metaSchema, keyColumns, valueProcessor);
                    sql = appendSingleRowLimit(SQLConstants.DELETE_KEYWORD, tableName, whereClause, sql);
                }
            } else if (OPERATION_TYPE_UPDATE_COPY.equalsIgnoreCase(operation.getType())) {
                sql = getUpdateSql(tableName, headerList, row, row, metaSchema, keyColumns, true);
            }
            stringBuilder.append(sql + SQLConstants.SEMICOLON_LINE_SEPARATOR);
        }
        return stringBuilder.toString();
    }


    protected String appendSingleRowLimit(String operationType, String tableName, String whereClause, String sql) {
        return sql;
    }

    @Override
    public String buildTemplate(Table table, String type) {
        if (table == null || CollectionUtils.isEmpty(table.getColumnList()) || StringUtils.isBlank(type)) {
            return SQLConstants.EMPTY;
        }
        if (DmlTypeEnum.INSERT.name().equalsIgnoreCase(type)) {
            return getInsertSql(table.getName(), table.getColumnList());
        } else if (DmlTypeEnum.UPDATE.name().equalsIgnoreCase(type)) {
            return getUpdateSql(table.getName(), table.getColumnList());
        } else if (DmlTypeEnum.DELETE.name().equalsIgnoreCase(type)) {
            return getDeleteSql(table.getName(), table.getColumnList());
        } else if (DmlTypeEnum.SELECT.name().equalsIgnoreCase(type)) {
            return getSelectSql(table.getName(), table.getColumnList());
        }
        return SQLConstants.EMPTY;
    }

    private String getSelectSql(String name, List<TableColumn> columnList) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_SELECT);
        for (TableColumn column : columnList) {
            script.append(column.getName())
                    .append(SQLConstants.COMMA);
        }
        script.deleteCharAt(script.length() - 1);
        script.append(SQL_FROM_WHERE).append(name);
        return script.toString();
    }

    private String getDeleteSql(String name, List<TableColumn> columnList) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_DELETE).append(name)
                .append(SQL_WHERE);
        return script.toString();
    }

    private String getUpdateSql(String name, List<TableColumn> columnList) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_UPDATE).append(name)
                .append(SQL_SET);
        for (TableColumn column : columnList) {
            script.append(column.getName())
                    .append(SQLConstants.EQUAL_SQL)
                    .append(SQLConstants.SPACE)
                    .append(SQLConstants.COMMA);
        }
        script.deleteCharAt(script.length() - 1);
        script.append(SQL_WHERE);
        return script.toString();
    }

    private String getInsertSql(String name, List<TableColumn> columnList) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_INSERT_INTO).append(name)
                .append(SQLConstants.SPACE_OPEN_PARENTHESIS);
        for (TableColumn column : columnList) {
            script.append(column.getName())
                    .append(SQLConstants.COMMA);
        }
        script.deleteCharAt(script.length() - 1);
        script.append(VALUE_CLOSE_PAREN_VALUES_OPEN_PAREN);
        for (TableColumn column : columnList) {
            script.append(SQLConstants.SPACE)
                    .append(SQLConstants.COMMA);
        }
        script.deleteCharAt(script.length() - 1);
        script.append(SQLConstants.CLOSE_PARENTHESIS);
        return script.toString();
    }


    protected String buildBaseInsertSql(String databaseName, String schemaName, String tableName, List<String> columnList) {
        StringBuilder script = new StringBuilder();

        script.append(SQL_INSERT_INTO);

        buildTableName(databaseName, schemaName, tableName, script);

        buildColumns(columnList, script);

        script.append(SQL_VALUES);
        return script.toString();
    }

    protected void buildColumns(List<String> columnList, StringBuilder script) {
        if (CollectionUtils.isNotEmpty(columnList)) {
            script.append(SQLConstants.SPACE_OPEN_PARENTHESIS)
                    .append(String.join(SQLConstants.COMMA, columnList))
                    .append(SQLConstants.CLOSE_PARENTHESIS_SPACE);
        }
    }

    protected void buildTableName(String databaseName, String schemaName, String tableName, StringBuilder script) {
        if (StringUtils.isNotBlank(databaseName)) {
            script.append(databaseName).append('.');
        }
        if (StringUtils.isNotBlank(schemaName)) {
            script.append(schemaName).append('.');
        }

        script.append(tableName);
    }


    @Override
    public String buildInsert(SingleInsertSqlRequest request) {
        List<String> valueList = request.getValueList();
        String baseSql = buildBaseInsertSql(request.getDatabaseName(), request.getSchemaName(), request.getTableName(),
                request.getColumnList());
        List<String> list = valueList.stream().map(EasyStringUtils::escapeLineString).toList();
        return baseSql + SQLConstants.OPEN_PARENTHESIS + String.join(SQLConstants.COMMA, list) + SQLConstants.CLOSE_PARENTHESIS;
    }


    @Override
    public String buildBatchInsert(MultiInsertSqlRequest request) {
        List<List<String>> valueLists = request.getValueLists();
        String baseSql = buildBaseInsertSql(request.getDatabaseName(), request.getSchemaName(), request.getTableName(),
                request.getColumnList());
        String valuesPart = valueLists.stream()
                .map(values -> SQLConstants.OPEN_PARENTHESIS + String.join(SQLConstants.COMMA, values.stream().map(EasyStringUtils::escapeLineString).toList()) + SQLConstants.CLOSE_PARENTHESIS)
                .collect(Collectors.joining(SQLConstants.COMMA_LINE_SEPARATOR));
        return baseSql + valuesPart;
    }


    @Override
    public String buildUpdate(UpdateSqlRequest request) {
        String databaseName = request.getDatabaseName();
        String schemaName = request.getSchemaName();
        String tableName = request.getTableName();
        Map<String, String> row = request.getRow();
        Map<String, String> primaryKeyMap = request.getPrimaryKeyMap();
        StringBuilder script = new StringBuilder();
        script.append(SQL_UPDATE);
        buildTableName(databaseName, schemaName, tableName, script);

        script.append(SQL_SET_2);
        List<String> setClauses = row.entrySet().stream()
                .map(entry -> entry.getKey() + SQLConstants.EQUAL_SQL + entry.getValue())
                .collect(Collectors.toList());
        script.append(String.join(SQLConstants.COMMA, setClauses));

        if (MapUtils.isNotEmpty(primaryKeyMap)) {
            script.append(SQL_WHERE_2);
            List<String> whereClauses = primaryKeyMap.entrySet().stream()
                    .map(entry -> entry.getKey() + SQLConstants.EQUAL_SQL + entry.getValue())
                    .collect(Collectors.toList());
            script.append(String.join(SQLConstants.SQL_AND, whereClauses));
        }
        return script.toString();
    }

    @Override
    public String buildDelete(DeleteSqlRequest deleteSqlRequest) {
        throw unsupported(METHOD_BUILD_DELETE);
    }

    @Override
    public String buildCopyByQueryResult(QueryResponse queryResult) {
        List<Header> headerList = queryResult.getHeaderList();
        List<ResultOperation> operations = queryResult.getOperations();
        String tableName = queryResult.getTableName();
        StringBuilder stringBuilder = new StringBuilder();
        IDbMetaData metaSchema = Chat2DBContext.getDbMetaData();
        String dbType = Chat2DBContext.getDBConfig().getDbType();
        if (CollectionUtils.isNotEmpty(operations)) {
            ResultOperation operation = operations.get(0);
            if (SQLConstants.WHERE_KEYWORD.equalsIgnoreCase(operation.getType())) {
                return copyWhereSql(operations, headerList, metaSchema, dbType);
            }
        }
        for (int i = 0; i < operations.size(); i++) {
            ResultOperation operation = operations.get(i);
            String sql = SQLConstants.EMPTY;
            if (SQLConstants.CREATE_KEYWORD.equalsIgnoreCase(operation.getType())) {
                sql = copyInsertSql(tableName, headerList, operation, metaSchema, dbType);
            } else if (OPERATION_TYPE_UPDATE_COPY.equalsIgnoreCase(operation.getType())) {
                sql = copyUpdateSql(tableName, headerList, operation, metaSchema, dbType);
            }
            stringBuilder.append(sql + SQLConstants.SEMICOLON_LINE_SEPARATOR);
        }
        return stringBuilder.toString();

    }

    public String copyInValuesByQuery(QueryResponse queryResult) {
        if (queryResult == null || CollectionUtils.isEmpty(queryResult.getHeaderList())
                || CollectionUtils.isEmpty(queryResult.getOperations())) {
            throw new BusinessException(ERROR_KEY_COPY_IN_VALUES_EMPTY_INPUT);
        }
        return copyResultSetInValues(queryResult);
    }

    public String copyExternalTextInValues(List<String> externalValues) {
        if (CollectionUtils.isEmpty(externalValues)) {
            throw new BusinessException(ERROR_KEY_COPY_IN_VALUES_EMPTY_INPUT);
        }
        List<String> values = externalValues.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .map(EasyStringUtils::escapeAndQuoteString)
                .distinct()
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(values)) {
            throw new BusinessException(ERROR_KEY_COPY_IN_VALUES_EMPTY_INPUT);
        }
        return SQLConstants.OPEN_PARENTHESIS + String.join(SQLConstants.COMMA_SPACE, values) + SQLConstants.CLOSE_PARENTHESIS;
    }

    private String copyResultSetInValues(QueryResponse queryResult) {
        List<Header> headerList = queryResult.getHeaderList();
        List<ResultOperation> operations = queryResult.getOperations();
        if (CollectionUtils.isEmpty(headerList) || CollectionUtils.isEmpty(operations)) {
            throw new BusinessException(ERROR_KEY_COPY_IN_VALUES_EMPTY_INPUT);
        }

        IDbMetaData metaSchema = Chat2DBContext.getDbMetaData();
        IValueProcessor valueProcessor = metaSchema.getValueProcessor();
        List<SQLDataValue> dataTypes = header2SQLDataValue(headerList);
        Integer selectedColumnIndex = null;
        Set<String> values = new LinkedHashSet<>();

        for (ResultOperation operation : operations) {
            List<Integer> selectCols = operation.getSelectCols();
            if (CollectionUtils.size(selectCols) != 1) {
                throw new BusinessException(ERROR_KEY_COPY_IN_VALUES_SINGLE_COLUMN_REQUIRED);
            }

            Integer columnIndex = selectCols.get(0);
            if (columnIndex == null || columnIndex < 0 || columnIndex >= headerList.size()) {
                throw new BusinessException(ERROR_KEY_COPY_IN_VALUES_INVALID_SELECTION);
            }
            if (selectedColumnIndex == null) {
                selectedColumnIndex = columnIndex;
                rejectUnsupportedCopyInValuesColumn(headerList.get(columnIndex));
            } else if (!Objects.equals(selectedColumnIndex, columnIndex)) {
                throw new BusinessException(ERROR_KEY_COPY_IN_VALUES_SINGLE_COLUMN_REQUIRED);
            }

            List<String> row = operation.getDataList();
            if (CollectionUtils.isEmpty(row) || columnIndex >= row.size()) {
                throw new BusinessException(ERROR_KEY_COPY_IN_VALUES_INVALID_SELECTION);
            }
            rejectUnsupportedCopyInValuesCell(operation.getSelectedCell());

            SQLDataValue sqlDataValue = dataTypes.get(columnIndex);
            String selectedValue = row.get(columnIndex);
            if (isLargeValuePlaceholder(selectedValue)) {
                throw new BusinessException(ERROR_KEY_COPY_IN_VALUES_LARGE_VALUE_REJECTED);
            }
            sqlDataValue.setValue(selectedValue);
            values.add(valueProcessor.getSqlValueString(sqlDataValue));
        }

        return SQLConstants.OPEN_PARENTHESIS + String.join(SQLConstants.COMMA_SPACE, values) + SQLConstants.CLOSE_PARENTHESIS;
    }

    private void rejectUnsupportedCopyInValuesColumn(Header header) {
        if (header == null) {
            throw new BusinessException(ERROR_KEY_COPY_IN_VALUES_INVALID_SELECTION);
        }
        String columnType = StringUtils.defaultString(StringUtils.defaultIfBlank(header.getColumnType(), header.getDataType())).toLowerCase();
        if (COPY_IN_VALUES_BLOCKED_COLUMN_TYPES.stream().anyMatch(columnType::contains)) {
            throw new BusinessException(ERROR_KEY_COPY_IN_VALUES_LARGE_VALUE_REJECTED);
        }
    }

    private void rejectUnsupportedCopyInValuesCell(ResultCell selectedCell) {
        if (selectedCell == null) {
            throw new BusinessException(ERROR_KEY_COPY_IN_VALUES_INVALID_SELECTION);
        }
        String valueType = StringUtils.defaultString(selectedCell.getValueType()).toUpperCase(Locale.ROOT);
        if (selectedCell.isLargeValue() || selectedCell.isTruncated() || StringUtils.isNotBlank(selectedCell.getLargeValueId())
                || LargeValueTypeEnum.fromCode(valueType).isBinaryLike()) {
            throw new BusinessException(ERROR_KEY_COPY_IN_VALUES_LARGE_VALUE_REJECTED);
        }
    }

    private boolean isLargeValuePlaceholder(String value) {
        return StringUtils.startsWith(value, LARGE_VALUE_PREVIEW_PREFIX);
    }


    private List<String> getPrimaryColumns(List<Header> headerList) {
        if (CollectionUtils.isEmpty(headerList)) {
            return Lists.newArrayList();
        }
        List<String> keyColumns = Lists.newArrayList();
        for (Header header : headerList) {
            if (header.getPrimaryKey() != null && header.getPrimaryKey()) {
                keyColumns.add(header.getName());
            }
        }
        return keyColumns;
    }

    private String getDeleteSql(String tableName, List<Header> headerList, List<String> row, IDbMetaData metaSchema,
                                List<String> keyColumns) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_DELETE).append(tableName).append(SQLConstants.EMPTY);
        IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
        script.append(buildWhere(headerList, row, metaSchema, keyColumns, valueProcessor));
        return script.toString();
    }

    private String buildWhere(List<Header> headerList, List<String> row, IDbMetaData metaSchema, List<String> keyColumns, IValueProcessor valueProcessor) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_WHERE);
        if (CollectionUtils.isEmpty(keyColumns)) {
            for (int i = 1; i < row.size(); i++) {
                String oldValue = row.get(i);
                Header header = headerList.get(i);
                DataType dataType = new DataType();
                dataType.setDataTypeName(header.getColumnType());
                dataType.setPrecision(header.getColumnSize());
                dataType.setScale(header.getDecimalDigits());
                SQLDataValue sqlDataValue = new SQLDataValue();
                sqlDataValue.setDataType(dataType);
                sqlDataValue.setValue(oldValue);
                if (oldValue == null) {
                    script.append(metaSchema.getMetaDataName(header.getName()))
                            .append(SQLConstants.SQL_IS_NULL_LOWER_AND);
                } else {
                    String value = valueProcessor.getSqlValueString(sqlDataValue);
                    script.append(metaSchema.getMetaDataName(header.getName()))
                            .append(SQLConstants.EQUAL_SQL)
                            .append(value)
                            .append(SQL_AND_2);
                }
            }
        } else {
            for (int i = 1; i < row.size(); i++) {
                String oldValue = row.get(i);
                Header header = headerList.get(i);
                String columnName = header.getName();
                if (keyColumns.contains(columnName)) {
                    String value = SqlUtils.getSqlValue(oldValue, header.getDataType());
                    if (value == null) {
                        script.append(metaSchema.getMetaDataName(columnName))
                                .append(SQLConstants.SQL_IS_NULL_LOWER_AND);
                    } else {
                        script.append(metaSchema.getMetaDataName(columnName))
                                .append(SQLConstants.EQUAL_SQL)
                                .append(value)
                                .append(SQL_AND_2);
                    }
                }
            }
        }
        script.delete(script.length() - 4, script.length());
        return script.toString();
    }

    private String getInsertSql(String tableName, List<Header> headerList, List<String> row, IDbMetaData metaSchema) {
        if (CollectionUtils.isEmpty(row) || ObjectUtils.allNull(row.toArray())) {
            return SQLConstants.EMPTY;
        }
        StringBuilder script = new StringBuilder();
        script.append(SQL_INSERT_INTO).append(tableName)
                .append(SQLConstants.SPACE_OPEN_PARENTHESIS);

        IValueProcessor valueProcessor = metaSchema.getValueProcessor();
        for (int i = 1; i < row.size(); i++) {
            Header header = headerList.get(i);
            Integer autoIncrement = header.getAutoIncrement();
            if (Objects.nonNull(autoIncrement)
                    && autoIncrement == 1
                    && StringUtils.equals(SqlValueConstants.USER_FILLED_GENERATED_VALUE, row.get(i))) {
                continue;
            }
            script.append(metaSchema.getMetaDataName(header.getName()))
                    .append(SQLConstants.COMMA);
        }
        script.deleteCharAt(script.length() - 1);
        script.append(VALUE_CLOSE_PAREN_VALUES_OPEN_PAREN);
        for (int i = 1; i < row.size(); i++) {
            String newValue = row.get(i);
            if (SqlValueConstants.DEFAULT_VALUE.equals(newValue)) {
                script.append(SQLConstants.DEFAULT_SQL_WITH_COMMA);
                continue;
            }
            Header header = headerList.get(i);
            Integer autoIncrement = header.getAutoIncrement();
            if (Objects.nonNull(autoIncrement)
                    && autoIncrement == 1
                    && StringUtils.equals(SqlValueConstants.USER_FILLED_GENERATED_VALUE, row.get(i))) {
                continue;
            }
            SQLDataValue sqlDataValue = new SQLDataValue();
            DataType dataType = new DataType();
            dataType.setDataTypeName(header.getColumnType());
            dataType.setScale(header.getDecimalDigits());
            dataType.setPrecision(header.getColumnSize());
            sqlDataValue.setValue(newValue);
            sqlDataValue.setDataType(dataType);

            String value = valueProcessor.getSqlValueString(sqlDataValue);
            script.append(value)
                    .append(SQLConstants.COMMA);
        }
        script.deleteCharAt(script.length() - 1);
        script.append(SQLConstants.CLOSE_PARENTHESIS);
        return script.toString();

    }

    private String getUpdateSql(String tableName, List<Header> headerList, List<String> row, List<String> odlRow,
                                IDbMetaData metaSchema,
                                List<String> keyColumns, boolean copy) {
        StringBuilder script = new StringBuilder();
        if (CollectionUtils.isEmpty(row) || CollectionUtils.isEmpty(odlRow)) {
            return SQLConstants.EMPTY;
        }
        script.append(SQL_UPDATE).append(tableName).append(SQL_SET);
        IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
        for (int i = 1; i < row.size(); i++) {
            String newValue = row.get(i);
            String oldValue = odlRow.get(i);
            if (StringUtils.equals(newValue, oldValue) && !copy) {
                continue;
            }
            Header header = headerList.get(i);
            SQLDataValue sqlDataValue = new SQLDataValue();
            sqlDataValue.setValue(newValue);
            DataType dataType = new DataType();
            dataType.setDataTypeName(header.getColumnType());
            dataType.setScale(header.getDecimalDigits());
            dataType.setPrecision(header.getColumnSize());
            sqlDataValue.setDataType(dataType);
            String newSqlValue = valueProcessor.getSqlValueString(sqlDataValue);
            script.append(metaSchema.getMetaDataName(header.getName()))
                    .append(SQLConstants.EQUAL_SQL)
                    .append(newSqlValue)
                    .append(SQLConstants.COMMA);
        }
        script.deleteCharAt(script.length() - 1);
        script.append(buildWhere(headerList, odlRow, metaSchema, keyColumns, valueProcessor));
        return script.toString();
    }


    private String copyInsertSql(String tableName, List<Header> headerList, ResultOperation operation, IDbMetaData metaSchema, String dbType) {
        List<String> row = operation.getDataList();
        if (CollectionUtils.isEmpty(row)) {
            return SQLConstants.EMPTY;
        }

        List<Integer> selectCols = operation.getSelectCols();
        StringBuilder script = new StringBuilder();
        script.append(SQL_INSERT_INTO).append(tableName).append(SQLConstants.SPACE_OPEN_PARENTHESIS);

        IValueProcessor valueProcessor = metaSchema.getValueProcessor();
        List<SQLDataValue> sqlDataValues = header2SQLDataValue(headerList);
        List<String> columnNames = selectCols.stream()
                .filter(colIndex -> !ignoreAutoIncrement(colIndex, headerList, row))
                .map(colIndex -> SqlStringUtil.quoteValue(headerList.get(colIndex).getName(), dbType))
                .collect(Collectors.toList());

        script.append(String.join(SQLConstants.COMMA, columnNames));
        script.append(VALUE_CLOSE_PAREN_VALUES_OPEN_PAREN);
        List<String> values = selectCols.stream()
                .filter(colIndex -> !ignoreAutoIncrement(colIndex, headerList, row))
                .map(colIndex -> {
                    String newValue = row.get(colIndex);
                    if (SqlValueConstants.DEFAULT_VALUE.equals(newValue)) {
                        return SQLConstants.DEFAULT_SQL;
                    }
                    SQLDataValue sqlDataValue = sqlDataValues.get(colIndex);
                    sqlDataValue.setValue(newValue);
                    return valueProcessor.getSqlValueString(sqlDataValue);
                })
                .collect(Collectors.toList());

        script.append(String.join(SQLConstants.COMMA, values));
        script.append(SQLConstants.CLOSE_PARENTHESIS);

        return script.toString();
    }

    private boolean ignoreAutoIncrement(int columnIndex, List<Header> headerList, List<String> rows) {
        Header header = headerList.get(columnIndex);
        Integer autoIncrement = header.getAutoIncrement();
        if (autoIncrement == null || autoIncrement != 1) {
            return false;
        }

        String value = rows.get(columnIndex);
        return StringUtils.isBlank(value) ||
                StringUtils.equals(SqlValueConstants.USER_FILLED_GENERATED_VALUE, value);
    }


    private String copyUpdateSql(String tableName, List<Header> headerList, ResultOperation operation, IDbMetaData metaSchema, String dbType) {
        StringBuilder script = new StringBuilder();
        List<String> row = operation.getDataList();

        if (CollectionUtils.isEmpty(row)) {
            return SQLConstants.EMPTY;
        }
        ArrayList<String> columnNames = new ArrayList<>(headerList.size());
        List<SQLDataValue> sqlDataValues = header2SQLDataValue(headerList, dbType, columnNames);
        List<Integer> selectCols = operation.getSelectCols();
        script.append(SQL_UPDATE).append(tableName).append(SQL_SET);
        IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
        for (Integer colIndex : selectCols) {
            String newValue = row.get(colIndex);
            SQLDataValue sqlDataValue = sqlDataValues.get(colIndex);
            sqlDataValue.setValue(newValue);
            String newSqlValue = valueProcessor.getSqlValueString(sqlDataValue);
            String columnName = columnNames.get(colIndex);
            script.append(columnName)
                    .append(SQLConstants.EQUAL_SQL)
                    .append(newSqlValue)
                    .append(SQLConstants.COMMA);
        }
        script.deleteCharAt(script.length() - 1);
        script.append(buildWhere(headerList, row, metaSchema, getPrimaryColumns(headerList), valueProcessor));
        return script.toString();
    }

    private String copyWhereSql(List<ResultOperation> row, List<Header> headerList, IDbMetaData metaSchema, String dbType) {
        int headerSize = headerList.size();
        if (headerSize == 0) {
            return SQLConstants.EMPTY;
        }

        IValueProcessor valueProcessor = metaSchema.getValueProcessor();
        ArrayList<String> columnNameList = new ArrayList<>();
        List<SQLDataValue> dataTypes = header2SQLDataValue(headerList, dbType, columnNameList);
        boolean allSelectColsSame = row.stream()
                .map(ResultOperation::getSelectCols)
                .allMatch(cols -> cols.size() == 1 && cols.get(0).equals(row.get(0).getSelectCols().get(0)));
        if (allSelectColsSame) {
            Integer colIndex = row.get(0).getSelectCols().get(0);
            SQLDataValue sqlDataValue = dataTypes.get(colIndex);
            String columnName = columnNameList.get(colIndex);
            List<String> resultClause = row.stream()
                    .map(resultOperation -> resultOperation.getDataList().get(colIndex))
                    .distinct()
                    .toList();

            if (resultClause.size() == 1) {
                String singleValue = resultClause.get(0);
                if (singleValue == null) {
                    return SQLConstants.WHERE_SQL_PREFIX + columnName + SQLConstants.SQL_IS_NULL;
                } else {
                    sqlDataValue.setValue(singleValue);
                    if (valueProcessor.isStringDataType(sqlDataValue.getDataType().getDataTypeName())) {
                        return SQLConstants.WHERE_SQL_PREFIX + columnName + SQLConstants.SQL_LIKE + valueProcessor.getSqlValueString(sqlDataValue);
                    } else {
                        return SQLConstants.WHERE_SQL_PREFIX + columnName + SQLConstants.EQUAL_SQL + valueProcessor.getSqlValueString(sqlDataValue);
                    }
                }
            } else {
                return buildWhereClauseForSameColumnValues(columnName, resultClause, sqlDataValue, valueProcessor);
            }
        }
        String whereClause = row.stream().map(
                resultOperation -> {
                    List<Integer> selectCols = resultOperation.getSelectCols();
                    List<String> dataList = resultOperation.getDataList();
                    StringBuilder rowConditionBuilder = new StringBuilder();
                    for (int i = 0; i < selectCols.size(); i++) {
                        Integer colIndex = selectCols.get(i);
                        SQLDataValue sqlDataValue = dataTypes.get(colIndex);
                        String value = dataList.get(colIndex);
                        rowConditionBuilder.append(columnNameList.get(colIndex));
                        if (Objects.isNull(value)) {
                            rowConditionBuilder.append(SQLConstants.SQL_IS_NULL);
                        } else {
                            boolean stringDataType = valueProcessor.isStringDataType(sqlDataValue.getDataType().getDataTypeName());
                            if (stringDataType) {
                                rowConditionBuilder.append(SQLConstants.SQL_LIKE);
                            } else {
                                rowConditionBuilder.append(SQLConstants.EQUAL_SQL);
                            }
                            sqlDataValue.setValue(value);
                            rowConditionBuilder.append(valueProcessor.getSqlValueString(sqlDataValue));
                        }
                        if (i != selectCols.size() - 1) {
                            rowConditionBuilder.append(SQL_AND);
                        } else {
                            rowConditionBuilder.append(SQLConstants.LINE_SEPARATOR);
                        }
                    }
                    return rowConditionBuilder.toString();
                }).collect(Collectors.joining(SQLConstants.SQL_OR));

        return SQLConstants.WHERE_SQL_PREFIX + whereClause;
    }

    private String buildWhereClauseForSameColumnValues(String columnName, List<String> values, SQLDataValue sqlDataValue,
                                                       IValueProcessor valueProcessor) {
        List<String> conditions = new ArrayList<>();
        if (values.stream().anyMatch(Objects::isNull)) {
            conditions.add(columnName + SQLConstants.SQL_IS_NULL);
        }

        List<String> nonNullValues = values.stream()
                .filter(Objects::nonNull)
                .map(value -> {
                    sqlDataValue.setValue(value);
                    return valueProcessor.getSqlValueString(sqlDataValue);
                })
                .toList();
        if (CollectionUtils.isNotEmpty(nonNullValues)) {
            conditions.add(columnName + SQLConstants.SQL_IN_OPEN_PARENTHESIS
                    + String.join(SQLConstants.COMMA_SPACE, nonNullValues)
                    + SQLConstants.CLOSE_PARENTHESIS);
        }

        return SQLConstants.WHERE_SQL_PREFIX + String.join(SQLConstants.SQL_OR, conditions);
    }

    private static @NotNull List<SQLDataValue> header2SQLDataValue(List<Header> headerList, String dbType, ArrayList<String> columnNameList) {
        return headerList.stream().map(header -> {
            DataType dataType = new DataType();
            dataType.setDataTypeName(header.getColumnType());
            dataType.setScale(header.getDecimalDigits());
            dataType.setPrecision(header.getColumnSize());
            SQLDataValue sqlDataValue = new SQLDataValue();
            sqlDataValue.setDataType(dataType);
            String name = header.getName();
            String quoteName = SqlStringUtil.quoteValue(name, dbType);
            columnNameList.add(quoteName);
            return sqlDataValue;
        }).toList();
    }

    private static @NotNull List<SQLDataValue> header2SQLDataValue(List<Header> headerList) {
        return headerList.stream().map(header -> {
            DataType dataType = new DataType();
            dataType.setDataTypeName(header.getColumnType());
            dataType.setScale(header.getDecimalDigits());
            dataType.setPrecision(header.getColumnSize());
            SQLDataValue sqlDataValue = new SQLDataValue();
            sqlDataValue.setDataType(dataType);
            return sqlDataValue;
        }).toList();
    }

    @Override
    public String buildCreateView(ModifyView modifyView) {
        throw unsupported(METHOD_BUILD_CREATE_VIEW);
    }

    @Override
    public String buildAlterView(ModifyView view) {
        throw unsupported(METHOD_BUILD_ALTER_VIEW);
    }

    @Override
    public String buildDropView(String databaseName, String schemaName, String viewName) {
        throw unsupported(METHOD_BUILD_DROP_VIEW);
    }

    @Override
    public String buildShowCreateView(String databaseName, String schemaName, String viewName) {
        throw unsupported(METHOD_BUILD_SHOW_CREATE_VIEW);
    }

    @Override
    public String buildExplain(String sql) {
        return SQLConstants.EXPLAIN_SQL_PREFIX + sql;
    }

    @Override
    public String buildAITableSchema(Table table) {
        List<TableColumn> columnList = table.getColumnList();
        if (CollectionUtils.isEmpty(columnList)) {
            table.setColumnList(List.of());
        }
        List<TableIndex> indexList = table.getIndexList();
        if (CollectionUtils.isEmpty(indexList)) {
            table.setIndexList(List.of());
        }
        List<ForeignKeyInfo> foreignKeyList = table.getForeignKeyList();
        if (CollectionUtils.isEmpty(foreignKeyList)) {
            table.setForeignKeyList(List.of());
        }
        return buildCreateTable(table, TableBuilderConfig.defaultConfig());
    }

    private UnsupportedOperationException unsupported(String capability) {
        return new UnsupportedOperationException(ERROR_UNSUPPORTED_DEFAULT_SQL_BUILDER_PREFIX + capability);
    }
}
