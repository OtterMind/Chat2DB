package ai.chat2db.plugin.clickhouse.builder;

import ai.chat2db.plugin.clickhouse.type.ClickHouseColumnTypeEnum;
import ai.chat2db.plugin.clickhouse.type.ClickHouseIndexTypeEnum;
import ai.chat2db.spi.jdbc.DefaultSqlBuilder;
import ai.chat2db.spi.model.Database;
import ai.chat2db.spi.model.Table;
import ai.chat2db.spi.model.TableColumn;
import ai.chat2db.spi.model.TableIndex;
import org.apache.commons.lang3.StringUtils;

import java.util.List;


public class ClickHouseSqlBuilder extends DefaultSqlBuilder {
    @Override
    public String buildCreateTableSql(Table table) {
        // Initialize StringBuilder to build the SQL script
        StringBuilder script = new StringBuilder("CREATE TABLE ");
        
        // Append the database name, if present
        appendDatabaseName(script, table.getDatabaseName());
        
        // Append the table name
        script.append("`").append(table.getName()).append("`").append(" (").append("\n");

        // append column
        appendColumns(script, table.getColumnList());

        // append index
        appendIndexes(script, table.getIndexList());

        // Remove the last comma
        script = new StringBuilder(script.substring(0, script.length() - 2));
        script.append("\n)");

        // Append the engine, if present
       appendEngine(script, table.getEngine());

       // append primary key
       appendPrimaryKey(script, table.getIndexList());
       
        // Append the comment, if present
        appendComment(script, table.getComment());

         // Append a semicolon to complete the SQL statement
        script.append(";");
       
        // Return the complete SQL script
        return script.toString();
    }

    // Method to append the database name to the SQL script
    private void appendDatabaseName(StringBuilder script, String databaseName) {
        if (StringUtils.isNotBlank(databaseName)) {
            script.append("`").append(databaseName).append("`.");
        }
    }

    // Method to append columns to the SQL script
    private void appendColumns(StringBuilder script, List<TableColumn> columns) {
        for (TableColumn column : columns) {
            // Check if column name and type are not blank
            if (StringUtils.isNotBlank(column.getName()) && StringUtils.isNotBlank(column.getColumnType())) {
                // Get the column type enum and append the column SQL to the script
                ClickHouseColumnTypeEnum typeEnum = ClickHouseColumnTypeEnum.getByType(column.getColumnType());
                script.append("\t").append(typeEnum.buildCreateColumnSql(column)).append(",\n");
            }
        }
    }

    // Method to append indexes to the SQL script
    private void appendIndexes(StringBuilder script, List<TableIndex> indexes) {
        for (TableIndex index : indexes) {
            // Check if index name and type are not blank
            if (StringUtils.isNotBlank(index.getName()) && StringUtils.isNotBlank(index.getType())) {
                // Get the index type enum and append the index script to the script
                ClickHouseIndexTypeEnum indexTypeEnum = ClickHouseIndexTypeEnum.getByType(index.getType());
                if (!ClickHouseIndexTypeEnum.PRIMARY.equals(indexTypeEnum)) {
                    script.append("\t").append(indexTypeEnum.buildIndexScript(index)).append(",\n");
                }
            }
        }
    }

    // Method to append the engine to the SQL script
    private void appendEngine(StringBuilder script, String engine) {
        if (StringUtils.isNotBlank(engine)) {
            script.append(" ENGINE=").append(engine).append("\n");
        }
    }

    // Method to append the primary key to the SQL script
    private void appendPrimaryKey(StringBuilder script, List<TableIndex> indexes) {
        for (TableIndex index : indexes) {
            // Check if index name and type are not blank
            if (StringUtils.isNotBlank(index.getName()) && StringUtils.isNotBlank(index.getType())) {
                // Get the index type enum and append the index script to the script
                ClickHouseIndexTypeEnum indexTypeEnum = ClickHouseIndexTypeEnum.getByType(index.getType());
                if (ClickHouseIndexTypeEnum.PRIMARY.equals(indexTypeEnum)) {
                    script.append("\t").append(indexTypeEnum.buildIndexScript(index)).append("\n");
                }
            }
        }
    }

    // Method to append the comment to the SQL script
    private void appendComment(StringBuilder script, String comment) {
        if (StringUtils.isNotBlank(comment)) {
            script.append(" COMMENT '").append(comment).append("'");
        }
    }

    @Override
    public String buildModifyTaleSql(Table oldTable, Table newTable) {
        StringBuilder script = new StringBuilder();
        script.append("ALTER TABLE ");
        if (StringUtils.isNotBlank(oldTable.getDatabaseName())) {
            script.append("`").append(oldTable.getDatabaseName()).append("`").append(".");
        }
        script.append("`").append(oldTable.getName()).append("`").append("\n");

        if (!StringUtils.equalsIgnoreCase(oldTable.getComment(), newTable.getComment())) {
            script.append("\t").append("MODIFY COMMENT").append("'").append(newTable.getComment()).append("'").append(",\n");
        }

        // append modify column
        for (TableColumn tableColumn : newTable.getColumnList()) {
            if (StringUtils.isNotBlank(tableColumn.getEditStatus()) && StringUtils.isNotBlank(tableColumn.getColumnType()) && StringUtils.isNotBlank(tableColumn.getName())) {
                ClickHouseColumnTypeEnum typeEnum = ClickHouseColumnTypeEnum.getByType(tableColumn.getColumnType());
                if(typeEnum == null){
                    continue;
                }
                script.append("\t").append(typeEnum.buildModifyColumn(tableColumn)).append(",\n");
            }
        }

        // append modify index
        for (TableIndex tableIndex : newTable.getIndexList()) {
            if (StringUtils.isNotBlank(tableIndex.getEditStatus()) && StringUtils.isNotBlank(tableIndex.getType())) {
                ClickHouseIndexTypeEnum clickHouseIndexTypeEnum = ClickHouseIndexTypeEnum
                        .getByType(tableIndex.getType());
                if(clickHouseIndexTypeEnum == null){
                    continue;
                }
                script.append("\t").append(clickHouseIndexTypeEnum.buildModifyIndex(tableIndex)).append(",\n");
            }
        }

        if (script.length() > 2) {
            script = new StringBuilder(script.substring(0, script.length() - 2));
            script.append(";");
        }

        return script.toString();
    }


    @Override
    public String pageLimit(String sql, int offset, int pageNo, int pageSize) {
        StringBuilder sqlBuilder = new StringBuilder(sql.length() + 14);
        sqlBuilder.append(sql);
        if (offset == 0) {
            sqlBuilder.append("\n LIMIT ");
            sqlBuilder.append(pageSize);
        } else {
            sqlBuilder.append("\n LIMIT ");
            sqlBuilder.append(offset);
            sqlBuilder.append(",");
            sqlBuilder.append(pageSize);
        }
        return sqlBuilder.toString();
    }


    @Override
    public String buildCreateDatabaseSql(Database database) {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("CREATE DATABASE `" + database.getName() + "`");
        if(StringUtils.isNotBlank(database.getComment())){
            sqlBuilder.append(";ALTER DATABASE ").append(database.getName()).append(" COMMENT '").append(database.getComment()).append("';");
        }
        return sqlBuilder.toString();
    }

}
