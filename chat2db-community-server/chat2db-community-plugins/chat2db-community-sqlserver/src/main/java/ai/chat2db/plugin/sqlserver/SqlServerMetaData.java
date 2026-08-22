package ai.chat2db.plugin.sqlserver;

import ai.chat2db.spi.ICommandExecutor;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;

import ai.chat2db.plugin.sqlserver.builder.SqlServerSqlBuilder;
import ai.chat2db.plugin.sqlserver.constant.SQLConstant;
import ai.chat2db.plugin.sqlserver.enums.SqlServerViewAttributeOptionEnum;
import ai.chat2db.plugin.sqlserver.enums.SqlServerViewCheckOptionEnum;
import ai.chat2db.plugin.sqlserver.identifier.SqlServerIdentifierProcessor;
import ai.chat2db.plugin.sqlserver.enums.type.SqlServerColumnTypeEnum;
import ai.chat2db.plugin.sqlserver.enums.type.SqlServerDefaultValueEnum;
import ai.chat2db.plugin.sqlserver.enums.type.SqlServerIndexTypeEnum;
import ai.chat2db.plugin.sqlserver.value.SqlServerValueProcessor;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.spi.*;
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
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.util.SortUtils;
import ai.chat2db.spi.util.SqlUtils;
import jakarta.validation.constraints.NotEmpty;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.sqlserver.constant.SQLConstant.*;
import static ai.chat2db.spi.util.SortUtils.sortDatabase;

import static ai.chat2db.plugin.sqlserver.constant.SqlServerMetaDataConstants.*;
public class SqlServerMetaData extends DefaultMetaService implements IDbMetaData {









    @Override
    public List<Database> databases(Connection connection) {
        List<Database> databases = DefaultSQLExecutor.getInstance().databases(connection);
        return sortDatabase(databases, SYSTEM_DATABASES, connection);
    }

    @Override
    public List<Schema> schemas(Connection connection, String databaseName) {
        List<Schema> schemas = DefaultSQLExecutor.getInstance().schemas(connection, databaseName, null);
        return SortUtils.sortSchema(schemas, SYSTEM_SCHEMAS);
    }


