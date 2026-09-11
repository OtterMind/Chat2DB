package ai.chat2db.plugin.kingbase;

import ai.chat2db.plugin.kingbase.builder.KingBaseSqlBuilder;
import ai.chat2db.plugin.kingbase.identifier.KingBaseSQLIdentifierProcessor;
import ai.chat2db.plugin.kingbase.enums.type.KingBaseColumnTypeEnum;
import ai.chat2db.plugin.kingbase.enums.type.KingBaseDefaultValueEnum;
import ai.chat2db.plugin.kingbase.enums.type.KingBaseIndexTypeEnum;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.community.domain.api.model.account.*;
import ai.chat2db.community.domain.api.config.*;
import ai.chat2db.spi.model.datasource.*;
import ai.chat2db.community.domain.api.model.form.*;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.community.domain.api.model.sql.*;
import ai.chat2db.spi.model.value.*;
import ai.chat2db.community.domain.api.model.view.*;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import jakarta.validation.constraints.NotEmpty;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.kingbase.constant.SqlConstant.*;
import static ai.chat2db.spi.util.SortUtils.sortDatabase;

import static ai.chat2db.plugin.kingbase.constant.KingBaseMetaDataConstants.*;
@Slf4j
public class KingBaseMetaData extends DefaultMetaService implements IDbMetaData {








    @Override
    public List<Database> databases(Connection connection) {
        String sql = "SELECT datname FROM sys_database";
        String version = getDbVersion();
        if (version.startsWith("12.") || version.startsWith("9.")) {
            sql = "SELECT datname FROM pg_database";
        }
        List<Database> list = DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            List<Database> databases = new ArrayList<>();
            try {
                while (resultSet.next()) {
                    String dbName = resultSet.getString("datname");
                    if ("template0".equalsIgnoreCase(dbName) || "template1".equalsIgnoreCase(dbName) ||
                            "template2".equalsIgnoreCase(dbName)) {
                        continue;
                    }
                    Database database = new Database();
                    database.setName(dbName);
                    databases.add(database);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return databases;
        });
        return sortDatabase(list, SYSTEM_DATABASES, connection);
    }


    private String format(String objectName) {
        if (StringUtils.isBlank(objectName)) {
            return objectName;
        } else {
            return KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(objectName);
        }
    }

    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        String databaseProductVersion = "";
        try {
            databaseProductVersion = connection.getMetaData().getDatabaseProductVersion();
        } catch (SQLException e) {
            log.error("get db version error", e);
        }

        int majorVersion = 0;
        boolean isVersionTenOrHigher = false;
        boolean isVersionElevenOrHigher = false;
        try {
            String[] versionParts = databaseProductVersion.split("\\.");
            if (versionParts.length > 0) {
                majorVersion = Integer.parseInt(versionParts[0]);
            }
            isVersionTenOrHigher = majorVersion >= 10;
            isVersionElevenOrHigher = majorVersion >= 11;
        } catch (NumberFormatException e) {
            log.error("Failed to parse database version", e);
        }


        StringBuilder ddlBuilder = new StringBuilder(200);
        String formatTableName = getMetaDataName(schemaName, tableName);
        ddlBuilder.append(SQL_CREATE_TABLE).append(formatTableName);
        String options = DefaultSQLExecutor.getInstance().preExecute(connection, TABLE_OPTION_SQL, new String[]{schemaName, tableName}, resultSet -> {
            if (resultSet.next()) {
                StringBuilder optionBuilder = new StringBuilder();
                String tableOptions = resultSet.getString("table_options");
                if (StringUtils.isNotBlank(tableOptions)) {
                    return optionBuilder.append(" with ").append("(").append(tableOptions).append(")").toString();
                }
            }
            return null;
        });


