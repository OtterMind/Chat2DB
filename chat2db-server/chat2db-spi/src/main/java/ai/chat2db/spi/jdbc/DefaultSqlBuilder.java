package ai.chat2db.spi.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Lists;

import ai.chat2db.spi.MetaData;
import ai.chat2db.spi.SqlBuilder;
import ai.chat2db.spi.model.Database;
import ai.chat2db.spi.model.ForeignKey;
import ai.chat2db.spi.model.Header;
import ai.chat2db.spi.model.OrderBy;
import ai.chat2db.spi.model.ResultOperation;
import ai.chat2db.spi.model.Schema;
import ai.chat2db.spi.model.Table;
import ai.chat2db.spi.model.TableColumn;
import ai.chat2db.spi.model.TableIndex;
import ai.chat2db.spi.model.TableIndexColumn;
import ai.chat2db.spi.model.VirtualForeignKey;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.util.SqlUtils;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

public class DefaultSqlBuilder implements SqlBuilder {


    @Override
    public String buildCreateTableSql(Table table) {
        StringBuilder script = new StringBuilder();
        if(StringUtils.isNotBlank(table.getAiComment())){
            script.append(" -- ").append(table.getAiComment()).append("\n");
        }
        script.append("CREATE TABLE ");

        // 添加数据库名
        if (StringUtils.isNotBlank(table.getDatabaseName())) {
            script.append("`").append(table.getDatabaseName()).append("`").append(".");
        }
        script.append("`").append(table.getName()).append("`").append(" (").append("\n");

        // 添加列
        appendColumns(script, table.getColumnList());

        // 添加索引
        appendIndexes(script, table.getIndexList());

        // 添加外键约束
        appendForeignKeys(script, table.getForeignKeyList());

        // 添加虚拟外键约束
        appendVirtualForeignKeys(script, table.getVirtualForeignKeyList());

        // 移除最后的逗号
        if (script.length() > 2) {
            script.setLength(script.length() - 2);
        }
        script.append("\n)");

        // 添加表的其他属性
        appendTableAttributes(script, table);

        script.append(";");

        return script.toString();
    }

    protected void appendTableAttributes(StringBuilder script, Table table) {
        // 添加表的存储引擎
        if (table.getEngine() != null) {
            script.append(" ENGINE = ").append(table.getEngine());
        }

        // 添加字符集
        if (table.getCharset() != null) {
            script.append(" DEFAULT CHARSET = ").append(table.getCharset());
        }

        // 添加排序规则
        if (table.getCollate() != null) {
            script.append(" COLLATE = ").append(table.getCollate());
        }

        // 添加分区信息
        if (table.getPartition() != null) {
            script.append(" PARTITION BY ").append(table.getPartition());
        }

        // 添加表空间信息
        if (table.getTablespace() != null) {
            script.append(" TABLESPACE = ").append(table.getTablespace());
        }

        // 添加注释
        if (table.getComment() != null) {
            script.append(" COMMENT = '").append(table.getComment()).append("'");
        }
    }

    protected void appendIndexes(StringBuilder script, List<TableIndex> indexList) {
        for (TableIndex index : indexList) {
            script.append("    INDEX `").append(index.getName()).append("` (");

            // 添加索引包含的列
            for (TableIndexColumn column : index.getColumnList()) {
                script.append("`").append(column.getColumnName()).append("`, ");
            }

            // 移除最后的逗号
            if (!script.isEmpty()) {
                script.setLength(script.length() - 2);
            }

            script.append(")");

            // 添加索引的其他属性
            if (Boolean.TRUE.equals(index.getUnique())) {
                script.append(" UNIQUE");
            }

            if (index.getComment() != null) {
                script.append(" COMMENT '").append(index.getComment()).append("'");
            }

            script.append(",\n");
        }
    }

    protected void appendForeignKeys(StringBuilder script, List<ForeignKey> foreignKeyList) {
        if (CollectionUtils.isEmpty(foreignKeyList)) {
            return;
        }
        for (ForeignKey fk : foreignKeyList) {
            script.append("    ").append(buildForeignKeyClause(fk)).append(",\n");
        }
    }

    protected void appendVirtualForeignKeys(StringBuilder script, List<VirtualForeignKey> virtualForeignKeyList) {
        if (CollectionUtils.isEmpty(virtualForeignKeyList)) {
            return;
        }
        for (VirtualForeignKey fk : virtualForeignKeyList) {
            script.append("    ").append(buildForeignKeyClause(fk)).append(",\n");
        }
    }