    private String format(String objectName) {
        return SqlServerIdentifierProcessor.INSTANCE.quoteIdentifierAlways(objectName);

    }

    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        StringBuilder ddlBuilder = new StringBuilder(500);
        StringBuilder tempBuilder = new StringBuilder(100);
        List<String> tempList = new ArrayList<>();
        String formatSchemaName = format(schemaName);
        String formatTableName = format(tableName);
        ddlBuilder.append(SQL_CREATE_TABLE).append(" ")
                .append(getMetaDataName(schemaName, tableName)).append("\n");
        ddlBuilder.append("(\n");
        List<TableColumn> tableColumnList = DefaultSQLExecutor.getInstance().preExecute(connection, SELECT_TABLE_COLUMNS, new String[]{schemaName, tableName}, resultSet -> {
            List<TableColumn> columns = new ArrayList<>();
            while (resultSet.next()) {
                TableColumn tableColumn = new TableColumn();
                tableColumn.setSchemaName(schemaName);
                tableColumn.setTableName(tableName);
                tableColumn.setName(resultSet.getString("COLUMN_NAME"));
                String computedDefinition = resultSet.getString("COMPUTED_DEFINITION");
                boolean isPersisted = resultSet.getBoolean("IS_PERSISTED");
                String dataType = resultSet.getString("DATA_TYPE").toUpperCase(Locale.ROOT);
                boolean isIdentity = resultSet.getBoolean("IS_IDENTITY");
                BigDecimal seedValue = resultSet.getBigDecimal("SEED_VALUE");
                BigDecimal incrementValue = resultSet.getBigDecimal("INCREMENT_VALUE");
                if (StringUtils.isNotBlank(computedDefinition)) {
                    dataType = "AS " + computedDefinition;
                    if (isPersisted) {
                        dataType += " PERSISTED";
                    }
                } else if (isIdentity) {
                    dataType = buildIdentityDataType(dataType, seedValue, incrementValue);
                }
                tableColumn.setColumnType(dataType);
                tableColumn.setSparse(resultSet.getBoolean("IS_SPARSE"));
                tableColumn.setDefaultValue(resultSet.getString("COLUMN_DEFAULT"));
                tableColumn.setNullable(resultSet.getInt("IS_NULLABLE"));
                tableColumn.setComment(resultSet.getString("COLUMN_COMMENT"));
                configureColumnSize(resultSet, tableColumn);
                columns.add(tableColumn);
                SqlServerColumnTypeEnum typeEnum = SqlServerColumnTypeEnum.getByType(tableColumn.getColumnType());
                if (typeEnum == SqlServerColumnTypeEnum.OTHER) {
                    tempBuilder.append("\t").append(format(tableColumn.getName())).append(" ").append(tableColumn.getColumnType());
                } else {
                    tempBuilder.append("\t").append(typeEnum.buildCreateColumnSql(tableColumn));
                }
                tempList.add(tempBuilder.toString());
                tempBuilder.setLength(0);
            }
            ddlBuilder.append(String.join(",\n", tempList));
            tempList.clear();
            return columns;
        });
        Set<String> PKUQConstraintNameSet = DefaultSQLExecutor.getInstance().execute(connection, String.format(PK_UQ_CONSTRAINT_SQL, SqlServerIdentifierProcessor.INSTANCE.escapeString(formatSchemaName), SqlServerIdentifierProcessor.INSTANCE.escapeString(formatTableName)), resultSet -> {
            Map<String, List<String>> PKConstraintsMap = new HashMap<>(1);
            Map<String, List<String>> UQConstraintsMap = new HashMap<>(3);
            HashMap<String, String> clusteredMap = new HashMap<>(4);
            while (resultSet.next()) {
                String constraintName = resultSet.getString("CONSTRAINT_NAME");
                String columnName = resultSet.getString("COLUMN_NAME");
                boolean isDesc = resultSet.getBoolean("IS_DESC");
                String constraintType = resultSet.getString("CONSTRAINT_TYPE");
                String indexType = resultSet.getString("INDEX_TYPE");
                if (StringUtils.isNotBlank(indexType)) {
                    clusteredMap.computeIfAbsent(constraintName, k -> indexType);
                }
                columnName = SqlServerIdentifierProcessor.INSTANCE.quoteIdentifierAlways(columnName) + (isDesc ? " desc" : " asc");
                if ("PK".equals(constraintType)) {
                    PKConstraintsMap.computeIfAbsent(constraintName, k -> new ArrayList<>()).add(columnName);
                } else if ("UQ".equals(constraintType)) {
                    UQConstraintsMap.computeIfAbsent(constraintName, k -> new ArrayList<>()).add(columnName);
                }
            }
            if (MapUtils.isNotEmpty(PKConstraintsMap) || MapUtils.isNotEmpty(UQConstraintsMap)) {
                ddlBuilder.append(",\n");
                if (MapUtils.isNotEmpty(PKConstraintsMap)) {
                    PKConstraintsMap.forEach((key, value) -> {
                        tempBuilder.append("constraint ")
                                .append(SqlServerIdentifierProcessor.INSTANCE.quoteIdentifierAlways(key))
                                .append("\n")
                                .append("primary key ");
                        if (clusteredMap.containsKey(key)) {
                            tempBuilder.append(" ").append(clusteredMap.get(key).toLowerCase(Locale.ROOT)).append(" ");
                        }
                        tempBuilder.append("(")
                                .append(String.join(" , ", value))
                                .append(")");
                        tempList.add(tempBuilder.toString());
                        tempBuilder.setLength(0);
                    });
                }
                if (MapUtils.isNotEmpty(UQConstraintsMap)) {
                    UQConstraintsMap.forEach((key, value) -> {
                        tempBuilder.append("constraint ")
                                .append(SqlServerIdentifierProcessor.INSTANCE.quoteIdentifierAlways(key))
                                .append("\n")
                                .append("unique ");
                        if (clusteredMap.containsKey(key)) {
                            tempBuilder.append(" ").append(clusteredMap.get(key).toLowerCase(Locale.ROOT)).append(" ");
                        }
                        tempBuilder.append("(")
                                .append(String.join(" , ", value))
                                .append(")");
                        tempList.add(tempBuilder.toString());
                        tempBuilder.setLength(0);
                    });
                }
                ddlBuilder.append(String.join(",\n", tempList));
                tempList.clear();
            }
            Set<String> combinedKeySet = new HashSet<>();
            combinedKeySet.addAll(PKConstraintsMap.keySet());
            combinedKeySet.addAll(UQConstraintsMap.keySet());
            clusteredMap.clear();
            PKConstraintsMap.clear();
            UQConstraintsMap.clear();
            return combinedKeySet;
        });
        DefaultSQLExecutor.getInstance().execute(connection,
                String.format(CHECK_CONSTRAINT_SQL,
                        SqlServerIdentifierProcessor.INSTANCE.escapeString(formatSchemaName),
                        SqlServerIdentifierProcessor.INSTANCE.escapeString(formatTableName)), resultSet -> {
                    boolean isFirst = true;
                    while (resultSet.next()) {

                        String constraintName = resultSet.getString("CONSTRAINT_NAME");
                        String constraintDefinition = resultSet.getString("CONSTRAINT_DEFINITION");
                        if (StringUtils.isBlank(constraintDefinition)) {
                            continue;
                        }
                        if (isFirst) {
                            ddlBuilder.append(",\n");
                            isFirst = false;
                        }
                        tempBuilder.append("constraint ").append(SqlServerIdentifierProcessor.INSTANCE.quoteIdentifierAlways(constraintName)).append("\n")
                                .append("check ").append(constraintDefinition);
                        tempList.add(tempBuilder.toString());
                        tempBuilder.setLength(0);
                    }
                    if (CollectionUtils.isNotEmpty(tempList)) {
                        ddlBuilder.append(String.join(",\n", tempList));
                        tempList.clear();
                    }
                });

