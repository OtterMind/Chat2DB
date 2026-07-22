package ai.chat2db.plugin.oracle.builder;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.plugin.oracle.enums.type.OracleColumnTypeEnum;
import ai.chat2db.plugin.oracle.enums.type.OracleIndexTypeEnum;
import ai.chat2db.plugin.oracle.util.OracleUtil;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.community.domain.api.model.view.ModifyView;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.spi.util.SqlUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.oracle.constant.OracleSqlBuilderConstants.*;
public class OracleSqlBuilder extends DefaultSqlBuilder {





















    @Override
    protected String appendSingleRowLimit(String operationType, String tableName, String whereClause, String sql) {
        if (StringUtils.isBlank(whereClause) || !sql.endsWith(whereClause)) {
            return sql;
        }
        String body = sql.substring(0, sql.length() - whereClause.length());
        return body + SQL_WHERE_ROWID_IN_OPEN_PAREN_SELECT_ROWID_FROM + tableName + whereClause + VALUE_AND_ROWNUM_EQUAL_1_CLOSE_PAREN;
    }

    @Override
    public String buildCreateTable(Table table, TableBuilderConfig tableBuilderConfig) {
        StringBuilder script = new StringBuilder();

        script.append(SQL_CREATE_TABLE)
                .append(OracleUtil.quoteIdentifier(table.getSchemaName()))
                .append(SQLConstants.DOT)
                .append(OracleUtil.quoteIdentifierIgnoreCase(table.getName()))
                .append(SQLConstants.OPEN_PARENTHESIS).append(SQLConstants.LINE_SEPARATOR);

        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            OracleColumnTypeEnum typeEnum = OracleColumnTypeEnum.getByType(column.getColumnType());
            if (typeEnum == null) {
                continue;
            }
            script.append(SQLConstants.TAB).append(typeEnum.buildCreateColumnSql(column)).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }

        script = new StringBuilder(script.substring(0, script.length() - 2));
        script.append(SQLConstants.LINE_SEPARATOR_CLOSE_PARENTHESIS_SEMICOLON);

