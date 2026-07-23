package ai.chat2db.spi.util;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.enums.plugin.IndexTypeEnum;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;

public class DBStructUtils {

    private static final String SQL_CREATE = "CREATE ";
    private static final String SQL_CREATE_TABLE = "CREATE TABLE ";
    private static final String SQL_ON = " ON ";


    public static String getTableDdl(Connection connection, String databaseName, String schemaName, String tableName) {
        List<TableColumn> columns = DefaultSQLExecutor.getInstance().columns(connection, databaseName, schemaName, tableName, null);
        List<TableIndex> indexList = DefaultSQLExecutor.getInstance().indexes(connection, databaseName, schemaName, tableName);
        String createTable = generateCreateTableSQL(tableName, columns);
        String createIndex = generateCreateIndexSQL(tableName, indexList);
        return createTable + "\n" + createIndex;
    }


    private static String generateCreateIndexSQL(String tableName, List<TableIndex> indexList) {
        StringBuilder createIndexSQL = new StringBuilder();
        for (TableIndex index : indexList) {
            String indexName = index.getName();
            List<TableIndexColumn> columns = index.getColumnList();
            StringBuilder columnNames = new StringBuilder();
            for (TableIndexColumn column : columns) {
                if (columnNames.length() > 0) {
                    columnNames.append(", ");
                }
                columnNames.append(column.getColumnName());
            }
            boolean unique = index.getUnique() == null ? false : index.getUnique();
            String indexType = unique ? "UNIQUE INDEX " : "INDEX ";
            createIndexSQL.append(SQL_CREATE + indexType + indexName + SQL_ON + tableName + " (" + columnNames + ");\n");
        }
        return createIndexSQL.toString();
    }


    protected static String generateCreateTableSQL(String tableName, List<TableColumn> columns) {
        StringBuilder createTableSQL = new StringBuilder(SQL_CREATE_TABLE + tableName + " (\n");
        boolean firstColumn = true;
        for (TableColumn column : columns) {
            if (!firstColumn) {
                createTableSQL.append(",\n");
            }
            String columnName = column.getName();
            String dataType = column.getColumnType();
            String nullable = Objects.equals(column.getNullable(), 0) ? " NOT NULL" : "";
            Integer columnSize = column.getColumnSize();
            Integer decimalDigits = column.getDecimalDigits();
            String columnComment = column.getComment();
            String commentClause = (columnComment != null && !columnComment.isEmpty()) ? " COMMENT '" + columnComment + "'" : "";
            String columnDefinition = columnName + " " + dataType;

            if ((dataType.equalsIgnoreCase("VARCHAR") || dataType.equalsIgnoreCase("CHAR")) && columnSize != null) {
                columnDefinition += "(" + columnSize + ")";
            } else if ((dataType.equalsIgnoreCase("DECIMAL") || dataType.equalsIgnoreCase("NUMERIC")) && columnSize != null) {
                columnDefinition += decimalDigits == null ? "(" + columnSize + ")" : "(" + columnSize + "," + decimalDigits + ")";
            }
            columnDefinition += nullable + commentClause;
            createTableSQL.append("\t" + columnDefinition);
            firstColumn = false;
        }
        createTableSQL.append("\n);");
        return createTableSQL.toString();
    }


    public static String buildAlterTable(Table oldTable, Table newTable) {

        StringBuilder script = new StringBuilder();
        if (!StringUtils.equalsIgnoreCase(oldTable.getName(), newTable.getName())) {
            script.append(generateUpateTableNameSQL(oldTable.getName(), newTable.getName())).append("\n");
        }
        if (!StringUtils.equalsIgnoreCase(oldTable.getComment(), newTable.getComment())) {
            script.append(generateTableCommentSQL(newTable.getName(), newTable.getComment())).append("\n");
        }
        for (TableColumn tableColumn : newTable.getColumnList()) {
            if (StringUtils.isNotBlank(tableColumn.getEditStatus()) && StringUtils.isNotBlank(tableColumn.getColumnType())
                    && StringUtils.isNotBlank(tableColumn.getName())) {
                script.append(generateUpateTableColumnSQL(tableColumn)).append("\n");
            }
        }
        for (TableIndex tableIndex : newTable.getIndexList()) {
            if (StringUtils.isNotBlank(tableIndex.getEditStatus()) && StringUtils.isNotBlank(tableIndex.getType())) {
                script.append(generateUpateIndexSQL(tableIndex)).append("\n");
            }
        }

        return script.toString();
    }

    private static String generateUpateIndexSQL(TableIndex tableIndex) {
        if (EditStatusEnum.DELETE.name().equals(tableIndex.getEditStatus())) {
            return "DROP INDEX " + tableIndex.getName() + ";";
        }
        if (EditStatusEnum.ADD.name().equals(tableIndex.getEditStatus())) {
            StringBuilder columnNames = new StringBuilder();
            for (TableIndexColumn column : tableIndex.getColumnList()) {
                if (columnNames.length() > 0) {
                    columnNames.append(", ");
                }
                columnNames.append(column.getColumnName());
            }
            boolean unique = IndexTypeEnum.UNIQUE.getName().equals(tableIndex.getType());
            return "CREATE " + (unique ? "UNIQUE " : "") + "INDEX " + tableIndex.getName() + " ON " + tableIndex.getTableName() + " (" + columnNames + ");";
        }
        return "";
    }

    private static String generateUpateTableNameSQL(String name, String name1) {
        return "ALTER TABLE " + name + " RENAME TO " + name1 + ";";
    }

    private static String generateUpateTableColumnSQL(TableColumn tableColumn) {
        if (EditStatusEnum.DELETE.name().equals(tableColumn.getEditStatus())) {
            return "ALTER TABLE " + tableColumn.getTableName() + " DROP COLUMN " + tableColumn.getName() + ";";
        }
        if (EditStatusEnum.ADD.name().equals(tableColumn.getEditStatus())) {
            return "ALTER TABLE " + tableColumn.getTableName() + " ADD COLUMN " + tableColumn.getName() + " " + tableColumn.getColumnType() + ";";
        }
        if (EditStatusEnum.MODIFY.name().equals(tableColumn.getEditStatus())) {
            return "ALTER TABLE " + tableColumn.getTableName() + " MODIFY COLUMN " + tableColumn.getName() + " " + tableColumn.getColumnType() + ";";
        }
        if (tableColumn.getComment() != null) {
            return "COMMENT ON COLUMN " + tableColumn.getTableName() + "." + tableColumn.getName() + " IS '" + tableColumn.getComment() + "';";
        }
        return "";
    }

    private static String generateTableCommentSQL(String tableName, String comment) {
        return "COMMENT ON TABLE " + tableName + " IS '" + comment + "';";
    }

}