        DefaultSQLExecutor.getInstance().preExecute(connection, FOREIGN_KEY_SQL, new String[]{schemaName, tableName}, resultSet -> {
            HashMap<String, String> referencedSchemaMap = new HashMap<>();
            HashMap<String, String> referencedTableMap = new HashMap<>();
            HashMap<String, List<String>> columnMap = new HashMap<>();
            HashMap<String, List<String>> referencedColumnMap = new HashMap<>();
            HashMap<String, Integer> updateActionMap = new HashMap<>();
            HashMap<String, Integer> deleteActionMap = new HashMap<>();
            while (resultSet.next()) {
                String constraintName = resultSet.getString("CONSTRAINT_NAME");
                String referencedSchemaName = resultSet.getString("REFERENCED_SCHEMA_NAME");
                String referencedTableName = resultSet.getString("REFERENCED_TABLE_NAME");
                referencedSchemaMap.putIfAbsent(constraintName, referencedSchemaName);
                referencedTableMap.putIfAbsent(constraintName, referencedTableName);
                String columnName = resultSet.getString("COLUMN_NAME");
                columnMap.computeIfAbsent(constraintName, k -> new ArrayList<>()).add(columnName);
                String referencedColumnName = resultSet.getString("REFERENCED_COLUMN_NAME");
                referencedColumnMap.computeIfAbsent(constraintName, k -> new ArrayList<>()).add(referencedColumnName);
                updateActionMap.putIfAbsent(constraintName, resultSet.getInt("UPDATE_ACTION"));
                deleteActionMap.putIfAbsent(constraintName, resultSet.getInt("DELETE_ACTION"));
            }
            if (MapUtils.isNotEmpty(referencedTableMap)) {
                ddlBuilder.append(",\n");
                referencedTableMap.forEach((constraintName, referencedTableName) -> {
                    tempList.add(buildForeignKeyDefinition(
                            constraintName,
                            columnMap.get(constraintName),
                            referencedSchemaMap.get(constraintName),
                            referencedTableName,
                            referencedColumnMap.get(constraintName),
                            updateActionMap.getOrDefault(constraintName, 0),
                            deleteActionMap.getOrDefault(constraintName, 0)));
                });
                ddlBuilder.append(String.join(",\n", tempList));
                tempList.clear();
            }

        });
        ddlBuilder.append("\n)\n");
        DefaultSQLExecutor.getInstance().preExecute(connection, PARTITION_DEF_SQL, new String[]{schemaName, tableName}, resultSet -> {
            if (resultSet.next()) {
                String partitionColumnName = resultSet.getString("PARTITION_COLUMN_NAME");
                String partitionSchemeName = resultSet.getString("PARTITION_SCHEME_NAME");
                if (StringUtils.isNotBlank(partitionSchemeName) && StringUtils.isNotBlank(partitionColumnName)) {
                    ddlBuilder.append(SQL_ON)
                            .append(format(partitionSchemeName))
                            .append(" (")
                            .append(format(partitionColumnName))
                            .append(") ");
                }
            }
        });
        ddlBuilder.append("\ngo\n");


