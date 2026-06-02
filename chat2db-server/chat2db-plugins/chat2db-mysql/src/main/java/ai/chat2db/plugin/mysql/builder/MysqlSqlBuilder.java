package ai.chat2db.plugin.mysql.builder;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import ai.chat2db.plugin.mysql.type.MysqlColumnTypeEnum;
import ai.chat2db.plugin.mysql.type.MysqlIndexTypeEnum;
import ai.chat2db.plugin.mysql.util.MysqlSqlUtils;
import ai.chat2db.spi.SqlBuilder;
import ai.chat2db.spi.enums.EditStatus;
import ai.chat2db.spi.jdbc.DefaultSqlBuilder;
import ai.chat2db.spi.model.Database;
import ai.chat2db.spi.model.ForeignKey;
import ai.chat2db.spi.model.Header;
import ai.chat2db.spi.model.Table;
import ai.chat2db.spi.model.TableColumn;
import ai.chat2db.spi.model.TableIndex;
import ai.chat2db.spi.MetaData;
import cn.hutool.core.util.ArrayUtil;

public class MysqlSqlBuilder extends DefaultSqlBuilder implements SqlBuilder {

    @Override
    public String buildCreateTableSql(Table table) {
        StringBuilder script = new StringBuilder();
        if (StringUtils.isNotBlank(table.getAiComment())) {
            script.append(" -- ").append(table.getAiComment()).append("\n");
        }
        script.append("CREATE TABLE ");
        if (StringUtils.isNotBlank(table.getDatabaseName())) {
            script.append("`").append(table.getDatabaseName()).append("`").append(".");
        }
        script.append("`").append(table.getName()).append("`").append(" (").append("\n");

        appendColumns(script, table.getColumnList(),
                hasPrimaryKeyIndex(table.getIndexList()) || getPrimaryKeyColumns(table.getColumnList()).size() > 1);
        appendIndexes(script, table.getIndexList());
        appendForeignKeys(script, table.getForeignKeyList());
        appendVirtualForeignKeys(script, table.getVirtualForeignKeyList());

        if (script.length() > 2) {
            script.setLength(script.length() - 2);
        }
        script.append("\n)");
        appendTableAttributes(script, table);
        script.append(";");

        return script.toString();
    }

    // 添加列的方法
    @Override
    protected void appendColumns(StringBuilder script, List<TableColumn> columns) {
        appendColumns(script, columns, shouldSuppressInlinePrimaryKey(columns));
    }

    private void appendColumns(StringBuilder script, List<TableColumn> columns, boolean suppressInlinePrimaryKey) {
        if (columns == null) {
            return;
        }
        Set<String> primaryKeyColumns = getPrimaryKeyColumns(columns);
        for (TableColumn column : columns) {
            if (StringUtils.isBlank(column.getName())) {
                continue;
            }
            MysqlColumnTypeEnum typeEnum = MysqlColumnTypeEnum.getColumnTypeEnum(column);
            if (typeEnum == null) {
                continue;
            }
            Boolean originalPrimaryKey = column.getPrimaryKey();
            if (suppressInlinePrimaryKey && primaryKeyColumns.contains(column.getName())) {
                column.setPrimaryKey(false);
            }
            script.append("\t").append(typeEnum.buildCreateColumnSql(column)).append(",\n");
            if (suppressInlinePrimaryKey && primaryKeyColumns.contains(column.getName())) {
                column.setPrimaryKey(originalPrimaryKey);
            }
        }
    }

    private boolean shouldSuppressInlinePrimaryKey(List<TableColumn> columns) {
        if (columns == null) {
            return false;
        }
        Set<String> primaryKeyColumns = getPrimaryKeyColumns(columns);
        boolean hasPrimaryKeyIndexMetadata = columns.stream()
                .anyMatch(column -> Boolean.TRUE.equals(column.getPrimaryKey())
                        && StringUtils.isNotBlank(column.getPrimaryKeyName()));
        return hasPrimaryKeyIndexMetadata || primaryKeyColumns.size() > 1;
    }

