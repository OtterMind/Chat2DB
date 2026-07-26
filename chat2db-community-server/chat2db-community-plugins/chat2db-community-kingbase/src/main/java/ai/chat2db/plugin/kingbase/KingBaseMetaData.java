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
import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
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
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.util.SqlUtils;
import jakarta.validation.constraints.NotEmpty;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.kingbase.constant.SqlConstant.*;
import static ai.chat2db.spi.util.SortUtils.sortDatabase;

import static ai.chat2db.plugin.kingbase.constant.KingBaseMetaDataConstants.*;
@Slf4j
public class KingBaseMetaData extends DefaultMetaService implements IDbMetaData {








    public static final DefaultSQLIdentifierProcessor KINGBASE_SQL_IDENTIFIER_PROCESSOR = new KingBaseSQLIdentifierProcessor();

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
            return SqlUtils.quoteObjectName(objectName);
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
        String formatTableName = format(tableName);
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
                            .append(constraintName)
                            .append(" ")
                            .append(constraintDefinition.toLowerCase());
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
                        ddlBuilder.append("\n").append(" partition of ").append(SqlUtils.quoteObjectName(parentTableName)).append("\n");
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
                            .append(format(table_name))
                            .append(" owner to ")
                            .append(owner)
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
                                .append(privilegeType.toLowerCase())
                                .append(SQL_ON)
                                .append(formatTableName)
                                .append(" to ")
                                .append(grantee)
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
        int columnCount;
        if (!partitionInfo[0]) {
            columnCount = DefaultSQLExecutor.getInstance().preExecute(connection, COLUMN_SQL, new String[]{schemaName, tableName}, resultSet -> {
                int total = 0;
                while (resultSet.next()) {
                    total++;
                    String columnName = resultSet.getString("column_name");
                    String dataType = resultSet.getString("data_type");
                    String columnDefault = resultSet.getString("column_default");
                    String udtName = resultSet.getString("udt_name");
                    String identityGeneration = resultSet.getString("identity_generation");
                    boolean isNullable = "YES".equals(resultSet.getString("is_nullable"));
                    boolean isIdentity = "YES".equals(resultSet.getString("is_identity"));
                    int identityIncrement = resultSet.getInt("identity_increment");
                    int identityStart = resultSet.getInt("identity_start");
                    int characterMaximumLength = resultSet.getInt("character_maximum_length");
                    int numericPrecision = resultSet.getInt("numeric_precision");
                    int numericScale = resultSet.getInt("numeric_scale");
                    int datetimePrecision = resultSet.getInt("datetime_precision");
                    ddlBuilder.append("\t").append(columnName).append("  ").append("\t");


                    if ("ARRAY".equals(dataType)) {
                        if (udtName.contains(KingBaseColumnTypeEnum.INT4.name().toLowerCase())) {
                            ddlBuilder.append(KingBaseColumnTypeEnum.INTEGER.getColumnType().getTypeName().toLowerCase()).append("[]");
                        }


 else if (udtName.contains(KingBaseColumnTypeEnum.TEXT.name().toLowerCase())) {
                            ddlBuilder.append(KingBaseColumnTypeEnum.TEXT.getColumnType().getTypeName().toLowerCase()).append("[]");
                        }

 else if (udtName.substring(1).equals(KingBaseColumnTypeEnum.BIT.name().toLowerCase())) {
                            ddlBuilder.append(KingBaseColumnTypeEnum.BIT.getColumnType().getTypeName().toLowerCase()).append("[]");
                        } else if (udtName.substring(1).equals(KingBaseColumnTypeEnum.TIME.name().toLowerCase())) {
                            ddlBuilder.append("time without time zone").append("[]");
                        } else if (udtName.substring(1).equals(KingBaseColumnTypeEnum.TIMESTAMP.name().toLowerCase())) {
                            ddlBuilder.append("timestamp without time zone").append("[]");
                        } else if (udtName.contains(KingBaseColumnTypeEnum.TIMETZ.name().toLowerCase())) {
                            ddlBuilder.append("time with time zone").append("[]");
                        } else if (udtName.contains(KingBaseColumnTypeEnum.TIMESTAMPTZ.name().toLowerCase())) {
                            ddlBuilder.append("timestamp with time zone").append("[]");
                        } else if (udtName.contains(KingBaseColumnTypeEnum.PATH.name().toLowerCase())) {
                            ddlBuilder.append(KingBaseColumnTypeEnum.PATH.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(KingBaseColumnTypeEnum.POINT.name().toLowerCase())) {
                            ddlBuilder.append(KingBaseColumnTypeEnum.POINT.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(KingBaseColumnTypeEnum.LINE.name().toLowerCase())) {
                            ddlBuilder.append(KingBaseColumnTypeEnum.LINE.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(KingBaseColumnTypeEnum.BOX.name().toLowerCase())) {
                            ddlBuilder.append(KingBaseColumnTypeEnum.BOX.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(KingBaseColumnTypeEnum.LSEG.name().toLowerCase())) {
                            ddlBuilder.append(KingBaseColumnTypeEnum.LSEG.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(KingBaseColumnTypeEnum.POLYGON.name().toLowerCase())) {
                            ddlBuilder.append(KingBaseColumnTypeEnum.POLYGON.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(KingBaseColumnTypeEnum.CIRCLE.name().toLowerCase())) {
                            ddlBuilder.append(KingBaseColumnTypeEnum.CIRCLE.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(KingBaseColumnTypeEnum.CIDR.name().toLowerCase())) {
                            ddlBuilder.append(KingBaseColumnTypeEnum.CIDR.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(KingBaseColumnTypeEnum.INET.name().toLowerCase())) {
                            ddlBuilder.append(KingBaseColumnTypeEnum.INET.name().toLowerCase()).append("[]");
                        } else if (udtName.substring(1).equals(KingBaseColumnTypeEnum.MACADDR.name().toLowerCase())) {
                            ddlBuilder.append(KingBaseColumnTypeEnum.MACADDR.name().toLowerCase()).append("[]");
                        } else if (udtName.contains("macaddr8")) {
                            ddlBuilder.append("macaddr8").append("[]");
                        } else if (udtName.contains(KingBaseColumnTypeEnum.XML.name().toLowerCase())) {
                            ddlBuilder.append(KingBaseColumnTypeEnum.XML.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(KingBaseColumnTypeEnum.TSQUERY.name().toLowerCase())) {
                            ddlBuilder.append(KingBaseColumnTypeEnum.TSQUERY.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(KingBaseColumnTypeEnum.TSVECTOR.name().toLowerCase())) {
                            ddlBuilder.append(KingBaseColumnTypeEnum.TSVECTOR.name().toLowerCase()).append("[]");
                        } else {
                            ddlBuilder.append(dataType);
                        }
                    } else if (dataType.equalsIgnoreCase("bpchar")) {
                        ddlBuilder.append(KingBaseColumnTypeEnum.CHAR.name().toLowerCase());
                        if (characterMaximumLength > 0 && characterMaximumLength != 1) {
                            ddlBuilder.append("(").append(characterMaximumLength).append(")");
                        }
                    } else if (dataType.equalsIgnoreCase("pg_catalog.bit")) {
                        ddlBuilder.append(KingBaseColumnTypeEnum.BIT.name().toLowerCase());
                        if (characterMaximumLength > 0 && characterMaximumLength != 1) {
                            ddlBuilder.append("(").append(characterMaximumLength).append(")");
                        }
                    } else if (KingBaseColumnTypeEnum.BIT.name().toLowerCase().equals(dataType)) {
                        ddlBuilder.append(udtName);
                        if (characterMaximumLength > 0 && characterMaximumLength != 1) {
                            ddlBuilder.append("(").append(characterMaximumLength).append(")");
                        }
                    }


 else if ("USER-DEFINED".equals(dataType)) {
                        dataType = format(udtName);
                        ddlBuilder.append(dataType);

                    } else if (KingBaseColumnTypeEnum.TIMETZ.getColumnType().getTypeName().toLowerCase().equals(udtName)) {
                        if (datetimePrecision >= 0 && datetimePrecision != 6) {
                            dataType = "time" + "(" + datetimePrecision + ")" + " with time zone";
                        }
                        ddlBuilder.append(dataType);

                    } else if (KingBaseColumnTypeEnum.TIMESTAMPTZ.getColumnType().getTypeName().toLowerCase().equals(udtName)) {
                        if (datetimePrecision >= 0 && datetimePrecision != 6) {
                            dataType = "timestamp" + "(" + datetimePrecision + ")" + " with time zone";
                        }
                        ddlBuilder.append(dataType);
                    } else if (KingBaseColumnTypeEnum.TIMESTAMP.name().toLowerCase().equals(udtName)) {
                        ddlBuilder.append(udtName);
                        if (datetimePrecision >= 0 && datetimePrecision != 6) {
                            ddlBuilder.append("(").append(datetimePrecision).append(")");
                        }
                    } else if (KingBaseColumnTypeEnum.INTERVAL.name().toLowerCase().equals(udtName)) {
                        ddlBuilder.append(udtName);
                        if (datetimePrecision >= 0 && datetimePrecision != 6) {
                            ddlBuilder.append("(").append(datetimePrecision).append(")");
                        }
                    } else if (KingBaseColumnTypeEnum.TIME.name().toLowerCase().equals(udtName)) {
                        ddlBuilder.append(udtName);
                        if (datetimePrecision >= 0 && datetimePrecision != 6) {
                            ddlBuilder.append("(").append(datetimePrecision).append(")");
                        }
                    } else if (KingBaseColumnTypeEnum.CHARACTER.name().toLowerCase().equals(dataType)) {
                        ddlBuilder.append(KingBaseColumnTypeEnum.CHAR.name().toLowerCase());
                        if (characterMaximumLength > 1) {
                            ddlBuilder.append("(").append(characterMaximumLength).append(")");
                        }
                    } else if (KingBaseColumnTypeEnum.NUMERIC.name().toLowerCase().equals(dataType)) {
                        ddlBuilder.append(dataType);
                        if (numericPrecision > 0) {
                            ddlBuilder.append("(").append(numericPrecision);
                            if (numericScale != 0) {
                                ddlBuilder.append(",").append(numericScale);
                            }
                            ddlBuilder.append(")");
                        }
                    } else {
                        ddlBuilder.append(dataType);
                        if (isIdentity) {
                            ddlBuilder.append(" generated ").append(identityGeneration.toLowerCase()).append(" as identity");
                            if (!(identityStart == 1 && identityIncrement == 1)) {
                                ddlBuilder.append(" (start with ").append(identityStart).append(" increment by ").append(identityIncrement).append(")");
                            }
                        }
                    }
                    if (StringUtils.isNotBlank(columnDefault) && !isIdentity) {
                        ddlBuilder.append(" default ").append(columnDefault);
                    }
                    if (!isNullable && !isIdentity) {
                        ddlBuilder.append(" not null");
                    }

                    if (!resultSet.isLast()) {
                        ddlBuilder.append(",\n");
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
                            .append(resultSet.getString("partition_key").toLowerCase())
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
                        ddlBuilder.append("\n").append(SQL_CREATE_TABLE).append(format(subName)).append("\n")
                                .append("partition of ").append(parentTableName).append("\n")
                                .append(partitionDefinition.toLowerCase()).append(";\n");
                    }
                }

            });
        } else if (childTableInfo.size() >= 2) {
            String parentTableName = childTableInfo.get(1);
            ddlBuilder.append(" ").append(" inherits ")
                    .append("(")
                    .append(format(parentTableName))
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

        List<Table> tables = this.tables(connection, databaseName, schemaName, tableName);
        if (CollectionUtils.isNotEmpty(tables) && tables.size() == 1) {
            Table table = tables.get(0);
            String comment = table.getComment();
            if (StringUtils.isNotBlank(comment)) {
                ddlBuilder.append("\n").append(SQL_COMMENT_TABLE).append(formatTableName).append(" is ")
                        .append("'").append(comment).append("'")
                        .append(";\n");
            }
        }

        for (TableColumn column : this.columns(connection, databaseName, schemaName, tableName)) {
            String name = column.getName();
            String comment = column.getComment();
            if (StringUtils.isNotBlank(comment)) {
                comment = KINGBASE_SQL_IDENTIFIER_PROCESSOR.escapeString(comment);
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

                    ddlBuilder.append(SQL_COMMENT_INDEX).append(index_name)
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
        return Arrays.stream(names).filter(name -> StringUtils.isNotBlank(name)).map(name -> "\"" + name + "\"").collect(Collectors.joining("."));
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
        return KINGBASE_SQL_IDENTIFIER_PROCESSOR;
    }
}