    protected void appendColumns(StringBuilder script, List<TableColumn> columnList) {
        for (TableColumn column : columnList) {
            script.append("    `").append(column.getName()).append("` ")
                    .append(column.getDataType());

            // 添加列的大小（如果适用）
            if (column.getColumnSize() != null && column.getColumnSize() > 0) {
                script.append("(").append(column.getColumnSize());
                if (column.getDecimalDigits() != null && column.getDecimalDigits() > 0) {
                    script.append(", ").append(column.getDecimalDigits());
                }
                script.append(")");
            }

            // 添加是否自增
            if (Boolean.TRUE.equals(column.getAutoIncrement())) {
                script.append(" AUTO_INCREMENT");
            }

            // 添加默认值
            if (column.getDefaultValue() != null) {
                script.append(" DEFAULT '").append(column.getDefaultValue()).append("'");
            }

            // 添加注释
            if (StringUtils.isNotBlank(column.getComment())) {
                script.append(" COMMENT '").append(column.getComment()).append("'");
            }

            // 添加是否主键
            if (Boolean.TRUE.equals(column.getPrimaryKey())) {
                script.append(" PRIMARY KEY");
            }

            // 添加注释
            if (StringUtils.isNotBlank(column.getAiComment())) {
                script.append(" -- ").append(column.getAiComment());
            }
            script.append(",\n");
        }
    }

    @Override
    public String buildModifyTaleSql(Table oldTable, Table newTable) {
        if (oldTable.equals(newTable)) {
            return "";
        }
        StringBuilder script = new StringBuilder();
        script.append("ALTER TABLE ");

        // 添加数据库名
        if (StringUtils.isNotBlank(oldTable.getDatabaseName())) {
            script.append("`").append(oldTable.getDatabaseName()).append("`").append(".");
        }
        script.append("`").append(oldTable.getName()).append("`").append("\n");

        // 修改表名和注释
        modifyTableNameAndComment(script, oldTable, newTable);

        // 修改列
        modifyColumns(script, oldTable, newTable);

        // 修改索引
        modifyIndexes(script, oldTable, newTable);

        // 修改外键
        modifyForeignKeys(script, oldTable, newTable);

        // 添加列重排逻辑
        script.append(buildGenerateReorderColumnSql(oldTable, newTable));

        // 移除最后的逗号
        if (script.length() > 2) {
            script.setLength(script.length() - 2);
            script.append(";");
        }

        return script.toString();
    }

    // 修改表名和注释的方法
    protected void modifyTableNameAndComment(StringBuilder script, Table oldTable, Table newTable) {
        if (!StringUtils.equalsIgnoreCase(oldTable.getName(), newTable.getName())) {
            script.append("\t").append("RENAME TO ").append("`").append(newTable.getName()).append("`").append(",\n");
        }
        if (!StringUtils.equalsIgnoreCase(oldTable.getComment(), newTable.getComment())) {
            script.append("\t").append("COMMENT=").append("'").append(newTable.getComment()).append("'").append(",\n");
        }
        if (newTable.getIncrementValue() != null
                && !Objects.equals(oldTable.getIncrementValue(), newTable.getIncrementValue())) {
            script.append("\t").append("AUTO_INCREMENT=").append(newTable.getIncrementValue()).append(",\n");
        }
    }

    // 修改列的方法
    protected void modifyColumns(StringBuilder script, Table oldTable, Table newTable) {
    }

    // 修改索引的方法
    protected void modifyIndexes(StringBuilder script, Table oldTable, Table newTable) {
    }

    protected void modifyForeignKeys(StringBuilder script, Table oldTable, Table newTable) {
        List<ForeignKey> oldFKs = oldTable != null && oldTable.getForeignKeyList() != null
                ? oldTable.getForeignKeyList() : Lists.newArrayList();
        List<ForeignKey> newFKs = newTable != null && newTable.getForeignKeyList() != null
                ? newTable.getForeignKeyList() : Lists.newArrayList();

        java.util.Map<String, ForeignKey> oldFKMap = oldFKs.stream()
                .collect(java.util.stream.Collectors.toMap(this::buildFKKey, f -> f, (o1, o2) -> o1));
        java.util.Map<String, ForeignKey> newFKMap = newFKs.stream()
                .collect(java.util.stream.Collectors.toMap(this::buildFKKey, f -> f, (o1, o2) -> o1));

        for (ForeignKey newFK : newFKs) {
            if (!oldFKMap.containsKey(buildFKKey(newFK))) {
                script.append("\t").append("ADD ").append(buildForeignKeyClause(newFK)).append(",\n");
            }
        }

        for (ForeignKey oldFK : oldFKs) {
            if (!newFKMap.containsKey(buildFKKey(oldFK))) {
                script.append("\t").append("DROP FOREIGN KEY `").append(oldFK.getName()).append("`,\n");
            }
        }
    }