    private Set<String> getPrimaryKeyColumns(List<TableColumn> columns) {
        Set<String> primaryKeyColumns = new HashSet<>();
        if (columns == null) {
            return primaryKeyColumns;
        }
        for (TableColumn column : columns) {
            if (Boolean.TRUE.equals(column.getPrimaryKey()) && StringUtils.isNotBlank(column.getName())) {
                primaryKeyColumns.add(column.getName());
            }
        }
        return primaryKeyColumns;
    }

    // 添加索引的方法
    @Override
    protected void appendIndexes(StringBuilder script, List<TableIndex> indexes) {
        if (indexes == null) {
            return;
        }
        for (TableIndex tableIndex : indexes) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            MysqlIndexTypeEnum mysqlIndexTypeEnum = MysqlIndexTypeEnum.getByType(tableIndex.getType());
            if (mysqlIndexTypeEnum == null) {
                continue;
            }
            script.append("\t").append(mysqlIndexTypeEnum.buildIndexScript(tableIndex)).append(",\n");
        }
    }

    private boolean hasPrimaryKeyIndex(List<TableIndex> indexes) {
        if (indexes == null) {
            return false;
        }
        return indexes.stream()
                .anyMatch(index -> MysqlIndexTypeEnum.PRIMARY_KEY.getName().equalsIgnoreCase(index.getType()));
    }

    // 添加表的其他属性的方法
    @Override
    protected void appendTableAttributes(StringBuilder script, Table table) {
        if (StringUtils.isNotBlank(table.getEngine())) {
            script.append(" ENGINE=").append(table.getEngine());
        }
        if (StringUtils.isNotBlank(table.getCharset())) {
            script.append(" DEFAULT CHARACTER SET=").append(table.getCharset());
        }
        if (StringUtils.isNotBlank(table.getCollate())) {
            script.append(" COLLATE=").append(table.getCollate());
        }
        if (table.getIncrementValue() != null) {
            script.append(" AUTO_INCREMENT=").append(table.getIncrementValue());
        }
        if (StringUtils.isNotBlank(table.getComment())) {
            script.append(" COMMENT=").append(MysqlSqlUtils.quoteString(table.getComment()));
        }
        if (StringUtils.isNotBlank(table.getPartition())) {
            script.append(" \n").append(table.getPartition());
        }
    }

    @Override
    protected void modifyTableNameAndComment(StringBuilder script, Table oldTable, Table newTable) {
        if (!StringUtils.equalsIgnoreCase(oldTable.getName(), newTable.getName())) {
            script.append("\t").append("RENAME TO `").append(newTable.getName()).append("`,\n");
        }
        if (!StringUtils.equalsIgnoreCase(oldTable.getComment(), newTable.getComment())) {
            script.append("\t").append("COMMENT=").append(MysqlSqlUtils.quoteString(newTable.getComment())).append(",\n");
        }
        if (newTable.getIncrementValue() != null
                && !Objects.equals(oldTable.getIncrementValue(), newTable.getIncrementValue())) {
            script.append("\t").append("AUTO_INCREMENT=").append(newTable.getIncrementValue()).append(",\n");
        }
    }

    @Override
    // 修改列的方法
    protected void modifyColumns(StringBuilder script, Table oldTable, Table newTable) {
        for (TableColumn tableColumn : newTable.getColumnList()) {
            if (StringUtils.isNotBlank(tableColumn.getEditStatus())
                    && StringUtils.isNotBlank(tableColumn.getName())) {
                MysqlColumnTypeEnum typeEnum = MysqlColumnTypeEnum.getColumnTypeEnum(tableColumn);
                if (typeEnum == null) {
                    continue;
                }
                script.append("\t").append(typeEnum.buildModifyColumn(tableColumn)).append(",\n");
            }
        }
    }

    @Override
    // 修改索引的方法
    protected void modifyIndexes(StringBuilder script, Table oldTable, Table newTable) {
        for (TableIndex tableIndex : newTable.getIndexList()) {
            if (StringUtils.isNotBlank(tableIndex.getEditStatus()) && StringUtils.isNotBlank(tableIndex.getType())) {
                MysqlIndexTypeEnum mysqlIndexTypeEnum = MysqlIndexTypeEnum.getByType(tableIndex.getType());
                script.append("\t").append(mysqlIndexTypeEnum.buildModifyIndex(tableIndex)).append(",\n");
            }
        }
    }

    @Override
    // 修改外键的方法
    protected void modifyForeignKeys(StringBuilder script, Table oldTable, Table newTable) {
        if (newTable.getForeignKeyList() == null) {
            return;
        }
        for (ForeignKey newForeignKey : newTable.getForeignKeyList()) {
            if (EditStatus.DELETE.name().equals(newForeignKey.getEditStatus())) {
                script.append("\t").append("DROP FOREIGN KEY `").append(newForeignKey.getName()).append("`,\n");
            } else if (EditStatus.ADD.name().equals(newForeignKey.getEditStatus())) {
                script.append("\t").append("ADD CONSTRAINT `").append(newForeignKey.getName()).append("` ")
                        .append("FOREIGN KEY (`").append(newForeignKey.getColumn()).append("`) ")
                        .append("REFERENCES `").append(newForeignKey.getReferencedTable()).append("` (`")
                        .append(newForeignKey.getReferencedColumn()).append("`),\n");
            } else if (EditStatus.MODIFY.name().equals(newForeignKey.getEditStatus())) {
                // 处理修改的外键
                script.append("\t").append("DROP FOREIGN KEY `").append(newForeignKey.getName()).append("`,\n");
                script.append("\t").append("ADD CONSTRAINT `").append(newForeignKey.getName()).append("` ")
                        .append("FOREIGN KEY (`").append(newForeignKey.getColumn()).append("`) ")
                        .append("REFERENCES `").append(newForeignKey.getReferencedTable()).append("` (`")
                        .append(newForeignKey.getReferencedColumn()).append("`),\n");
            }
        }
    }

    @Override
    public String pageLimit(String sql, int offset, int pageNo, int pageSize) {
        StringBuilder sqlBuilder = new StringBuilder(sql.length() + 14);
        sqlBuilder.append(sql);
        sqlBuilder.append("\n LIMIT ");
        if (offset == 0) {
            sqlBuilder.append(pageSize);
        } else {
            sqlBuilder.append(offset).append(",").append(pageSize);
        }
        return sqlBuilder.toString();
    }

    /**
     * 构建创建数据库的SQL语句
     *
     * @param database 数据库对象
     * @return 创建数据库的SQL语句
     */
    @Override
    public String buildCreateDatabaseSql(Database database) {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("CREATE DATABASE `").append(database.getName()).append("`");
        if (StringUtils.isNotBlank(database.getCharset())) {
            sqlBuilder.append(" DEFAULT CHARACTER SET=").append(database.getCharset());
        }
        if (StringUtils.isNotBlank(database.getCollation())) {
            sqlBuilder.append(" COLLATE=").append(database.getCollation());
        }
        return sqlBuilder.toString();
    }

    @Override
    public String buildGenerateReorderColumnSql(Table oldTable, Table newTable) {
        List<String> oldColumns = oldTable.getColumnList().stream()
                .filter(column -> !EditStatus.DELETE.name().equals(column.getEditStatus()))
                .map(TableColumn::getName)
                .collect(Collectors.toList());
        List<String> targetColumns = newTable.getColumnList().stream()
                .filter(column -> !EditStatus.ADD.name().equals(column.getEditStatus()))
                .map(column -> StringUtils.isNotBlank(column.getOldName()) ? column.getOldName() : column.getName())
                .collect(Collectors.toList());
        // 初始化SQL构建器
        StringBuilder sql = new StringBuilder();
        if (!oldColumns.equals(targetColumns)) {
            sql.append("ALTER TABLE ");
            if (StringUtils.isNotBlank(oldTable.getDatabaseName())) {
                sql.append("`").append(oldTable.getDatabaseName()).append("`.");
            }
            sql.append("`").append(oldTable.getName()).append("`\n");
            buildReorderStatements(oldColumns, targetColumns, oldTable, sql);
        }
        return sql.toString();
    }

    private void buildReorderStatements(List<String> currentColumns,
            List<String> targetColumns,
            Table table,
            StringBuilder sql) {
        int steps = 0;
        while (!currentColumns.equals(targetColumns)) {
            // 寻找第一个不匹配的位置
            int firstMismatch = findFirstMismatch(currentColumns, targetColumns);
            if (firstMismatch == -1) {
                break;
            }
            String expectedColumn = targetColumns.get(firstMismatch);
            int currentPosition = currentColumns.indexOf(expectedColumn);
            // 构建MODIFY COLUMN语句
            TableColumn column = table.getColumnList().stream()
                    .filter(c -> c.getName().equals(expectedColumn))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Column not found: " + expectedColumn));
            if (steps++ > 0) {
                sql.append(",\n");
            }
            sql.append("MODIFY COLUMN ")
                    .append(buildColumnDefinition(column))
                    .append(" ");
            // 确定移动位置
            if (firstMismatch == 0) {
                sql.append("FIRST");
            } else {
                String afterColumn = targetColumns.get(firstMismatch - 1);
                sql.append("AFTER `").append(afterColumn).append("`");
            }
            // 更新当前列顺序
            currentColumns.remove(currentPosition);
            currentColumns.add(firstMismatch, expectedColumn);
        }
        sql.append(";");
    }

    // 辅助方法：构建列定义
    private String buildColumnDefinition(TableColumn column) {
        MysqlColumnTypeEnum type = MysqlColumnTypeEnum.getColumnTypeEnum(column);
        if (type == null) {
            return "";
        }
        return type.buildColumn(column);
    }

    // 辅助方法：找到第一个不匹配的位置
    private int findFirstMismatch(List<String> list1, List<String> list2) {
        int minLength = Math.min(list1.size(), list2.size());
        for (int i = 0; i < minLength; i++) {
            if (!list1.get(i).equals(list2.get(i))) {
                return i;
            }
        }
        return list1.size() == list2.size() ? -1 : minLength;
    }

    @Override
    protected String buildImportUpsertSql(String tableName, List<Header> headerList, List<String> primaryKeyColumns,
                                          MetaData metaSchema) {
        // MySQL: INSERT INTO ... ON DUPLICATE KEY UPDATE col=VALUES(col)
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(tableName).append(" (");
        for (int i = 0; i < headerList.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append(metaSchema.getMetaDataName(headerList.get(i).getName()));
        }
        sql.append(") VALUES (");
        for (int i = 0; i < headerList.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append("?");
        }
        sql.append(") ON DUPLICATE KEY UPDATE ");
        boolean first = true;
        for (Header header : headerList) {
            if (primaryKeyColumns != null && primaryKeyColumns.contains(header.getName())) {
                continue;
            }
            if (!first) sql.append(",");
            String quotedName = metaSchema.getMetaDataName(header.getName());
            sql.append(quotedName).append("=VALUES(").append(quotedName).append(")");
            first = false;
        }
        return sql.toString();
    }

    @Override
    protected String buildImportInsertIgnoreSql(String tableName, List<Header> headerList, MetaData metaSchema) {
        // MySQL: INSERT IGNORE INTO ...
        StringBuilder sql = new StringBuilder("INSERT IGNORE INTO ");
        sql.append(tableName).append(" (");
        for (int i = 0; i < headerList.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append(metaSchema.getMetaDataName(headerList.get(i).getName()));
        }
        sql.append(") VALUES (");
        for (int i = 0; i < headerList.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append("?");
        }
        sql.append(")");
        return sql.toString();
    }

    @Override
    public String buildOptimizeTableSql(String databaseName, String schemaName, String tableName) {
        return "OPTIMIZE TABLE `" + databaseName + "`.`" + tableName + "`";
    }

    @Override
    public String buildAnalyzeTableSql(String databaseName, String schemaName, String tableName) {
        return "ANALYZE TABLE `" + databaseName + "`.`" + tableName + "`";
    }

    @Override
    public String buildExplainSql(String originalSql) {
        return "EXPLAIN " + originalSql;
    }

}
