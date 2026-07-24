package ai.chat2db.plugin.hive;

import ai.chat2db.plugin.hive.builder.HiveSqlBuilder;
import ai.chat2db.plugin.hive.enums.type.HiveColumnTypeEnum;
import ai.chat2db.plugin.hive.enums.type.HiveIndexTypeEnum;
import ai.chat2db.spi.ICommandExecutor;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.DefaultMetaService;
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
import ai.chat2db.spi.DefaultSQLExecutor;
import com.google.common.collect.Lists;
import jakarta.validation.constraints.NotEmpty;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.hive.constant.HiveMetaDataConstants.*;
public class HiveMetaData extends DefaultMetaService implements IDbMetaData {


    @Override
    public List<Database> databases(Connection connection) {
        return Lists.newArrayList();
    }

    @Override
    public List<Schema> schemas(Connection connection, String databaseName) {
        List<Schema> schemas = new ArrayList<>();
        return DefaultSQLExecutor.getInstance().execute(connection,SQL_SHOW_DATABASES, resultSet -> {
            try {
                while (resultSet.next()) {
                    String schenaNane = resultSet.getString("database_name");
                    Schema schema = new Schema();
                    schema.setName(schenaNane);
                    schemas.add(schema);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return schemas;
        });
    }

    @Override
    public String tableDDL(Connection connection, @NotEmpty String databaseName, String schemaName,
                           @NotEmpty String tableName) {
        String sql = "SHOW CREATE TABLE " + format(databaseName) + "."
                + format(tableName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            StringBuilder sb = new StringBuilder();
            while (resultSet.next()) {
                sb.append(resultSet.getString("createtab_stmt"));
                sb.append("\r\n");
            }
            if (sb.length() > 0) {
                sb = sb.delete(sb.length() - 2, sb.length());
                sb.append(";");
                return sb.toString();
            }
            return null;
        });
    }

    @Override
    public String getMetaDataName(String... names) {
        return Arrays.stream(names).skip(1).filter(name -> StringUtils.isNotBlank(name)).map(name -> "`" + name + "`").collect(Collectors.joining("."));
    }

    @Override
    public ICommandExecutor getCommandExecutor() {
        return new HiveCommandExecutor();
    }

    @Override
    public TableMeta getTableMeta(String databaseName, String schemaName, String tableName) {
        return TableMeta.builder()
                .columnTypes(HiveColumnTypeEnum.getTypes())
                .indexTypes(HiveIndexTypeEnum.getIndexTypes())
                .build();
    }

    @Override
    public ISqlBuilder getSqlBuilder() {
        return new HiveSqlBuilder();
    }



    @Override
    public List<TableColumn> columns(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = String.format(SELECT_TAB_COLS, databaseName, tableName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            List<TableColumn> tableColumns = new ArrayList<>();
            Map<String, String> detailTableInfo = new HashMap<>();
            Map<String, String> tableParams = new HashMap<>();
            Map<String, String> storageInfo = new HashMap<>();
            Map<String, String> storageDescParams = new HashMap<>();
            Map<String, Map<String, String>> constraints = new HashMap<>();
            List<Map<String, String>> columns = new ArrayList<>();
            List<Map<String, String>> partitions = new ArrayList<>();
            Map<String, String> moduleMap = getDescTableModule();

            String infoModule = "";
            while (resultSet.next()) {
                String title = resultSet.getString(1).trim();
                if (("".equals(title) && resultSet.getString(2) == null) || "# Constraints".equals(title)) {
                    continue;
                }
                if (moduleMap.containsKey(title)) {
                    if ("partition_info".equals(infoModule) && "col_name".equals(moduleMap.get(title))) {
                        continue;
                    }
                    infoModule = moduleMap.get(title);
                    continue;
                }

                String key = null;
                String value = null;
                switch (infoModule) {
                    case "col_name":
                        Map<String, String> map = new HashMap<>();
                        int colNum = resultSet.getMetaData().getColumnCount();
                        for (int col = 1; col <= colNum; col++) {
                            String columnName = resultSet.getMetaData().getColumnName(col);
                            String columnValue = resultSet.getString(columnName);
                            map.put(columnName, columnValue);
                        }
                        columns.add(map);
                        break;
                    case "table_info":
                        key = resultSet.getString(1).trim().replace(":", "");
                        value = resultSet.getString(2).trim();
                        detailTableInfo.put(key, value);
                        break;

                    case "table_param":
                        key = resultSet.getString(2).trim().replace(":", "");
                        value = resultSet.getString(3).trim();
                        tableParams.put(key, value);
                        break;

                    case "storage_info":
                        key = resultSet.getString(1).trim().replace(":", "");
                        value = resultSet.getString(2).trim();
                        storageInfo.put(key, value);
                        break;

                    case "storage_desc":
                        key = resultSet.getString(2).trim().replace(":", "");
                        value = resultSet.getString(3).trim();
                        storageDescParams.put(key, value);
                        break;
                    case "primary_key":
                        Map<String, String> primaryKeyMap = constraints.getOrDefault("primaryKey", new HashMap<>());
                        if ("Table:".equals(title.trim())) {
                            resultSet.next();
                        }
                        String primaryKeyName = resultSet.getString(2).trim();
                        resultSet.next();

                        key = resultSet.getString(2).trim();
                        primaryKeyMap.put(key, primaryKeyName);

                        constraints.put("primaryKey", primaryKeyMap);
                        break;
                    case "not_null_constraint":
                        Map<String, String> notNullMap = constraints.getOrDefault("notnull", new HashMap<>());
                        if ("Table:".equals(title.trim())) {
                            resultSet.next();
                        }

                        String notNullConstraintName = resultSet.getString(2).trim();
                        resultSet.next();

                        key = resultSet.getString(2).trim();
                        notNullMap.put(key, notNullConstraintName);

                        constraints.put("notnull", notNullMap);
                        break;

                    case "default_constraint":
                        Map<String, String> defaultMap = constraints.getOrDefault("default", new HashMap<>());
                        if ("Table:".equals(title.trim())) { resultSet.next();}

                        String defaultConstraintName = resultSet.getString(2).trim();
                        resultSet.next();

                        String[] keyParts = resultSet.getString(1).trim().split(":");
                        key = keyParts.length > 1 ? keyParts[1] : keyParts[0];
                        value = resultSet.getString(2).trim();
                        int valueIndex = value.indexOf(":");
                        value = value.substring(valueIndex + 1);

                        defaultMap.put(key + "_constraintName", defaultConstraintName);

                        constraints.put("default", defaultMap);
                        break;

                    case "partition_info":
                        Map<String, String> partitionMap = new HashMap<>();
                        int partitionColNum = resultSet.getMetaData().getColumnCount();
                        for (int col = 0; col < partitionColNum; col++) {
                            String columnName = resultSet.getMetaData().getColumnName(col + 1);
                            String columnValue = resultSet.getString(columnName);
                            partitionMap.put(columnName, columnValue);
                        }
                        partitions.add(partitionMap);
                        break;
                    default:
                        System.out.print("unknown module,please update method to support it : " + infoModule);

                }


            }

            for (Map<String, String> columnMap : columns) {
                TableColumn tableColumn = new TableColumn();
                tableColumn.setTableName(tableName);
                tableColumn.setSchemaName(schemaName);
                tableColumn.setName(columnMap.get("col_name"));
                tableColumn.setColumnType(columnMap.get("data_type"));
                tableColumn.setComment(columnMap.get("comment"));
                if (constraints.get("primaryKey") != null && constraints.get("primaryKey").keySet().contains(columnMap.get("col_name"))) {
                    tableColumn.setPrimaryKey(true);
                }
                if (constraints.get("notnull") !=null && constraints.get("notnull").keySet().contains(columnMap.get("col_name"))) {
                    tableColumn.setNullable(1);
                }
                tableColumns.add(tableColumn);

            }

            return tableColumns;
        });
    }