    private String buildFKKey(ForeignKey fk) {
        return StringUtils.defaultString(fk.getTableName()) + ":"
                + StringUtils.defaultString(fk.getColumn()) + ":"
                + StringUtils.defaultString(fk.getReferencedTable()) + ":"
                + StringUtils.defaultString(fk.getReferencedColumn());
    }

    protected String buildForeignKeyClause(ForeignKey fk) {
        StringBuilder script = new StringBuilder();
        script.append("CONSTRAINT `").append(fk.getName()).append("` FOREIGN KEY (`")
                .append(fk.getColumn()).append("`) REFERENCES `")
                .append(fk.getReferencedTable()).append("` (`")
                .append(fk.getReferencedColumn()).append("`)");

        if (fk.getDeleteRule() == 0) {
            script.append(" ON DELETE CASCADE");
        } else if (fk.getDeleteRule() == 1) {
            script.append(" ON DELETE RESTRICT");
        } else if (fk.getDeleteRule() == 2) {
            script.append(" ON DELETE SET NULL");
        }
        if (fk.getUpdateRule() == 0) {
            script.append(" ON UPDATE CASCADE");
        } else if (fk.getUpdateRule() == 1) {
            script.append(" ON UPDATE RESTRICT");
        } else if (fk.getUpdateRule() == 2) {
            script.append(" ON UPDATE SET NULL");
        }
        return script.toString();
    }

    public String buildAddForeignKeySql(ForeignKey fk) {
        StringBuilder script = new StringBuilder();
        script.append("ALTER TABLE ");
        if (StringUtils.isNotBlank(fk.getDatabaseName())) {
            script.append("`").append(fk.getDatabaseName()).append("`.`");
        }
        script.append("`").append(fk.getTableName()).append("` ADD ")
                .append(buildForeignKeyClause(fk)).append(";");
        return script.toString();
    }

    public String buildDropForeignKeySql(ForeignKey fk) {
        StringBuilder script = new StringBuilder();
        script.append("ALTER TABLE ");
        if (StringUtils.isNotBlank(fk.getDatabaseName())) {
            script.append("`").append(fk.getDatabaseName()).append("`.`");
        }
        script.append("`").append(fk.getTableName()).append("` DROP FOREIGN KEY `")
                .append(fk.getName()).append("`;");
        return script.toString();
    }

    protected String buildGenerateReorderColumnSql(Table oldTable, Table newTable) {
        return "";
    }


    @Override
    public String pageLimit(String sql, int offset, int pageNo, int pageSize) {
        return null;
    }

    public static String CREATE_DATABASE_SQL = "CREATE DATABASE IF NOT EXISTS `%s` DEFAULT CHARACTER SET %s COLLATE %s";

    @Override
    public String buildCreateDatabaseSql(Database database) {
        return null;
    }

    @Override
    public String buildModifyDatabaseSql(Database oldDatabase, Database newDatabase) {
        return null;
    }

    @Override
    public String buildCreateSchemaSql(Schema schema) {
        return null;
    }

    @Override
    public String buildModifySchemaSql(String oldSchemaName, String newSchemaName) {
        return null;
    }

    @Override
    public String buildOrderBySql(String originSql, List<OrderBy> orderByList) {
        if (CollectionUtils.isEmpty(orderByList)) {
            return originSql;
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(originSql);
            if (statement instanceof Select) {
                Select selectStatement = (Select) statement;
                PlainSelect plainSelect = (PlainSelect) selectStatement.getSelectBody();

                // 创建新的 ORDER BY 子句
                List<OrderByElement> orderByElements = new ArrayList<>();

                for (OrderBy orderBy : orderByList) {
                    OrderByElement orderByElement = new OrderByElement();
                    orderByElement.setExpression(CCJSqlParserUtil.parseExpression(orderBy.getColumnName()));
                    orderByElement.setAsc(orderBy.isAsc()); // 设置为升序，使用 setAsc(false) 设置为降序
                    orderByElements.add(orderByElement);
                }
                // 替换原有的 ORDER BY 子句
                plainSelect.setOrderByElements(orderByElements);
                // 输出修改后的 SQL
                return plainSelect.toString();
            }
        } catch (Exception e) {
        }
        return originSql;
    }