        List<TableIndex> indexList = DefaultSQLExecutor.getInstance().preExecute(connection, INDEX_SQL, new String[]{schemaName, tableName}, resultSet -> {
            HashMap<String, TableIndex> indexMap = new HashMap<>();
            while (resultSet.next()) {
                String indexName = resultSet.getString("INDEX_NAME");
                String columnName = resultSet.getString("COLUMN_NAME");
                String indexType = resultSet.getString("INDEX_TYPE");
                String ascOrDesc = resultSet.getBoolean("DESCEND") ? "DESC" : "ASC";
                String indexComment = resultSet.getString("INDEX_COMMENT");
                boolean isUnique = resultSet.getBoolean("IS_UNIQUE");
                if (CollectionUtils.isNotEmpty(PKUQConstraintNameSet) && PKUQConstraintNameSet.contains(indexName)) {
                    continue;
                }
                TableIndex index = indexMap.get(indexName);
                if (Objects.isNull(index)) {
                    index = new TableIndex();
                    index.setSchemaName(schemaName);
                    index.setTableName(tableName);
                    index.setName(indexName);
                    index.setColumnList(new ArrayList<>());
                    boolean isNonClustered = Objects.equals(SqlServerIndexTypeEnum.NONCLUSTERED.name(), indexType);
                    if (isUnique) {
                        if (isNonClustered) {
                            index.setType(SqlServerIndexTypeEnum.UNIQUE_NONCLUSTERED.getName());
                        } else {
                            index.setType(SqlServerIndexTypeEnum.UNIQUE_CLUSTERED.getName());
                        }
                    } else {
                        index.setType(indexType);
                    }
                    index.setComment(indexComment);
                    indexMap.put(indexName, index);
                }
                TableIndexColumn tableIndexColumn = new TableIndexColumn();
                tableIndexColumn.setTableName(tableName);
                tableIndexColumn.setSchemaName(schemaName);
                tableIndexColumn.setColumnName(columnName);
                tableIndexColumn.setAscOrDesc(ascOrDesc);
                index.getColumnList().add(tableIndexColumn);
            }
            return new ArrayList<>(indexMap.values());
        });
        DefaultSQLExecutor.getInstance().preExecute(connection, TABLE_COMMENT_SQL, new String[]{schemaName, tableName}, resultSet -> {
            if (resultSet.next()) {
                String comment = resultSet.getString("TABLE_COMMENT");
                if (StringUtils.isNotBlank(comment)) {
                    ddlBuilder.append(SQLConstant.buildTableComment(comment, schemaName, tableName));
                }
            }
        });

        for (TableColumn tableColumn : tableColumnList) {
            String comment = tableColumn.getComment();
            if (StringUtils.isNotBlank(comment)) {
                ddlBuilder.append(SQLConstant.buildColumnComment(comment, schemaName, tableName, tableColumn.getName()));
            }
        }
        DefaultSQLExecutor.getInstance().preExecute(connection, SELECT_CONSTRAINT_COMMENT_SQL, new String[]{schemaName, tableName}, resultSet -> {
            while (resultSet.next()) {
                String constraintName = resultSet.getString("CONSTRAINT_NAME");
                String comment = resultSet.getString("CONSTRAINT_COMMENT");
                if (StringUtils.isNotBlank(comment)) {
                    ddlBuilder.append("\t").append(SQLConstant.buildConstraintComment(comment, schemaName, tableName, constraintName));
                }
            }
        });
        if (CollectionUtils.isNotEmpty(indexList)) {
            indexList.forEach(index -> {
                String type = index.getType();
                SqlServerIndexTypeEnum sqlServerIndexTypeEnum = SqlServerIndexTypeEnum.getByType(type);
                if (Objects.nonNull(sqlServerIndexTypeEnum)) {
                    ddlBuilder.append("\n").append(sqlServerIndexTypeEnum.buildIndexScript(index));
                    String comment = index.getComment();
                    if (StringUtils.isNotBlank(comment)) {
                        ddlBuilder.append("\n").append(SQLConstant.buildIndexComment(comment, schemaName, tableName, index.getName()));
                    }
                }
            });
        }

