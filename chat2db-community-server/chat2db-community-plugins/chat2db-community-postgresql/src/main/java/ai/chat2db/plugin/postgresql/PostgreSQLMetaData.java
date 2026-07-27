package ai.chat2db.plugin.postgresql;

import ai.chat2db.plugin.postgresql.builder.PostgreSQLSqlBuilder;
import ai.chat2db.plugin.postgresql.enums.PostgreSQLViewCheckOptionEnum;
import ai.chat2db.plugin.postgresql.enums.PostgreSQLViewStorageOptionEnum;
import ai.chat2db.plugin.postgresql.identifier.PostgreSQLIdentifierProcessor;
import ai.chat2db.plugin.postgresql.enums.type.*;
import ai.chat2db.plugin.postgresql.value.PostgreSQLValueProcessor;
import ai.chat2db.community.tools.util.EasyCollectionUtils;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.community.domain.api.config.DBConfig;
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
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.util.SortUtils;
import ai.chat2db.spi.util.SqlUtils;
import com.google.common.collect.Lists;
import jakarta.validation.constraints.NotEmpty;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.postgresql.constant.SqlConstant.*;
import static ai.chat2db.spi.util.SortUtils.sortDatabase;

import static ai.chat2db.plugin.postgresql.constant.PostgreSQLMetaDataConstants.*;
@Slf4j
public class PostgreSQLMetaData extends DefaultMetaService implements IDbMetaData {









    public static final ISQLIdentifierProcessor POSTGRE_SQL_IDENTIFIER_PROCESSOR = new PostgreSQLIdentifierProcessor();

