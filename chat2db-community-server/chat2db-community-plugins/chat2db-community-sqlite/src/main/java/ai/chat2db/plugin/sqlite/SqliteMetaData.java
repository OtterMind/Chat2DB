package ai.chat2db.plugin.sqlite;

import ai.chat2db.plugin.sqlite.builder.SqliteBuilder;
import ai.chat2db.plugin.sqlite.identifier.SqliteIdentifierProcessor;
import ai.chat2db.plugin.sqlite.enums.type.SqliteCollationEnum;
import ai.chat2db.plugin.sqlite.enums.type.SqliteColumnTypeEnum;
import ai.chat2db.plugin.sqlite.enums.type.SqliteDefaultValueEnum;
import ai.chat2db.plugin.sqlite.enums.type.SqliteIndexTypeEnum;
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
import ai.chat2db.spi.DefaultSQLExecutor;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.sqlite.constant.SqliteMetaDataConstants.*;
public class SqliteMetaData extends DefaultMetaService implements IDbMetaData {


    public static final ISQLIdentifierProcessor SQLITE_IDENTIFIER_PROCESSOR = SqliteIdentifierProcessor.INSTANCE;

    @Override
    public Table view(Connection connection, String databaseName, String schemaName, String viewName) {
        Table view = new Table();
        view.setDatabaseName(databaseName);
        view.setSchemaName(schemaName);
        view.setName(viewName);
        String sql = String.format(VIEW_DDL_SQL, getSQLIdentifierProcessor().escapeString(viewName));
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            if (resultSet.next()) {
                view.setDdl(resultSet.getString("sql"));
            }
        });
        return view;
    }



    @Override
    public List<Trigger> triggers(Connection connection, String databaseName, String schemaName) {
        List<Trigger> triggers = new ArrayList<>();
        return DefaultSQLExecutor.getInstance().execute(connection, TRIGGER_LIST_SQL, resultSet -> {
            while (resultSet.next()) {
                Trigger trigger = new Trigger();
                String triggerName = resultSet.getString("name");
                trigger.setTriggerName(triggerName);
                trigger.setDatabaseName(databaseName);
                triggers.add(trigger);
            }
            return triggers;
        });
    }

    @Override
    public Trigger trigger(Connection connection, String databaseName, String schemaName, String triggerName) {
        Trigger trigger = new Trigger();
        String sql = String.format(TRIGGER_DDL_SQL, getSQLIdentifierProcessor().escapeString(triggerName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            while (resultSet.next()) {
                trigger.setTriggerName(triggerName);
                trigger.setDatabaseName(databaseName);
                trigger.setTriggerBody(resultSet.getString("sql"));
            }
            return trigger;
        });
    }

    @Override
    public List<Table> tables(Connection connection, String databaseName, String schemaName, String tableName) {
        List<Table> tables = DefaultSQLExecutor.getInstance().tables(connection, StringUtils.isEmpty(databaseName) ? null : databaseName, StringUtils.isEmpty(schemaName) ? null : schemaName, tableName, new String[]{"TABLE"});
        return tables;
    }

    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = "SELECT sql FROM sqlite_master WHERE type='table' AND name='" + getSQLIdentifierProcessor().escapeString(tableName) + "'";
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            try {
                if (resultSet.next()) {
                    return resultSet.getString("sql");
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    @Override
    public List<Database> databases(Connection connection) {
        return Lists.newArrayList(Database.builder().name("main").build());
    }

    @Override
    public List<Schema> schemas(Connection connection, String databaseName) {
        return Lists.newArrayList();
    }

    @Override
    public ISqlBuilder getSqlBuilder() {
        return new SqliteBuilder();
    }

    @Override
    public TableMeta getTableMeta(String databaseName, String schemaName, String tableName) {
        return TableMeta.builder()
                .columnTypes(SqliteColumnTypeEnum.getTypes())
                .charsets(null)
                .collations(SqliteCollationEnum.getCollations())
                .indexTypes(SqliteIndexTypeEnum.getIndexTypes())
                .defaultValues(SqliteDefaultValueEnum.getDefaultValues())
                .build();
    }


    @Override
    public String getMetaDataName(String... names) {
        return Arrays.stream(names).filter(StringUtils::isNotBlank)
                .map(SqliteIdentifierProcessor.INSTANCE::quoteIdentifierAlways)
                .collect(Collectors.joining("."));
    }

    @Override
    public ISQLIdentifierProcessor getSQLIdentifierProcessor() {
        return SQLITE_IDENTIFIER_PROCESSOR;
    }
}