    private static Map<String, String> getDescTableModule() {
        Map<String, String> descTableModule = new HashMap<>();

        descTableModule.put("# col_name", "col_name");
        descTableModule.put("# Detailed Table Information", "table_info");
        descTableModule.put("Table Parameters:", "table_param");
        descTableModule.put("# Storage Information", "storage_info");
        descTableModule.put("Storage Desc Params:", "storage_desc");
        descTableModule.put("# Not Null Constraints", "not_null_constraint");
        descTableModule.put("# Default Constraints", "default_constraint");
        descTableModule.put("# Partition Information", "partition_info");
        descTableModule.put("# Primary Key", "primary_key");

        return descTableModule;
    }

    public static String format(String name) {
        return "`" + name + "`";
    }



    @Override
    public Table view(Connection connection, String databaseName, String schemaName, String viewName) {
        String sql = String.format(VIEW_SQL, databaseName, viewName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Table table = new Table();
            table.setDatabaseName(databaseName);
            table.setSchemaName(schemaName);
            table.setName(viewName);
            StringBuilder sb = new StringBuilder();
            while (resultSet.next()) {
                sb.append(resultSet.getString("createtab_stmt"));
                sb.append("\r\n");
            }
            if (sb.length() > 0) {
                sb = sb.delete(sb.length() - 2, sb.length());
                sb.append(";");
                table.setDdl(sb.toString());
            }
            return table;
        });
    }
}

