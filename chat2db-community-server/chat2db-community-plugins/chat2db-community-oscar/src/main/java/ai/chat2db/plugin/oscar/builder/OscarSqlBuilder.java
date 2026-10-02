package ai.chat2db.plugin.oscar.builder;

import ai.chat2db.plugin.oscar.constant.OscarConstants;
import ai.chat2db.plugin.oscar.enums.type.OscarColumnTypeEnum;
import ai.chat2db.plugin.oscar.enums.type.OscarIndexTypeEnum;
import ai.chat2db.spi.constant.SQLConstants;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;

public class OscarSqlBuilder extends OscarBaseSqlBuilder {

    @Override
    public String buildCreateTable(Table table, TableBuilderConfig tableBuilderConfig) {
        StringBuilder script = new StringBuilder();
        script.append(SQLConstants.CREATE_TABLE_SQL_PREFIX)
                .append(qualifiedName(table.getSchemaName(), table.getName()))
                .append(SQLConstants.SPACE)
                .append(SQLConstants.OPEN_PARENTHESIS)
                .append(SQLConstants.LINE_SEPARATOR);

        if (CollectionUtils.isNotEmpty(table.getColumnList())) {
            for (TableColumn column : table.getColumnList()) {
                if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                    continue;
                }
                OscarColumnTypeEnum typeEnum = OscarColumnTypeEnum.getByType(column.getColumnType());
                if (typeEnum == null) {
                    typeEnum = OscarColumnTypeEnum.VARCHAR;
                }
                script.append(SQLConstants.TAB)
                        .append(typeEnum.buildCreateColumnSql(column))
                        .append(SQLConstants.COMMA_LINE_SEPARATOR);
            }
        }

        if (script.substring(script.length() - 2).equals(SQLConstants.COMMA_LINE_SEPARATOR)) {
            script = new StringBuilder(script.substring(0, script.length() - 2));
        }
        script.append(SQLConstants.LINE_SEPARATOR)
                .append(SQLConstants.CLOSE_PARENTHESIS)
                .append(SQLConstants.SEMICOLON);

        appendIndexes(script, table.getIndexList());
        appendComments(script, table);
        return script.toString();
    }

    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        StringBuilder script = new StringBuilder();

        if (!StringUtils.equalsIgnoreCase(oldTable.getName(), newTable.getName())) {
            script.append(SQLConstants.ALTER_TABLE_SQL_PREFIX)
                    .append(qualifiedName(oldTable.getSchemaName(), oldTable.getName()))
                    .append(SQLConstants.TABLE_RENAME_SQL)
                    .append(quoteIdentifierIgnoreCase(newTable.getName()))
                    .append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
        }
        if (!StringUtils.equals(oldTable.getComment(), newTable.getComment())) {
            script.append(buildTableComment(newTable)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
        }

        if (CollectionUtils.isNotEmpty(newTable.getColumnList())) {
            for (TableColumn tableColumn : newTable.getColumnList()) {
                String editStatus = tableColumn.getEditStatus();
                if (StringUtils.isBlank(editStatus)) {
                    continue;
                }
                OscarColumnTypeEnum typeEnum = OscarColumnTypeEnum.getByType(tableColumn.getColumnType());
                if (typeEnum == null) {
                    typeEnum = OscarColumnTypeEnum.VARCHAR;
                }
                script.append(typeEnum.buildModifyColumn(tableColumn)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
                if (StringUtils.isNotBlank(tableColumn.getComment())
                        && !Objects.equals(EditStatusEnum.DELETE.name(), editStatus)) {
                    script.append(buildComment(tableColumn)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
                }
            }
        }

        if (CollectionUtils.isNotEmpty(newTable.getIndexList())) {
            for (TableIndex tableIndex : newTable.getIndexList()) {
                if (StringUtils.isBlank(tableIndex.getEditStatus()) || StringUtils.isBlank(tableIndex.getType())) {
                    continue;
                }
                OscarIndexTypeEnum indexTypeEnum = OscarIndexTypeEnum.getByType(tableIndex.getType());
                if (indexTypeEnum == null) {
                    continue;
                }
                script.append(indexTypeEnum.buildModifyIndex(tableIndex)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
            }
        }
        if (!script.isEmpty() && script.substring(script.length() - 2)
                .equals(SQLConstants.SEMICOLON_LINE_SEPARATOR)) {
            script = new StringBuilder(script.substring(0, script.length() - 2));
            script.append(SQLConstants.SEMICOLON);
        }

        return script.toString();
    }

    @Override
    public String buildPageLimit(PageLimitRequest request) {
        String sql = request.getSql();
        int startRow = request.getOffset();
        int pageSize = request.getPageSize();
        long endRow = (long) startRow + pageSize;
        StringBuilder sqlBuilder = new StringBuilder(sql.length() + 120);
        sqlBuilder.append(OscarConstants.PAGE_OUTER_SELECT_PREFIX);
        if (startRow > 0) {
            sqlBuilder.append(OscarConstants.PAGE_INNER_SELECT_PREFIX);
        }
        sqlBuilder.append(SQLConstants.LINE_SEPARATOR).append(sql).append(SQLConstants.LINE_SEPARATOR);
        sqlBuilder.append(OscarConstants.PAGE_ROWNUM_FILTER_SQL).append(endRow);
        if (startRow > 0) {
            sqlBuilder.append(OscarConstants.PAGE_AUTO_ROW_ID_FILTER_SQL).append(startRow);
        }
        return sqlBuilder.toString();
    }

    private void appendIndexes(StringBuilder script, List<TableIndex> indexList) {
        if (CollectionUtils.isEmpty(indexList)) {
            return;
        }
        for (TableIndex tableIndex : indexList) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            OscarIndexTypeEnum indexTypeEnum = OscarIndexTypeEnum.getByType(tableIndex.getType());
            if (indexTypeEnum == null) {
                continue;
            }
            script.append(SQLConstants.LINE_SEPARATOR)
                    .append(indexTypeEnum.buildIndexScript(tableIndex))
                    .append(SQLConstants.SEMICOLON);
        }
    }

    private void appendComments(StringBuilder script, Table table) {
        if (CollectionUtils.isNotEmpty(table.getColumnList())) {
            for (TableColumn column : table.getColumnList()) {
                if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getComment())) {
                    continue;
                }
                script.append(SQLConstants.LINE_SEPARATOR)
                        .append(buildComment(column))
                        .append(SQLConstants.SEMICOLON);
            }
        }
        if (StringUtils.isNotBlank(table.getComment())) {
            script.append(SQLConstants.LINE_SEPARATOR)
                    .append(buildTableComment(table))
                    .append(SQLConstants.SEMICOLON);
        }
    }
}