    @Override
    public String generateSqlBasedOnResults(String tableName, List<Header> headerList, List<ResultOperation> operations) {

        StringBuilder stringBuilder = new StringBuilder();
        MetaData metaSchema = Chat2DBContext.getMetaData();
        List<String> keyColumns = getPrimaryColumns(headerList);
        for (int i = 0; i < operations.size(); i++) {
            ResultOperation operation = operations.get(i);
            List<String> row = operation.getDataList();
            List<String> odlRow = operation.getOldDataList();
            String sql = "";
            if ("UPDATE".equalsIgnoreCase(operation.getType())) {
                sql = getUpdateSql(tableName, headerList, row, odlRow, metaSchema, keyColumns, false);
            } else if ("CREATE".equalsIgnoreCase(operation.getType())) {
                sql = getInsertSql(tableName, headerList, row, metaSchema);
            } else if ("DELETE".equalsIgnoreCase(operation.getType())) {
                sql = getDeleteSql(tableName, headerList, odlRow, metaSchema, keyColumns);
            } else if ("UPDATE_COPY".equalsIgnoreCase(operation.getType())) {
                sql = getUpdateSql(tableName, headerList, row, row, metaSchema, keyColumns, true);
            }

            stringBuilder.append(sql + ";\n");
        }
        return stringBuilder.toString();
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

    private String getDeleteSql(String tableName, List<Header> headerList, List<String> row, MetaData metaSchema,
                                List<String> keyColumns) {
        StringBuilder script = new StringBuilder();
        script.append("DELETE FROM ").append(tableName).append("");

        script.append(buildWhere(headerList, row, metaSchema, keyColumns));
        return script.toString();
    }

    private String buildWhere(List<Header> headerList, List<String> row, MetaData metaSchema, List<String> keyColumns) {
        StringBuilder script = new StringBuilder();
        script.append(" where ");
        if (CollectionUtils.isEmpty(keyColumns)) {
            for (int i = 1; i < row.size(); i++) {
                String oldValue = row.get(i);
                Header header = headerList.get(i);
                String value = SqlUtils.getSqlValue(oldValue, header.getDataType());
                if (value == null) {
                    script.append(metaSchema.getMetaDataName(header.getName()))
                            .append(" is null and ");
                } else {
                    script.append(metaSchema.getMetaDataName(header.getName()))
                            .append(" = ")
                            .append(value)
                            .append(" and ");
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
                                .append(" is null and ");
                    } else {
                        script.append(metaSchema.getMetaDataName(columnName))
                                .append(" = ")
                                .append(value)
                                .append(" and ");
                    }
                }
            }
        }
        script.delete(script.length() - 4, script.length());
        return script.toString();
    }

    private String getInsertSql(String tableName, List<Header> headerList, List<String> row, MetaData metaSchema) {
        if (CollectionUtils.isEmpty(row) || ObjectUtils.allNull(row.toArray())) {
            return "";
        }
        StringBuilder script = new StringBuilder();
        script.append("INSERT INTO ").append(tableName)
                .append(" (");
        for (int i = 1; i < row.size(); i++) {
            Header header = headerList.get(i);
            //String newValue = row.get(i);
            //if (newValue != null) {
            script.append(metaSchema.getMetaDataName(header.getName()))
                    .append(",");
            // }
        }
        script.deleteCharAt(script.length() - 1);
        script.append(") VALUES (");
        for (int i = 1; i < row.size(); i++) {
            String newValue = row.get(i);
            //if (newValue != null) {
            Header header = headerList.get(i);
            script.append(SqlUtils.getSqlValue(newValue, header.getDataType()))
                    .append(",");
            //}
        }
        script.deleteCharAt(script.length() - 1);
        script.append(")");
        return script.toString();

    }