    @Override
    public List<Database> databases(Connection connection) {
        List<Database> list = DefaultSQLExecutor.getInstance().execute(connection, SQL_SELECT_DATNAME_PG_DATABASE, resultSet -> {
            List<Database> databases = new ArrayList<>();
            try {
                while (resultSet.next()) {
                    String dbName = resultSet.getString("datname");
                    if ("template0".equals(dbName) || "template1".equals(dbName)) {
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

    @Override
    public List<Schema> schemas(Connection connection, String databaseName) {
        List<Schema> schemas = super.schemas(connection, databaseName);
        return SortUtils.sortSchema(schemas, SYSTEM_SCHEMAS);
    }

    @Override
    public List<Table> tables(Connection connection, String databaseName, String schemaName, String tableName) {
        return DefaultSQLExecutor.getInstance().tables(connection, StringUtils.isEmpty(databaseName) ? null : databaseName, StringUtils.isEmpty(schemaName) ? null : schemaName, tableName, new String[]{"TABLE", "SYSTEM TABLE", "PARTITIONED TABLE"});
    }


    @Override
    public List<Trigger> triggers(Connection connection, String databaseName, String schemaName) {
        List<Trigger> triggers = new ArrayList<>();
        String sql = String.format(TRIGGER_SQL_LIST, schemaName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            while (resultSet.next()) {
                Trigger trigger = new Trigger();
                trigger.setTriggerName(resultSet.getString("trigger_name"));
                trigger.setSchemaName(schemaName);
                trigger.setDatabaseName(databaseName);
                triggers.add(trigger);
            }
            return triggers;
        });
    }


    protected String format(String objectName) {
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
        String tablespace = DefaultSQLExecutor.getInstance().preExecute(connection, TABLE_SPACE_SQL, new String[]{schemaName, tableName}, resultSet -> {
            StringBuilder tableSpaceBuilder = new StringBuilder();
            tableSpaceBuilder.append(" tablespace ");
            if (resultSet.next()) {
                tableSpaceBuilder.append(resultSet.getString("tablespace"));
            } else {
                tableSpaceBuilder.append("pg_default");
            }
            tableSpaceBuilder.append(";\n");
            return tableSpaceBuilder.toString();
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
            ddlBuilder.append("\n").append(tablespace);
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

                    if (PostgreSQLColumnTypeEnum.CHARACTERVARYING.getColumnType().getTypeName().toLowerCase().equals(dataType)) {
                        ddlBuilder.append(PostgreSQLColumnTypeEnum.VARCHAR.name().toLowerCase());
                        if (characterMaximumLength >= 1) {
                            ddlBuilder.append("(").append(characterMaximumLength).append(")");
                        }
                    } else if ("ARRAY".equals(dataType)) {
                        if (udtName.contains(PostgreSQLColumnTypeEnum.INT4.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.INTEGER.getColumnType().getTypeName().toLowerCase()).append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.INT2.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.SMALLINT.getColumnType().getTypeName().toLowerCase()).append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.INT8.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.BIGINT.getColumnType().getTypeName().toLowerCase()).append("[]");
                        } else if (udtName.substring(1).equals(PostgreSQLColumnTypeEnum.VARBIT.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.BITVARYING.getColumnType().getTypeName().toLowerCase()).append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.VARCHAR.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.CHARACTERVARYING.getColumnType().getTypeName().toLowerCase()).append("[]");
                        } else if (udtName.substring(1).equals(PostgreSQLColumnTypeEnum.JSON.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.JSON.getColumnType().getTypeName().toLowerCase()).append("[]");
                        } else if (udtName.substring(1).equals(PostgreSQLColumnTypeEnum.JSONB.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.JSONB.getColumnType().getTypeName().toLowerCase()).append("[]");
                        } else if (udtName.substring(1).equals(PostgreSQLColumnTypeEnum.JSONPATH.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.JSONPATH.getColumnType().getTypeName().toLowerCase()).append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.TEXT.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.TEXT.getColumnType().getTypeName().toLowerCase()).append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.BPCHAR.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.CHAR.getColumnType().getTypeName().toLowerCase()).append("[]");
                        } else if (udtName.substring(1).equals(PostgreSQLColumnTypeEnum.BIT.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.BIT.getColumnType().getTypeName().toLowerCase()).append("[]");
                        } else if (udtName.substring(1).equals(PostgreSQLColumnTypeEnum.TIME.name().toLowerCase())) {
                            ddlBuilder.append("time without time zone").append("[]");
                        } else if (udtName.substring(1).equals(PostgreSQLColumnTypeEnum.TIMESTAMP.name().toLowerCase())) {
                            ddlBuilder.append("timestamp without time zone").append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.TIMETZ.name().toLowerCase())) {
                            ddlBuilder.append("time with time zone").append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.TIMESTAMPTZ.name().toLowerCase())) {
                            ddlBuilder.append("timestamp with time zone").append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.PATH.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.PATH.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.POINT.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.POINT.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.LINE.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.LINE.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.BOX.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.BOX.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.LSEG.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.LSEG.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.POLYGON.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.POLYGON.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.CIRCLE.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.CIRCLE.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.CIDR.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.CIDR.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.INET.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.INET.name().toLowerCase()).append("[]");
                        } else if (udtName.substring(1).equals(PostgreSQLColumnTypeEnum.MACADDR.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.MACADDR.name().toLowerCase()).append("[]");
                        } else if (udtName.contains("macaddr8")) {
                            ddlBuilder.append("macaddr8").append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.XML.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.XML.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.TSQUERY.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.TSQUERY.name().toLowerCase()).append("[]");
                        } else if (udtName.contains(PostgreSQLColumnTypeEnum.TSVECTOR.name().toLowerCase())) {
                            ddlBuilder.append(PostgreSQLColumnTypeEnum.TSVECTOR.name().toLowerCase()).append("[]");
                        } else {
                            ddlBuilder.append(dataType);
                        }
                    } else if (PostgreSQLColumnTypeEnum.BIT.name().toLowerCase().equals(dataType)) {
                        ddlBuilder.append(udtName);
                        if (characterMaximumLength > 0 && characterMaximumLength != 1) {
                            ddlBuilder.append("(").append(characterMaximumLength).append(")");
                        }
                    } else if (PostgreSQLColumnTypeEnum.BITVARYING.getColumnType().getTypeName().toLowerCase().equals(dataType)) {
                        ddlBuilder.append(PostgreSQLColumnTypeEnum.BITVARYING.getColumnType().getTypeName().toLowerCase());
                        if (characterMaximumLength > 0) {
                            ddlBuilder.append("(").append(characterMaximumLength).append(")");
                        }
                    } else if ("USER-DEFINED".equals(dataType)) {
                        dataType = format(udtName);
                        ddlBuilder.append(dataType);

                    } else if (PostgreSQLColumnTypeEnum.TIMETZ.getColumnType().getTypeName().toLowerCase().equals(udtName)) {
                        if (datetimePrecision >= 0 && datetimePrecision != 6) {
                            dataType = "time" + "(" + datetimePrecision + ")" + " with time zone";
                        }
                        ddlBuilder.append(dataType);

                    } else if (PostgreSQLColumnTypeEnum.TIMESTAMPTZ.getColumnType().getTypeName().toLowerCase().equals(udtName)) {
                        if (datetimePrecision >= 0 && datetimePrecision != 6) {
                            dataType = "timestamp" + "(" + datetimePrecision + ")" + " with time zone";
                        }
                        ddlBuilder.append(dataType);
                    } else if (PostgreSQLColumnTypeEnum.TIMESTAMP.name().toLowerCase().equals(udtName)) {
                        ddlBuilder.append(udtName);
                        if (datetimePrecision >= 0 && datetimePrecision != 6) {
                            ddlBuilder.append("(").append(datetimePrecision).append(")");
                        }
                    } else if (PostgreSQLColumnTypeEnum.INTERVAL.name().toLowerCase().equals(udtName)) {
                        ddlBuilder.append(udtName);
                        if (datetimePrecision >= 0 && datetimePrecision != 6) {
                            ddlBuilder.append("(").append(datetimePrecision).append(")");
                        }
                    } else if (PostgreSQLColumnTypeEnum.TIME.name().toLowerCase().equals(udtName)) {
                        ddlBuilder.append(udtName);
                        if (datetimePrecision >= 0 && datetimePrecision != 6) {
                            ddlBuilder.append("(").append(datetimePrecision).append(")");
                        }
                    } else if (PostgreSQLColumnTypeEnum.CHARACTER.name().toLowerCase().equals(dataType)) {
                        ddlBuilder.append(PostgreSQLColumnTypeEnum.CHAR.name().toLowerCase());
                        if (characterMaximumLength > 1) {
                            ddlBuilder.append("(").append(characterMaximumLength).append(")");
                        }
                    } else if (PostgreSQLColumnTypeEnum.NUMERIC.name().toLowerCase().equals(dataType)) {
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
            ddlBuilder.append(tablespace);
        } else {
            ddlBuilder.append(tablespace);
        }
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

        DefaultSQLExecutor.getInstance().preExecute(connection, TABLE_COLUMN_COMMENT_SQL, new String[]{schemaName, tableName}, resultSet -> {
            while (resultSet.next()) {
                String comment = resultSet.getString("comment");

                if (StringUtils.isBlank(comment)) {
                    continue;
                }

                String objectType = resultSet.getString("object_type");
                String quoteTableName = resultSet.getString("table_name");
                String columnName = resultSet.getString("column_name");

                ddlBuilder.append(SQL_COMMENT).append(objectType.toLowerCase()).append(" ").append(quoteTableName);

                if (StringUtils.isNotBlank(columnName)) {
                    ddlBuilder.append(".").append(columnName);
                }

                ddlBuilder.append(" is ").append(comment).append(";\n");

            }
        });

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
    public Function function(Connection connection, @NotEmpty String databaseName, String schemaName,
                             String functionName) {
        return DefaultSQLExecutor.getInstance().preExecute(connection, ROUTINES_SQL, new String[]{"f", schemaName, functionName}, resultSet -> {
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
    public Table view(Connection connection, String databaseName, String schemaName, String viewName) {
        String sql = String.format(VIEW_SQL, schemaName, viewName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Table table = new Table();
            table.setDatabaseName(databaseName);
            table.setSchemaName(schemaName);
            table.setName(viewName);
            if (resultSet.next()) {
                table.setDdl(resultSet.getString("definition"));
            }
            return table;
        });
    }

    @Override
    public Trigger trigger(Connection connection, @NotEmpty String databaseName, String schemaName,
                           String triggerName) {

        String sql = String.format(TRIGGER_SQL, schemaName, triggerName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Trigger trigger = new Trigger();
            trigger.setDatabaseName(databaseName);
            trigger.setSchemaName(schemaName);
            trigger.setTriggerName(triggerName);
            if (resultSet.next()) {
                trigger.setTriggerBody(resultSet.getString("trigger_body"));
            }

            return trigger;
        });
    }

    @Override
    public Procedure procedure(Connection connection, @NotEmpty String databaseName, String schemaName,
                               String procedureName) {
        return DefaultSQLExecutor.getInstance().preExecute(connection, ROUTINES_SQL, new String[]{"p", schemaName, procedureName}, resultSet -> {
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
    public List<TableIndex> indexes(Connection connection, String databaseName, String schemaName, String tableName) {

        String constraintSql = String.format(SELECT_KEY_INDEX, schemaName, tableName);
        Map<String, String> constraintMap = new HashMap();
        LinkedHashMap<String, TableIndex> foreignMap = new LinkedHashMap();
        DefaultSQLExecutor.getInstance().execute(connection, constraintSql, resultSet -> {
            while (resultSet.next()) {
                String keyName = resultSet.getString("Key_name");
                String constraintType = resultSet.getString("Constraint_type");
                constraintMap.put(keyName, constraintType);
                if (StringUtils.equalsIgnoreCase(constraintType, PostgreSQLIndexTypeEnum.FOREIGN.getKeyword())) {
                    TableIndex tableIndex = foreignMap.get(keyName);
                    String columnName = resultSet.getString("Column_name");
                    if (tableIndex == null) {
                        tableIndex = new TableIndex();
                        tableIndex.setDatabaseName(databaseName);
                        tableIndex.setSchemaName(schemaName);
                        tableIndex.setTableName(tableName);
                        tableIndex.setName(keyName);
                        tableIndex.setForeignSchemaName(resultSet.getString("Foreign_schema_name"));
                        tableIndex.setForeignTableName(resultSet.getString("Foreign_table_name"));
                        tableIndex.setForeignColumnNamelist(Lists.newArrayList(columnName));
                        tableIndex.setType(PostgreSQLIndexTypeEnum.FOREIGN.getName());
                        foreignMap.put(keyName, tableIndex);
                    } else {
                        tableIndex.getForeignColumnNamelist().add(columnName);
                    }
                }
            }
            return null;
        });

        String sql = String.format(SELECT_TABLE_INDEX, schemaName, tableName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            LinkedHashMap<String, TableIndex> map = new LinkedHashMap(foreignMap);

            while (resultSet.next()) {
                String keyName = resultSet.getString("Key_name");
                TableIndex tableIndex = map.get(keyName);
                if (tableIndex != null) {
                    List<TableIndexColumn> columnList = tableIndex.getColumnList();
                    if (columnList == null) {
                        columnList = new ArrayList<>();
                        tableIndex.setColumnList(columnList);
                    }
                    columnList.add(getTableIndexColumn(resultSet));
                    columnList = columnList.stream().sorted(Comparator.comparing(TableIndexColumn::getOrdinalPosition))
                            .collect(Collectors.toList());
                    tableIndex.setColumnList(columnList);
                } else {
                    TableIndex index = new TableIndex();
                    index.setDatabaseName(databaseName);
                    index.setSchemaName(schemaName);
                    index.setTableName(tableName);
                    index.setName(keyName);
                    index.setUnique(!StringUtils.equals("t", resultSet.getString("NON_UNIQUE")));
                    index.setMethod(resultSet.getString("Index_method"));
                    index.setComment(resultSet.getString("Index_comment"));
                    List<TableIndexColumn> tableIndexColumns = new ArrayList<>();
                    tableIndexColumns.add(getTableIndexColumn(resultSet));
                    index.setColumnList(tableIndexColumns);
                    String constraintType = constraintMap.get(keyName);
                    if (StringUtils.equals("t", resultSet.getString("Index_primary"))) {
                        index.setType(PostgreSQLIndexTypeEnum.PRIMARY.getName());
                    } else if (StringUtils.equalsIgnoreCase(constraintType, PostgreSQLIndexTypeEnum.UNIQUE.getName())
                            || index.getUnique()) {
                        index.setType(PostgreSQLIndexTypeEnum.UNIQUE.getName());
                    } else {
                        index.setType(PostgreSQLIndexTypeEnum.NORMAL.getName());
                    }
                    map.put(keyName, index);
                }
            }
            return map.values().stream().collect(Collectors.toList());
        });

    }

    @Override
    public List<TableColumn> columns(Connection connection, String databaseName, String schemaName, String tableName) {
        List<TableColumn> columnList = super.columns(connection, databaseName, schemaName, tableName);

        EasyCollectionUtils.stream(columnList).forEach(v -> {
            if (StringUtils.equalsIgnoreCase(v.getColumnType(), "bpchar")) {
                v.setColumnType(PostgreSQLColumnTypeEnum.CHAR.getColumnType().getTypeName().toUpperCase());
            } else {
                v.setColumnType(v.getColumnType().toUpperCase());
            }
        });
        return columnList;
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
    public ISqlBuilder getSqlBuilder() {
        return new PostgreSQLSqlBuilder();
    }

    @Override
    public TableMeta getTableMeta(String databaseName, String schemaName, String tableName) {
        return TableMeta.builder()
                .columnTypes(PostgreSQLColumnTypeEnum.getTypes())
                .charsets(PostgreSQLCharsetEnum.getCharsets())
                .collations(PostgreSQLCollationEnum.getCollations())
                .indexTypes(PostgreSQLIndexTypeEnum.getIndexTypes())
                .defaultValues(PostgreSQLDefaultValueEnum.getDefaultValues())
                .build();
    }

    @Override
    public IValueProcessor getValueProcessor() {
        return new PostgreSQLValueProcessor();
    }

    @Override
    public ISQLIdentifierProcessor getSQLIdentifierProcessor() {
        return POSTGRE_SQL_IDENTIFIER_PROCESSOR;
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
        DBConfig dbConfig = Chat2DBContext.getDBConfig();
        if (dbConfig == null) {
            return SYSTEM_SCHEMAS;
        } else {
            List<String> strings = dbConfig.getSystemSchemas();
            return strings == null ? SYSTEM_SCHEMAS : strings;
        }
    }

    @Override
    public ModifyViewConfiguration viewMeta(String databaseName, String schemaName) {
        ModifyViewConfiguration configuration = new ModifyViewConfiguration();
        ArrayList<FormConfig> formConfigs = new ArrayList<>(7);
        formConfigs.add(PostgreSQLViewStorageOptionEnum.getFormConfig());
        formConfigs.add(PostgreSQLViewCheckOptionEnum.getFormConfig());
        formConfigs.add(FormConfig.getInputForm(I18nUtils.getMessage("gui.modify.view.config.name"), "viewName"));
        formConfigs.add(FormConfig.getCheckBox("use or replace", "useOrReplace"));
        formConfigs.add(FormConfig.getCheckBox("use recursive", "useRecursive"));
        formConfigs.add(FormConfig.getInputForm(I18nUtils.getMessage("gui.modify.view.config.comment"), "comment"));

        configuration.setConfigurations(formConfigs);
        String sql = "select * from table_name";
        StringBuilder sqlBuilder = new StringBuilder(100);
        sqlBuilder.append(SQL_CREATE).append("view ");
        if (StringUtils.isNotBlank(schemaName)) {
            sqlBuilder.append("\"").append(schemaName).append("\"").append(".");
        }
        sqlBuilder.append("\"").append("undefined").append("\"");
        sqlBuilder.append(" AS \n").append(sql).append(";");
        configuration.setPreviewSql(sqlBuilder.toString());
        configuration.setSql(sql);
        return configuration;
    }

    @Override
    public Boolean supportCrossSchema() {
        return Boolean.TRUE;
    }
}