        StringBuilder constraintsBuilder = new StringBuilder();
        HashSet<String> constraints = DefaultSQLExecutor.getInstance().preExecute(connection, isVersionElevenOrHigher
                ? CONSTRAINT_SQL : CONSTRAINT_SQL_VERSION_UNDER_ELEVEN, new String[]{schemaName, tableName}, resultSet -> {
            HashSet<String> constraintNameSet = new HashSet<>();
            while (resultSet.next()) {
                String constraintDefinition = resultSet.getString("CONSTRAINT_DEFINITION");
                String constraintName = resultSet.getString("CONSTRAINT_NAME");
                if (StringUtils.isNotBlank(constraintName) && StringUtils.isNotBlank(constraintDefinition)) {
                    constraintNameSet.add(constraintName);
                    if (!constraintsBuilder.isEmpty()) {
                        constraintsBuilder.append(",\n");
                    }
                    constraintsBuilder.append("\t").append(" constraint ")
                            .append(KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(constraintName))
                            .append(" ")
                            .append(constraintDefinition);
                }
            }
            if (!constraintsBuilder.isEmpty()) {
                constraintsBuilder.append("\n");
            }
            return constraintNameSet;

        });
        Boolean[] partitionInfo = {false, false};
        if (isVersionTenOrHigher) {
            DefaultSQLExecutor.getInstance().preExecute(connection, PARTITIONED_SUB_TABLE_SQL, new String[]{schemaName, tableName}, resultSet -> {
                if (resultSet.next()) {
                    String parentTableName = resultSet.getString("PARENT_TABLE");
                    String partitionDefinition = resultSet.getString("PARTITION_DEFINITION");
                    boolean isParentTable = resultSet.getBoolean("is_parent_table");
                    if (StringUtils.isNotBlank(parentTableName) && StringUtils.isNotBlank(partitionDefinition)) {
                        ddlBuilder.append("\n").append(" partition of ")
                                .append(getMetaDataName(resultSet.getString("parent_schema"), parentTableName))
                                .append("\n");
                        if (!constraintsBuilder.isEmpty()) {
                            ddlBuilder.append("(\n")
                                    .append(constraintsBuilder)
                                    .append(")\n");
                            constraintsBuilder.setLength(0);
                        }
                        ddlBuilder.append(partitionDefinition.toLowerCase());
                        partitionInfo[0] = true;
                        partitionInfo[1] = isParentTable;
                    }
                }

            });
        }
        String tableOwnerSql = DefaultSQLExecutor.getInstance().preExecute(connection, TABLE_OWNER_SQL, new String[]{schemaName, tableName}, resultSet -> {
            StringBuilder tableOwnerBuilder = new StringBuilder();
            while (resultSet.next()) {
                String owner = resultSet.getString("OWNER");
                String table_name = resultSet.getString("TABLE_NAME");
                if (StringUtils.isNotBlank(owner) && StringUtils.isNotBlank(table_name)) {
                    tableOwnerBuilder.append(SQL_ALTER_TABLE)
                            .append(getMetaDataName(schemaName, table_name))
                            .append(" owner to ")
                            .append(KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(owner))
                            .append(";").append("\n");
                }
            }
            return tableOwnerBuilder.toString();
        });
        String tablePrivilegeSql = DefaultSQLExecutor.getInstance().preExecute(connection, TABLE_PRIVILEGE_SQL, new String[]{schemaName, tableName}, resultSet -> {
            StringBuilder tablePrivilegeBuilder = new StringBuilder();
            while (resultSet.next()) {
                String grantee = resultSet.getString("grantee");
                String owner = resultSet.getString("OWNER");
                if (StringUtils.isNotBlank(grantee) && !Objects.equals(grantee, owner)) {
                    String privilegeType = resultSet.getString("PRIVILEGE_TYPE");
                    if (StringUtils.isNotBlank(privilegeType)) {
                        tablePrivilegeBuilder.append(SQL_GRANT)
                                .append(KingBaseSqlGuards.requirePrivilege(privilegeType))
                                .append(SQL_ON)
                                .append(formatTableName)
                                .append(" to ")
                                .append(KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(grantee))
                                .append(";").append("\n");
                    }
                }
            }
            return tablePrivilegeBuilder.toString();
        });
        if (partitionInfo[0] && !partitionInfo[1]) {
            if (StringUtils.isNotBlank(options)) {
                ddlBuilder.append(options);
            }
            ddlBuilder.append(";");
            if (StringUtils.isNotBlank(tableOwnerSql)) {
                ddlBuilder.append("\n").append(tableOwnerSql);
            }
            if (StringUtils.isNotBlank(tablePrivilegeSql)) {
                ddlBuilder.append("\n").append(tablePrivilegeSql);
            }
            return ddlBuilder.toString();
        }
        if (!partitionInfo[0]) {
            ddlBuilder.append("\n(\n");
        }
        ArrayList<String> childTableInfo;
        childTableInfo = DefaultSQLExecutor.getInstance().preExecute(connection, has_parent_table_sql,
                new String[]{schemaName, tableName}, resultSet -> {
                    ArrayList<String> parentTableInfo = new ArrayList<>(2);
                    while (resultSet.next()) {
                        String parentTableName = resultSet.getString("parent_table");
                        String partitionTableSchema = resultSet.getString("parent_schema");
                        if (StringUtils.isNotBlank(parentTableName) && StringUtils.isNotBlank(partitionTableSchema)) {
                            parentTableInfo.add(partitionTableSchema);
                            parentTableInfo.add(parentTableName);
                        }
                    }
                    return parentTableInfo;
                });
        StringBuilder autoIncrementDDL = new StringBuilder();
        int columnCount;
        if (!partitionInfo[0]) {
            columnCount = DefaultSQLExecutor.getInstance().preExecute(connection, COLUMN_SQL, new String[]{schemaName, tableName}, resultSet -> {
                // Catalog attributes vary by server capability; a.* never references absent attributes.
                Set<String> attributes = new HashSet<>();
                ResultSetMetaData metadata = resultSet.getMetaData();
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    attributes.add(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT));
                }
                int total = 0;
                while (resultSet.next()) {
                    if (total++ > 0) {
                        ddlBuilder.append(",\n");
                    }
                    String columnName = resultSet.getString("attname");
                    String dataType = resultSet.getString("data_type");
                    String columnDefault = resultSet.getString("column_default");
                    String identity = attributes.contains("attidentity") ? resultSet.getString("attidentity") : "";
                    String generated = attributes.contains("attgenerated") ? resultSet.getString("attgenerated") : "";
                    ddlBuilder.append("\t").append(format(columnName)).append("  \t").append(dataType);
                    if ("i".equals(identity)) {
                        appendAutoIncrement(connection, formatTableName, columnName, autoIncrementDDL);
                    } else if (StringUtils.isNotBlank(identity)) {
                        String generation = switch (identity) {
                            case "a" -> "always";
                            case "d" -> "by default";
                            default -> throw new SQLException("Unsupported identity kind: " + identity);
                        };
                        ddlBuilder.append(" generated ").append(generation).append(" as identity");
                        appendIdentityOptions(connection, formatTableName, columnName, ddlBuilder);
                    } else if (StringUtils.isNotBlank(generated)) {
                        String storage = switch (generated) {
                            case "s" -> "stored";
                            case "v" -> "virtual";
                            default -> throw new SQLException("Unsupported generated column kind: " + generated);
                        };
                        ddlBuilder.append(" generated always as (").append(columnDefault).append(") ").append(storage);
                    } else if (StringUtils.isNotBlank(columnDefault)) {
                        ddlBuilder.append(" default ").append(columnDefault);
                    }
                    if (resultSet.getBoolean("attnotnull")) {
                        ddlBuilder.append(" not null");
                    }
                }
                return total;
            });
        } else {
            columnCount = 0;
        }
        if (!partitionInfo[0] && !constraintsBuilder.isEmpty()) {
            if (columnCount != 0) {
                ddlBuilder.append(",\n");
            }
            ddlBuilder.append(constraintsBuilder);
        }
        if (!partitionInfo[0]) {
            ddlBuilder.append("\n)");
        }
        Boolean isPartitionedTable = false;
        if (isVersionTenOrHigher) {
            isPartitionedTable = DefaultSQLExecutor.getInstance().preExecute(connection, PARTITIONED_CONDITION_SQL, new String[]{schemaName, tableName}, resultSet -> {
                boolean isPartitioned = false;
                if (resultSet.next()) {
                    ddlBuilder.append(" partition by ")
                            .append(resultSet.getString("partition_key"))
                            .append(";");
                    isPartitioned = true;
                    ddlBuilder.append("\n");
                }
                return isPartitioned;
            });
        }
        if (isPartitionedTable) {
            DefaultSQLExecutor.getInstance().preExecute(connection, LIST_PARTITIONED_SUB_TABLE_SQL, new String[]{schemaName, tableName}, resultSet -> {
                while (resultSet.next()) {
                    String subName = resultSet.getString("sub_name");
                    String parentTableName = resultSet.getString("PARENT_TABLE");
                    String partitionDefinition = resultSet.getString("PARTITION_DEFINITION");
                    if (StringUtils.isNotBlank(parentTableName) && StringUtils.isNotBlank(partitionDefinition)) {
                        // These three names are quote_ident() output from LIST_PARTITIONED_SUB_TABLE_SQL.
                        ddlBuilder.append("\n").append(SQL_CREATE_TABLE)
                                .append(resultSet.getString("schema_name")).append(".").append(subName).append("\n")
                                .append("partition of ").append(format(schemaName)).append(".")
                                .append(parentTableName).append("\n")
                                .append(partitionDefinition).append(";\n");
                    }
                }

            });
        } else if (childTableInfo.size() >= 2) {
            String parentSchemaName = childTableInfo.get(0);
            String parentTableName = childTableInfo.get(1);
            ddlBuilder.append(" ").append(" inherits ")
                    .append("(")
                    .append(getMetaDataName(parentSchemaName, parentTableName))
                    .append(")").append("\n");
            if (StringUtils.isNotBlank(options)) {
                ddlBuilder.append(" ").append(options).append("\n");
            }
            ddlBuilder.append(";");
        }


        ddlBuilder.append(";");

        if (!partitionInfo[0]) {
            DefaultSQLExecutor.getInstance().preExecute(connection, INDEX_SQL, new String[]{schemaName, tableName}, resultSet -> {
                while (resultSet.next()) {
                    String indexName = resultSet.getString("INDEXNAME");
                    if (StringUtils.isNotBlank(indexName) && constraints.contains(indexName)) {
                        continue;
                    }
                    String indexDef = resultSet.getString("INDEXDEF");
                    if (StringUtils.isNotBlank(indexDef) && StringUtils.isNotBlank(indexName)) {
                        ddlBuilder.append(indexDef).append(";").append("\n");
                    }
                }
                ddlBuilder.append("\n");
            });
        }

        ddlBuilder.append(autoIncrementDDL);
        List<Table> tables = this.tables(connection, databaseName, schemaName, tableName);
        if (CollectionUtils.isNotEmpty(tables) && tables.size() == 1) {
            Table table = tables.get(0);
            String comment = table.getComment();
            if (StringUtils.isNotBlank(comment)) {
                ddlBuilder.append("\n").append(SQL_COMMENT_TABLE).append(formatTableName).append(" is ")
                        .append("'").append(getSQLIdentifierProcessor().escapeString(comment)).append("'")
                        .append(";\n");
            }
        }

        for (TableColumn column : this.columns(connection, databaseName, schemaName, tableName)) {
            String name = column.getName();
            String comment = column.getComment();
            if (StringUtils.isNotBlank(comment)) {
                comment = getSQLIdentifierProcessor().escapeString(comment);
                ddlBuilder.append("\n").append(SQL_COMMENT_COLUMN)
                        .append(formatTableName).append(".").append(format(name))
                        .append(" is ")
                        .append("'").append(comment).append("'")
                        .append(";\n");
            }
        }


        if (!partitionInfo[0]) {
            DefaultSQLExecutor.getInstance().preExecute(connection, TABLE_INDEX_COMMENT_SQL, new String[]{schemaName, tableName}, resultSet -> {
                while (resultSet.next()) {

                    String index_name = resultSet.getString("index_name");
                    String index_comment = resultSet.getString("index_comment");

                    ddlBuilder.append(SQL_COMMENT_INDEX).append(resultSet.getString("schema_name"))
                            .append(".").append(index_name)
                            .append(" is ").append(index_comment).append(";\n");
                }

            });
        }

        if (StringUtils.isNotBlank(tableOwnerSql)) {
            ddlBuilder.append("\n").append(tableOwnerSql);
        }

        if (StringUtils.isNotBlank(tablePrivilegeSql)) {
            ddlBuilder.append("\n").append(tablePrivilegeSql);
        }
        return ddlBuilder.toString();
    }



    private void appendIdentityOptions(Connection connection, String tableName, String columnName, StringBuilder ddl) {
        DefaultSQLExecutor.getInstance().preExecute(connection, IDENTITY_SEQUENCE_SQL, new String[]{tableName, columnName}, resultSet -> {
            if (!resultSet.next()) {
                throw new SQLException("Identity sequence metadata is missing");
            }
            ddl.append(" (");
            appendSequenceOptions(resultSet, ddl);
            ddl.append(")");
        });
    }

    private void appendAutoIncrement(Connection connection, String tableName, String columnName, StringBuilder ddl) {
        DefaultSQLExecutor.getInstance().preExecute(connection, IDENTITY_SEQUENCE_SQL, new String[]{tableName, columnName}, resultSet -> {
            if (!resultSet.next()) {
                throw new SQLException("Auto-increment sequence metadata is missing");
            }
            String sequenceName = getMetaDataName(resultSet.getString("sequence_schema"), resultSet.getString("sequence_name"));
            // Match sys_dump: attach AUTO_INCREMENT after keys, then restore its current counter.
            ddl.append("alter table ").append(tableName).append(" alter column ").append(format(columnName))
                    .append(" add auto_increment (sequence name ").append(sequenceName).append(" ");
            appendSequenceOptions(resultSet, ddl);
            ddl.append(")");
            DefaultSQLExecutor.getInstance().execute(connection, SEQUENCE_STATE_SQL.formatted(sequenceName), state -> {
                if (!state.next()) {
                    throw new SQLException("Auto-increment sequence state is missing");
                }
                long nextValue = state.getLong("next_value");
                if (nextValue > 0) {
                    ddl.append(", auto_increment = ").append(nextValue);
                }
                ddl.append(";\nselect pg_catalog.setval('").append(getSQLIdentifierProcessor().escapeString(sequenceName))
                        .append("', ").append(state.getLong("last_value")).append(", ")
                        .append(state.getBoolean("is_called")).append(");\n");
            });
        });
    }

    private void appendSequenceOptions(ResultSet resultSet, StringBuilder ddl) throws SQLException {
        ddl.append("start with ").append(resultSet.getLong("seqstart"))
                .append(" increment by ").append(resultSet.getLong("seqincrement"))
                .append(" minvalue ").append(resultSet.getLong("seqmin"))
                .append(" maxvalue ").append(resultSet.getLong("seqmax"))
                .append(" cache ").append(resultSet.getLong("seqcache"))
                .append(resultSet.getBoolean("seqcycle") ? " cycle" : " no cycle");
    }

    @Override
    public List<TableIndex> indexes(Connection connection, String databaseName, String schemaName, String tableName) {
        List<TableIndex> indexes = super.indexes(connection, databaseName, schemaName, tableName);
        Map<String, TableIndex> name2IndexMap = indexes.stream()
                .peek(tableIndex -> {
                    if (tableIndex.getUnique()) {
                        tableIndex.setType(KingBaseIndexTypeEnum.UNIQUE.getName());
                    } else {
                        tableIndex.setType(KingBaseIndexTypeEnum.NORMAL.getName());
                    }
                }).collect(Collectors.toMap(
                        TableIndex::getName, TableIndex -> TableIndex, (o, n) -> n, LinkedHashMap::new
                ));
        List<PrimaryKey> primaryKeys = this.getPrimaryKeys(connection, databaseName, schemaName, tableName);
        if (CollectionUtils.isNotEmpty(primaryKeys)) {
            for (PrimaryKey primaryKey : primaryKeys) {
                TableIndex tableIndex = name2IndexMap.get(primaryKey.getPrimaryKeyName());
                if (tableIndex != null) {
                    tableIndex.setType(KingBaseIndexTypeEnum.PRIMARY.getName());
                }
            }
        }
        return new ArrayList<>(name2IndexMap.values());
    }

    private String getDbVersion() {
        String version = Chat2DBContext.getDbVersion();
        if (StringUtils.isNotBlank(version)) {
            return version;
        }
        return "";
    }

    private TableIndexColumn getTableIndexColumn(ResultSet resultSet) throws SQLException {
        TableIndexColumn tableIndexColumn = new TableIndexColumn();
        tableIndexColumn.setColumnName(resultSet.getString("Column_name"));
        tableIndexColumn.setOrdinalPosition(resultSet.getShort("Seq_in_index"));
        tableIndexColumn.setCollation(resultSet.getString("Collation"));
        tableIndexColumn.setAscOrDesc(resultSet.getString("Collation"));
        return tableIndexColumn;
    }



    @Override
    public List<Function> functions(Connection connection, @NotEmpty String databaseName, String schemaName) {
        return DefaultSQLExecutor.getInstance().preExecute(connection, FUNCTION_LIST_SQL, new String[]{schemaName}, resultSet -> {
            List<Function> functions = new ArrayList<>();
            while (resultSet.next()) {
                Function function = new Function();
                function.setDatabaseName(databaseName);
                function.setSchemaName(resultSet.getString("nspname"));
                function.setFunctionName(resultSet.getString("proname"));
                function.setSpecificName(resultSet.getString("proname"));
                function.setFunctionType((short) 1);
                functions.add(function);
            }
            return functions;
        });
    }

    @Override
    public Function function(Connection connection, @NotEmpty String databaseName, String schemaName,
                             String functionName) {

        return DefaultSQLExecutor.getInstance().preExecute(connection, FUNCTION_SQL, new String[]{schemaName, functionName}, resultSet -> {
            Function function = new Function();
            function.setDatabaseName(databaseName);
            function.setSchemaName(schemaName);
            function.setFunctionName(functionName);
            if (resultSet.next()) {
                function.setFunctionBody(resultSet.getString("code"));
            }
            return function;
        });
    }

    @Override
    public Procedure procedure(Connection connection, @NotEmpty String databaseName, String schemaName,
                               String procedureName) {
        return DefaultSQLExecutor.getInstance().preExecute(connection, PROCEDURE_SQL, new String[]{schemaName, procedureName}, resultSet -> {
            Procedure procedure = new Procedure();
            procedure.setDatabaseName(databaseName);
            procedure.setSchemaName(schemaName);
            procedure.setProcedureName(procedureName);
            if (resultSet.next()) {
                procedure.setProcedureBody(resultSet.getString("code"));
            }
            return procedure;
        });
    }

    @Override
    public ISqlBuilder getSqlBuilder() {
        return new KingBaseSqlBuilder();
    }

    @Override
    public TableMeta getTableMeta(String databaseName, String schemaName, String tableName) {
        return TableMeta.builder()
                .columnTypes(KingBaseColumnTypeEnum.getTypes())
                .indexTypes(KingBaseIndexTypeEnum.getIndexTypes())
                .defaultValues(KingBaseDefaultValueEnum.getDefaultValues())
                .build();
    }

    @Override
    public String getMetaDataName(String... names) {
        return Arrays.stream(names).filter(name -> StringUtils.isNotBlank(name)).map(KingBaseSQLIdentifierProcessor.INSTANCE::quoteIdentifierAlways).collect(Collectors.joining("."));
    }

    @Override
    public List<String> getSystemDatabases() {
        return SYSTEM_DATABASES;
    }

    @Override
    public List<String> getSystemSchemas() {
        return SYSTEM_SCHEMAS;
    }

    @Override
    public ISQLIdentifierProcessor getSQLIdentifierProcessor() {
        return KingBaseSQLIdentifierProcessor.INSTANCE;
    }
}