        return ddlBuilder.toString();
    }

    static String buildIdentityDataType(String dataType, BigDecimal seedValue, BigDecimal incrementValue) {
        String identityType = dataType + " identity";
        if (seedValue == null || incrementValue == null) {
            return identityType;
        }
        if (seedValue.compareTo(BigDecimal.ONE) != 0 || incrementValue.compareTo(BigDecimal.ONE) != 0) {
            return identityType + " (" + formatIdentityValue(seedValue) + ","
                    + formatIdentityValue(incrementValue) + ")";
        }
        return identityType;
    }

    private static String formatIdentityValue(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    static String buildForeignKeyDefinition(String constraintName, List<String> columnNames,
                                            String referencedSchemaName, String referencedTableName,
                                            List<String> referencedColumnNames, int updateAction,
                                            int deleteAction) {
        String referencedTable = SqlServerIdentifierProcessor.INSTANCE.quoteIdentifierAlways(referencedTableName);
        if (StringUtils.isNotBlank(referencedSchemaName)) {
            referencedTable = SqlServerIdentifierProcessor.INSTANCE.quoteIdentifierAlways(referencedSchemaName) + "." + referencedTable;
        }
        return "constraint " + SqlServerIdentifierProcessor.INSTANCE.quoteIdentifierAlways(constraintName) + "\n"
                + "foreign key (" + quoteIdentifierList(columnNames) + ")\n"
                + "references " + referencedTable + " (" + quoteIdentifierList(referencedColumnNames) + ")"
                + buildReferentialActions(updateAction, deleteAction);
    }

    private static String quoteIdentifierList(List<String> identifiers) {
        return identifiers.stream().map(SqlServerIdentifierProcessor.INSTANCE::quoteIdentifierAlways)
                .collect(Collectors.joining(" , "));
    }

    static String buildReferentialActions(int updateAction, int deleteAction) {
        StringBuilder actions = new StringBuilder();
        if (deleteAction != 0) {
            actions.append(SQL_DELETE).append(buildReferentialAction(deleteAction));
        }
        if (updateAction != 0) {
            actions.append(SQL_UPDATE).append(buildReferentialAction(updateAction));
        }
        return actions.toString();
    }

    private static String buildReferentialAction(int actionCode) {
        return switch (actionCode) {
            case 1 -> "cascade";
            case 2 -> "set null";
            case 3 -> "set default";
            default -> "no action";
        };

    }

    private void configureColumnSize(ResultSet columns, TableColumn tableColumn) throws SQLException {
        if (shouldOmitColumnSize(tableColumn.getColumnType())) {
            return;
        }
        int columnSize = columns.getInt("COLUMN_SIZE");
        int numericScale = columns.getInt("NUMERIC_SCALE");
        int columnPrecision = columns.getInt("COLUMN_PRECISION");
        if (Arrays.asList(SqlServerColumnTypeEnum.NCHAR.name(),
                        SqlServerColumnTypeEnum.NVARCHAR.name())
                .contains(tableColumn.getColumnType())) {
            if (columnSize == 2) {
                return;
            }
            if (columnSize == -1) {
                tableColumn.setColumnSize(columnSize);
                return;
            }
            columnSize = columnSize / 2;
            tableColumn.setColumnSize(columnSize);
            return;
        }
        if (Arrays.asList(SqlServerColumnTypeEnum.DATETIMEOFFSET.name(),
                        SqlServerColumnTypeEnum.TIME.name(), SqlServerColumnTypeEnum.DATETIME2.name())
                .contains(tableColumn.getColumnType())) {
            if (numericScale == 7) {
                return;
            }
            tableColumn.setColumnSize(numericScale);
            return;
        } else if (Arrays.asList(SqlServerColumnTypeEnum.DECIMAL.name(),
                        SqlServerColumnTypeEnum.NUMERIC.name())
                .contains(tableColumn.getColumnType())) {
            tableColumn.setColumnSize(columnPrecision);
        } else {
            if (columnSize != 1) {
                tableColumn.setColumnSize(columnSize);
            }

        }
        tableColumn.setDecimalDigits(numericScale);
    }

    static boolean shouldOmitColumnSize(String columnType) {
        return Arrays.asList(SqlServerColumnTypeEnum.FLOAT.name(),
                        SqlServerColumnTypeEnum.REAL.name(),
                        SqlServerColumnTypeEnum.TIMESTAMP.name(),
                        "ROWVERSION")
                .contains(columnType);
    }



    @Override
    public List<Table> tables(Connection connection, String databaseName, String schemaName, String tableName) {
        List<Table> tables = new ArrayList<>();
        String sql = String.format(SELECT_TABLES_SQL, getSQLIdentifierProcessor().escapeString(schemaName));
        if (StringUtils.isNotBlank(tableName)) {
            sql += " AND t.name = '" + getSQLIdentifierProcessor().escapeString(tableName) + "'";
        } else {
            sql += " ORDER BY t.name";
        }

        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            while (resultSet.next()) {
                Table table = new Table();
                table.setDatabaseName(databaseName);
                table.setSchemaName(schemaName);
                table.setName(resultSet.getString("TableName"));
                table.setComment(resultSet.getString("comment"));
                tables.add(table);
            }
            return tables;
        });
    }

    @Override
    public List<TableColumn> columns(Connection connection, String databaseName, String schemaName, String tableName) {
        List<TableColumn> tableColumns = new ArrayList<>();
        return DefaultSQLExecutor.getInstance().preExecute(connection, SELECT_TABLE_COLUMNS, new String[]{schemaName, tableName}, resultSet -> {
            while (resultSet.next()) {
                TableColumn column = new TableColumn();
                column.setDatabaseName(databaseName);
                column.setTableName(tableName);
                column.setSchemaName(schemaName);
                column.setOldName(resultSet.getString("COLUMN_NAME"));
                column.setName(resultSet.getString("COLUMN_NAME"));
                String dataType = resultSet.getString("DATA_TYPE").toUpperCase(Locale.ROOT);
                column.setColumnType(SqlUtils.removeDigits(dataType));
                column.setDefaultValue(resultSet.getString("COLUMN_DEFAULT"));
                column.setComment(resultSet.getString("COLUMN_COMMENT"));
                column.setNullable(resultSet.getInt("IS_NULLABLE"));
                column.setOrdinalPosition(resultSet.getInt("ORDINAL_POSITION"));
                column.setDecimalDigits(resultSet.getInt("NUMERIC_SCALE"));
                column.setCollationName(resultSet.getString("COLLATION_NAME"));
                int columnSize = resultSet.getInt("COLUMN_SIZE");
                if (StringUtils.equalsAny(dataType, SqlServerColumnTypeEnum.NCHAR.name(), SqlServerColumnTypeEnum.NVARCHAR.name())) {
                    if (columnSize == -1) {
                        column.setColumnSize(2147483647);
                    } else if (columnSize > 0) {
                        if (columnSize == 1) {
                            column.setColumnSize(columnSize);
                        } else {
                            column.setColumnSize(columnSize / 2);
                        }
                    }
                } else if (StringUtils.equalsAnyIgnoreCase(dataType, SqlServerColumnTypeEnum.TIME.name(),
                        SqlServerColumnTypeEnum.DATETIME.name(), SqlServerColumnTypeEnum.DATETIME2.name(),
                        SqlServerColumnTypeEnum.DATETIMEOFFSET.name())) {
                    column.setColumnSize(column.getDecimalDigits());
                } else {
                    column.setColumnSize(columnSize);
                }
                tableColumns.add(column);
            }
            return tableColumns;
        });
    }


    @Override
    public Function function(Connection connection, @NotEmpty String databaseName, String schemaName,
                             String functionName) {

        String sql = String.format(
                ROUTINES_DDL_SQL,
                "'SQL_SCALAR_FUNCTION', 'SQL_INLINE_TABLE_VALUED_FUNCTION', 'SQL_TABLE_VALUED_FUNCTION'",
                getSQLIdentifierProcessor().escapeString(functionName),
                getSQLIdentifierProcessor().escapeString(schemaName)
        );
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Function function = new Function();
            function.setDatabaseName(databaseName);
            function.setSchemaName(schemaName);
            function.setFunctionName(functionName);
            if (resultSet.next()) {
                function.setFunctionBody(resultSet.getString("definition"));
            }
            return function;
        });
    }

    @Override
    public List<Function> functions(Connection connection, String databaseName, String schemaName) {
        List<Function> functions = new ArrayList<>();
        String sql = String.format(FUNCTIONS_SQL, FUNCTION_TYPE_LIST);

        return DefaultSQLExecutor.getInstance().preExecute(connection, sql, new String[]{schemaName}, resultSet -> {
            while (resultSet.next()) {
                Function function = new Function();
                function.setDatabaseName(databaseName);
                function.setSchemaName(schemaName);
                function.setFunctionName(resultSet.getString("name"));
                functions.add(function);
            }
            return functions;
        });
    }

    private Function removeVersion(Function function) {
        String fullFunctionName = function.getFunctionName();
        if (!StringUtils.isEmpty(fullFunctionName)) {
            String[] parts = fullFunctionName.split(";");
            String functionName = parts[0];
            function.setFunctionName(functionName);
        }
        return function;
    }

    @Override
    public List<Procedure> procedures(Connection connection, String databaseName, String schemaName) {
        List<Procedure> procedures = new ArrayList<>();
        return DefaultSQLExecutor.getInstance().preExecute(connection, SQLConstant.ROUTINES_SQL, new String[]{"P", schemaName}, resultSet -> {
            while (resultSet.next()) {
                Procedure procedure = new Procedure();
                procedure.setDatabaseName(databaseName);
                procedure.setSchemaName(schemaName);
                procedure.setProcedureName(resultSet.getString("name"));
                procedures.add(procedure);
            }
            return procedures;
        });
    }

    private Procedure removeVersion(Procedure procedure) {
        String fullProcedureName = procedure.getProcedureName();
        if (!StringUtils.isEmpty(fullProcedureName)) {
            String[] parts = fullProcedureName.split(";");
            String procedureName = parts[0];
            procedure.setProcedureName(procedureName);
        }
        return procedure;
    }

    @Override
    public List<Trigger> triggers(Connection connection, String databaseName, String schemaName) {
        List<Trigger> triggers = new ArrayList<>();
        return DefaultSQLExecutor.getInstance().preExecute(connection, TRIGGERS_SQL, new String[]{schemaName}, resultSet -> {
            while (resultSet.next()) {
                Trigger trigger = new Trigger();
                trigger.setTriggerName(resultSet.getString("triggerName"));
                trigger.setSchemaName(schemaName);
                trigger.setDatabaseName(databaseName);
                triggers.add(trigger);
            }
            return triggers;
        });
    }

    @Override
    public Trigger trigger(Connection connection, @NotEmpty String databaseName, String schemaName,
                           String triggerName) {
        return DefaultSQLExecutor.getInstance().preExecute(connection, TRIGGER_DDL_SQL, new String[]{schemaName, triggerName}, resultSet -> {
            Trigger trigger = new Trigger();
            trigger.setDatabaseName(databaseName);
            trigger.setSchemaName(schemaName);
            trigger.setTriggerName(triggerName);
            if (resultSet.next()) {
                trigger.setTriggerBody(resultSet.getString("triggerDefinition"));
            }
            return trigger;
        });
    }

    @Override
    public Procedure procedure(Connection connection, @NotEmpty String databaseName, String schemaName,
                               String procedureName) {
        String sql = String.format(ROUTINES_DDL_SQL, "'SQL_STORED_PROCEDURE'",
                getSQLIdentifierProcessor().escapeString(procedureName),
                getSQLIdentifierProcessor().escapeString(schemaName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
                    Procedure procedure = new Procedure();
                    procedure.setDatabaseName(databaseName);
                    procedure.setSchemaName(schemaName);
                    procedure.setProcedureName(procedureName);
                    if (resultSet.next()) {
                        procedure.setProcedureBody(resultSet.getString("definition"));
                    }
                    return procedure;
                }
        );
    }



    @Override
    public Table view(Connection connection, String databaseName, String schemaName, String viewName) {
        return DefaultSQLExecutor.getInstance().preExecute(connection, VIEW_SQL, new String[]{schemaName, viewName}, resultSet -> {
            Table table = new Table();
            table.setDatabaseName(databaseName);
            table.setSchemaName(schemaName);
            table.setName(viewName);
            if (resultSet.next()) {
                table.setDdl(resultSet.getString("VIEW_DEFINITION"));
            }
            return table;
        });
    }

    @Override
    public List<TableIndex> indexes(Connection connection, String databaseName, String schemaName, String tableName) {
        return DefaultSQLExecutor.getInstance().preExecute(connection, INDEX_SQL, new String[]{schemaName, tableName}, resultSet -> {
            LinkedHashMap<String, TableIndex> map = new LinkedHashMap();
            while (resultSet.next()) {
                String keyName = resultSet.getString("INDEX_NAME");
                TableIndex tableIndex = map.get(keyName);
                if (tableIndex != null) {
                    List<TableIndexColumn> columnList = tableIndex.getColumnList();
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
                    int isunique = resultSet.getInt("IS_UNIQUE");
                    if (isunique == 1) {
                        index.setUnique(true);
                    } else {
                        index.setUnique(false);
                    }
                    List<TableIndexColumn> tableIndexColumns = new ArrayList<>();
                    tableIndexColumns.add(getTableIndexColumn(resultSet));
                    index.setColumnList(tableIndexColumns);
                    String indexType = resultSet.getString("INDEX_TYPE");
                    if (resultSet.getBoolean("IS_PRIMARY")) {
                        index.setType(SqlServerIndexTypeEnum.PRIMARY_KEY.getName());
                    } else if ("CLUSTERED".equalsIgnoreCase(indexType)) {
                        if (index.getUnique()) {
                            index.setType(SqlServerIndexTypeEnum.UNIQUE_CLUSTERED.getName());
                        } else {
                            index.setType(SqlServerIndexTypeEnum.CLUSTERED.getName());
                        }
                    } else if ("NONCLUSTERED".equalsIgnoreCase(indexType)) {
                        if (index.getUnique()) {
                            index.setType(SqlServerIndexTypeEnum.UNIQUE_NONCLUSTERED.getName());
                        } else {
                            index.setType(SqlServerIndexTypeEnum.NONCLUSTERED.getName());
                        }
                    } else {
                        index.setType(indexType);
                    }
                    map.put(keyName, index);
                }
            }
            return map.values().stream().collect(Collectors.toList());
        });

    }

    private TableIndexColumn getTableIndexColumn(ResultSet resultSet) throws SQLException {
        TableIndexColumn tableIndexColumn = new TableIndexColumn();
        tableIndexColumn.setColumnName(resultSet.getString("COLUMN_NAME"));
        tableIndexColumn.setOrdinalPosition(resultSet.getShort("COLUMN_POSITION"));
        int isDescending = resultSet.getInt("DESCEND");
        if (isDescending == 1) {
            tableIndexColumn.setAscOrDesc("DESC");
        } else {
            tableIndexColumn.setAscOrDesc("ASC");
        }
        return tableIndexColumn;
    }


    @Override
    public ISqlBuilder getSqlBuilder() {
        return new SqlServerSqlBuilder();
    }

    @Override
    public TableMeta getTableMeta(String databaseName, String schemaName, String tableName) {
        return TableMeta.builder()
                .columnTypes(SqlServerColumnTypeEnum.getTypes())
                .charsets(null)
                .collations(null)
                .indexTypes(SqlServerIndexTypeEnum.getIndexTypes())
                .defaultValues(SqlServerDefaultValueEnum.getDefaultValues())
                .build();
    }


    @Override
    public IValueProcessor getValueProcessor() {
        return new SqlServerValueProcessor();
    }


    @Override
    public ISQLIdentifierProcessor getSQLIdentifierProcessor() {
        return SqlServerIdentifierProcessor.INSTANCE;
    }

    @Override
    public String getMetaDataName(String... names) {
        return Arrays.stream(names).filter(StringUtils::isNotBlank)
                .map(SqlServerIdentifierProcessor.INSTANCE::quoteIdentifierAlways).collect(Collectors.joining("."));
    }

    @Override
    public ICommandExecutor getCommandExecutor() {
        return new SqlServerExecutor();
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
    public String getDefaultSchemaName(Connection connection, String consoleSchemaName) {
        try {
            String schemaName = "dbo";
            String default_schema = DefaultSQLExecutor.getInstance().execute(connection, SQL_SELECT_SCHEMA_NAME_DEFAULT_SCHEMA, resultSet -> {
                if (resultSet.next()) {
                    String defaultSchema = resultSet.getString("DEFAULT_SCHEMA");
                    if (StringUtils.isNotBlank(defaultSchema)) {
                        return defaultSchema;
                    }
                }
                return null;
            });
            if (StringUtils.isNotBlank(default_schema)) {
                schemaName = default_schema;
            }
            return schemaName;
        } catch (Exception e) {
            return "dbo";
        }

    }

    @Override
    public ModifyViewConfiguration viewMeta(String databaseName, String schemaName) {
        ModifyViewConfiguration configuration = new ModifyViewConfiguration();
        ArrayList<FormConfig> formConfigs = new ArrayList<>(5);
        formConfigs.add(SqlServerViewAttributeOptionEnum.getFormConfig());
        formConfigs.add(SqlServerViewCheckOptionEnum.getFormConfig());
        formConfigs.add(FormConfig.getInputForm(I18nUtils.getMessage("gui.modify.view.config.name"), "viewName"));
        formConfigs.add(FormConfig.getCheckBox("use or alter", "useOrAlter"));
        formConfigs.add(FormConfig.getInputForm(I18nUtils.getMessage("gui.modify.view.config.comment"), "comment"));
        configuration.setConfigurations(formConfigs);
        String sql = "select * from table_name";
        StringBuilder sqlBuilder = new StringBuilder(100);
        sqlBuilder.append(SQL_CREATE).append("view ");
        if (StringUtils.isNotBlank(schemaName)) {
            sqlBuilder.append(SqlServerIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName)).append(".");
        }
        sqlBuilder.append("[").append("undefined").append("]");
        sqlBuilder.append(" AS \n").append(sql).append(";");
        configuration.setPreviewSql(sqlBuilder.toString());
        configuration.setSql(sql);
        return configuration;
    }

    @Override
    public Boolean supportCrossSchema() {
        return Boolean.TRUE;
    }

    @Override
    public Boolean supportCrossDatabase() {
        return Boolean.TRUE;
    }
}