        for (TableIndex tableIndex : table.getIndexList()) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            OracleIndexTypeEnum oracleColumnTypeEnum = OracleIndexTypeEnum.getByType(tableIndex.getType());
            if (oracleColumnTypeEnum == null) {
                continue;
            }
            script.append(SQLConstants.LINE_SEPARATOR).append(SQLConstants.EMPTY).append(oracleColumnTypeEnum.buildIndexScript(tableIndex)).append(SQLConstants.SEMICOLON);
        }

        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType()) || StringUtils.isBlank(column.getComment())) {
                continue;
            }
            script.append(SQLConstants.LINE_SEPARATOR).append(buildComment(column)).append(SQLConstants.SEMICOLON);
        }

        if (StringUtils.isNotBlank(table.getComment())) {
            script.append(SQLConstants.LINE_SEPARATOR).append(buildTableComment(table)).append(SQLConstants.SEMICOLON);
        }


        return script.toString();
    }

    @Override
    public String buildAITableSchema(Table table) {
        StringBuilder script = new StringBuilder();

        script.append(SQL_CREATE_TABLE)
                .append(OracleUtil.quoteIdentifier(table.getSchemaName()))
                .append(SQLConstants.DOT)
                .append(OracleUtil.quoteIdentifierIgnoreCase(table.getName()))
                .append(SQLConstants.OPEN_PARENTHESIS).append(SQLConstants.LINE_SEPARATOR);

        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            OracleColumnTypeEnum typeEnum = OracleColumnTypeEnum.getByType(column.getColumnType());
            if (typeEnum == null) {
                continue;
            }
            script.append(SQLConstants.TAB).append(typeEnum.buildAICreateColumnSql(column)).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }

        script = new StringBuilder(script.substring(0, script.length() - 2));
        script.append(SQLConstants.LINE_SEPARATOR_CLOSE_PARENTHESIS_SEMICOLON);

        List<TableIndex> indexList = table.getIndexList();
        if (CollectionUtils.isEmpty(indexList)) {
            indexList = List.of();
        }
        for (TableIndex tableIndex : indexList) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            OracleIndexTypeEnum oracleColumnTypeEnum = OracleIndexTypeEnum.getByType(tableIndex.getType());
            if (oracleColumnTypeEnum == null) {
                continue;
            }
            script.append(SQLConstants.LINE_SEPARATOR).append(oracleColumnTypeEnum.buildIndexScript(tableIndex)).append(SQLConstants.SEMICOLON);
        }


        if (StringUtils.isNotBlank(table.getComment())) {
            script.append(SQLConstants.LINE_SEPARATOR).append(buildTableComment(table)).append(SQLConstants.SEMICOLON);
        }

        return script.toString();
    }


    private String buildTableComment(Table table) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_COMMENT_TABLE_2).append(SQLConstants.DOUBLE_QUOTE).append(table.getSchemaName()).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(table.getName()).append(VALUE_DOUBLE_QUOTE_IS_SINGLE_QUOTE).append(table.getComment()).append(SQLConstants.SINGLE_QUOTE);
        return script.toString();
    }

    private String buildComment(TableColumn column) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_COMMENT_COLUMN).append(SQLConstants.DOUBLE_QUOTE).append(column.getSchemaName()).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(column.getTableName()).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(column.getName()).append(VALUE_DOUBLE_QUOTE_IS_SINGLE_QUOTE).append(column.getComment()).append(SQLConstants.SINGLE_QUOTE);
        return script.toString();
    }

    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        StringBuilder script = new StringBuilder();

        if (!StringUtils.equalsIgnoreCase(oldTable.getName(), newTable.getName())) {
            script.append(SQL_ALTER_TABLE).append(SQLConstants.DOUBLE_QUOTE).append(oldTable.getSchemaName()).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(oldTable.getName()).append(SQLConstants.DOUBLE_QUOTE);
            script.append(SQLConstants.SPACE).append(SQL_RENAME).append(SQLConstants.DOUBLE_QUOTE).append(newTable.getName()).append(SQLConstants.DOUBLE_QUOTE).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
        }
        if (!StringUtils.equalsIgnoreCase(oldTable.getComment(), newTable.getComment())) {
            script.append(SQLConstants.EMPTY).append(buildTableComment(newTable)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
        }
        for (TableColumn tableColumn : newTable.getColumnList()) {
            String editStatus = tableColumn.getEditStatus();
            if (StringUtils.isNotBlank(editStatus)) {
                OracleColumnTypeEnum typeEnum = OracleColumnTypeEnum.getByType(tableColumn.getColumnType());
                if (typeEnum == null) {
                    continue;
                }
                script.append(SQLConstants.TAB).append(typeEnum.buildModifyColumn(tableColumn)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
                if (StringUtils.isNotBlank(tableColumn.getComment())
                        &&!EditStatusEnum.DELETE.name().equals(editStatus)) {
                    script.append(SQLConstants.LINE_SEPARATOR).append(buildComment(tableColumn)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
                }
            }
        }
        for (TableIndex tableIndex : newTable.getIndexList()) {
            if (StringUtils.isNotBlank(tableIndex.getEditStatus()) && StringUtils.isNotBlank(tableIndex.getType())) {
                OracleIndexTypeEnum oracleIndexTypeEnum = OracleIndexTypeEnum.getByType(tableIndex.getType());
                if (oracleIndexTypeEnum == null) {
                    continue;
                }
                script.append(SQLConstants.TAB).append(oracleIndexTypeEnum.buildModifyIndex(tableIndex)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
            }
        }
        if (script.length() > 2) {
            script = new StringBuilder(script.substring(0, script.length() - 2));
            script.append(SQLConstants.SEMICOLON);
        }

        return script.toString();
    }

    @Override
    public String buildPageLimit(PageLimitRequest request) {
        String sql = request.getSql();
        int offset = request.getOffset();
        int pageNo = request.getPageNo();
        int pageSize = request.getPageSize();
        int startRow = offset;
        int endRow = offset + pageSize;
        StringBuilder sqlBuilder = new StringBuilder(sql.length() + 120);
        sqlBuilder.append(SQL_SELECT);
        sqlBuilder.append(SQL_SELECT_TMP_PAGE_ROWNUM_CAHT2DB);
        sqlBuilder.append(SQLConstants.LINE_SEPARATOR);
        sqlBuilder.append(sql);
        sqlBuilder.append(SQLConstants.LINE_SEPARATOR);
        sqlBuilder.append(SQL_CLOSE_PAREN_TMP_PAGE_WHERE_ROWNUM_EQUAL);
        sqlBuilder.append(endRow);
        sqlBuilder.append(SQL_CLOSE_PAREN_WHERE_CAHT2DB_AUTO_ROW_ID);
        sqlBuilder.append(startRow);
        return sqlBuilder.toString();
    }


    @Override
    protected void buildTableName(String databaseName, String schemaName, String tableName, StringBuilder script) {
        if (StringUtils.isNotBlank(databaseName)) {
            script.append(SqlUtils.quoteObjectName(databaseName)).append('.');
        }
        script.append(SqlUtils.quoteObjectName(tableName));
    }


    @Override
    protected void buildColumns(List<String> columnList, StringBuilder script) {
        if (CollectionUtils.isNotEmpty(columnList)) {
            script.append(SQLConstants.SPACE_OPEN_PARENTHESIS)
                    .append(columnList.stream().map(SqlUtils::quoteObjectName).collect(Collectors.joining(SQLConstants.COMMA)))
                    .append(SQLConstants.CLOSE_PARENTHESIS_SPACE);
        }
    }

    @Override
    public String buildCreateView(ModifyView modifyView) {
        StringBuilder createViewSqlBuilder = new StringBuilder(100);
        createViewSqlBuilder.append(SQL_CREATE);
        if (modifyView.isUseOrReplace()) {
            createViewSqlBuilder.append(SQL_REPLACE);
        }
        if (modifyView.isUseForce()) {
            createViewSqlBuilder.append(SQLConstants.FORCE_SQL);
        }
        String editClause = modifyView.getEditClause();
        if (StringUtils.isNotBlank(editClause)) {
            createViewSqlBuilder.append(editClause);
        }
        createViewSqlBuilder.append(SQLConstants.VIEW_KEYWORD);
        String schemaName = modifyView.getSchemaName();
        if (StringUtils.isNotBlank(schemaName)) {
            createViewSqlBuilder.append(SQLConstants.DOUBLE_QUOTE).append(schemaName).append(SQLConstants.DOUBLE_QUOTE).append(SQLConstants.DOT);
        }
        String viewName = modifyView.getViewName();
        if (StringUtils.isNotBlank(viewName)) {
            createViewSqlBuilder.append(SQLConstants.DOUBLE_QUOTE).append(viewName).append(SQLConstants.DOUBLE_QUOTE);
        } else {
            createViewSqlBuilder.append(UNDEFINED_KEYWORD);
        }
        createViewSqlBuilder.append(SQLConstants.SPACE);
        if (modifyView.isUseIfNotExists()) {
            createViewSqlBuilder.append(SQLConstants.IF_NOT_EXISTS_SQL);
        }
        String shareClause = modifyView.getShareClause();
        if (StringUtils.isNotBlank(shareClause)) {
            createViewSqlBuilder.append(SQL_SHARING_EQUAL).append(shareClause).append(SQLConstants.SPACE);
        }
        String collationClause = modifyView.getCollationClause();
        if (StringUtils.isNotBlank(collationClause)) {
            createViewSqlBuilder.append(SQL_DEFAULT_COLLATE).append(collationClause).append(SQLConstants.SPACE);
        }
        String security = modifyView.getSecurity();
        if (StringUtils.isNotBlank(security)) {
            createViewSqlBuilder.append(security).append(SQLConstants.SPACE);
        }
        createViewSqlBuilder.append(SQLConstants.LINE_SEPARATOR_SQL_AS);
        String viewBody = modifyView.getViewBody();
        if (StringUtils.isNotBlank(viewBody)) {
            viewBody = viewBody.trim();
            if (viewBody.endsWith(SQLConstants.SEMICOLON)) {
                viewBody = viewBody.substring(0, viewBody.length() - 1);
            }
            createViewSqlBuilder.append(SQLConstants.LINE_SEPARATOR).append(viewBody).append(SQLConstants.SPACE);
        }
        String subqueryRestrictionClause = modifyView.getSubqueryRestrictionClause();
        if (StringUtils.isNotBlank(subqueryRestrictionClause)) {
            createViewSqlBuilder.append(subqueryRestrictionClause).append(SQLConstants.SPACE);
        }
        String subqueryConstraintName = modifyView.getSubqueryConstraintName();
        if (StringUtils.equalsAnyIgnoreCase(SQL_CHECK_OPTION_CONSTRAINT, subqueryRestrictionClause)
                && StringUtils.isNotBlank(subqueryConstraintName)) {
            createViewSqlBuilder.append(subqueryConstraintName).append(SQLConstants.SPACE);
        }
        String containerClause = modifyView.getContainerClause();
        if (StringUtils.isNotBlank(containerClause)) {
            createViewSqlBuilder.append(containerClause);
        }
        createViewSqlBuilder.append(SQLConstants.SEMICOLON);

        String comment = modifyView.getComment();
        if (StringUtils.isNotBlank(comment)) {
            createViewSqlBuilder.append(SQLConstants.LINE_SEPARATOR);
            createViewSqlBuilder.append(SQL_COMMENT_TABLE).append(SQLConstants.SPACE);
            createViewSqlBuilder.append(SQLConstants.DOUBLE_QUOTE).append(schemaName).append(SQLConstants.DOUBLE_QUOTE)
                    .append(SQLConstants.DOT)
                    .append(SQLConstants.DOUBLE_QUOTE).append(viewName).append(SQLConstants.DOUBLE_QUOTE)
                    .append(SQLConstants.SQL_IS_LOWER)
                    .append(SQLConstants.SINGLE_QUOTE).append(comment).append(SQLConstants.SINGLE_QUOTE)
                    .append(SQLConstants.SEMICOLON);
        }
        return createViewSqlBuilder.toString();
    }

    @Override
    public String buildExplain(String sql) {
        return SQL_EXPLAIN_PLAN_FOR + sql;
    }
}