    private String getUpdateSql(String tableName, List<Header> headerList, List<String> row, List<String> odlRow,
                                MetaData metaSchema,
                                List<String> keyColumns, boolean copy) {
        StringBuilder script = new StringBuilder();
        if (CollectionUtils.isEmpty(row) || CollectionUtils.isEmpty(odlRow)) {
            return "";
        }
        script.append("UPDATE ").append(tableName).append(" set ");
        for (int i = 1; i < row.size(); i++) {
            String newValue = row.get(i);
            String oldValue = odlRow.get(i);
            if (StringUtils.equals(newValue, oldValue) && !copy) {
                continue;
            }
            Header header = headerList.get(i);
            String newSqlValue = SqlUtils.getSqlValue(newValue, header.getDataType());
            script.append(metaSchema.getMetaDataName(header.getName()))
                    .append(" = ")
                    .append(newSqlValue)
                    .append(",");
        }
        script.deleteCharAt(script.length() - 1);
        script.append(buildWhere(headerList, odlRow, metaSchema, keyColumns));
        return script.toString();
    }

    @Override
    public String buildImportSql(String tableName, List<Header> headerList, List<String> primaryKeyColumns, String mode) {
        MetaData metaSchema = Chat2DBContext.getMetaData();
        switch (mode) {
            case "INSERT":
                return buildImportInsertSql(tableName, headerList, metaSchema);
            case "UPDATE":
                return buildImportUpdateSql(tableName, headerList, primaryKeyColumns, metaSchema);
            case "UPSERT":
                return buildImportUpsertSql(tableName, headerList, primaryKeyColumns, metaSchema);
            case "INSERT_IGNORE":
                return buildImportInsertIgnoreSql(tableName, headerList, metaSchema);
            case "DELETE":
                return buildImportDeleteSql(tableName, headerList, primaryKeyColumns, metaSchema);
            default:
                return buildImportInsertSql(tableName, headerList, metaSchema);
        }
    }

    protected String buildImportInsertSql(String tableName, List<Header> headerList, MetaData metaSchema) {
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
        sql.append(")");
        return sql.toString();
    }

    protected String buildImportUpdateSql(String tableName, List<Header> headerList, List<String> primaryKeyColumns,
                                          MetaData metaSchema) {
        StringBuilder sql = new StringBuilder("UPDATE ");
        sql.append(tableName).append(" SET ");
        boolean first = true;
        for (Header header : headerList) {
            if (primaryKeyColumns != null && primaryKeyColumns.contains(header.getName())) {
                continue;
            }
            if (!first) sql.append(",");
            sql.append(metaSchema.getMetaDataName(header.getName())).append("=?");
            first = false;
        }
        sql.append(" WHERE ");
        first = true;
        if (primaryKeyColumns != null && !primaryKeyColumns.isEmpty()) {
            for (String pk : primaryKeyColumns) {
                if (!first) sql.append(" AND ");
                sql.append(metaSchema.getMetaDataName(pk)).append("=?");
                first = false;
            }
        } else {
            // 无主键时使用所有列作为匹配条件（不推荐，但作为兜底）
            for (Header header : headerList) {
                if (!first) sql.append(" AND ");
                sql.append(metaSchema.getMetaDataName(header.getName())).append("=?");
                first = false;
            }
        }
        return sql.toString();
    }

    protected String buildImportUpsertSql(String tableName, List<Header> headerList, List<String> primaryKeyColumns,
                                          MetaData metaSchema) {
        // 默认使用INSERT实现
        return buildImportInsertSql(tableName, headerList, metaSchema);
    }

    protected String buildImportInsertIgnoreSql(String tableName, List<Header> headerList, MetaData metaSchema) {
        // 默认使用INSERT实现
        return buildImportInsertSql(tableName, headerList, metaSchema);
    }

    protected String buildImportDeleteSql(String tableName, List<Header> headerList, List<String> primaryKeyColumns,
                                          MetaData metaSchema) {
        StringBuilder sql = new StringBuilder("DELETE FROM ");
        sql.append(tableName).append(" WHERE ");
        if (primaryKeyColumns != null && !primaryKeyColumns.isEmpty()) {
            for (int i = 0; i < primaryKeyColumns.size(); i++) {
                if (i > 0) sql.append(" AND ");
                sql.append(metaSchema.getMetaDataName(primaryKeyColumns.get(i))).append("=?");
            }
        } else {
            // 无主键时使用所有列作为匹配条件
            for (int i = 0; i < headerList.size(); i++) {
                if (i > 0) sql.append(" AND ");
                sql.append(metaSchema.getMetaDataName(headerList.get(i).getName())).append("=?");
            }
        }
        return sql.toString();
    }
}
