package ai.chat2db.plugin.clickhouse.constant;

import ai.chat2db.plugin.clickhouse.builder.ClickHouseSqlBuilder;
import ai.chat2db.plugin.clickhouse.enums.type.ClickHouseColumnTypeEnum;
import ai.chat2db.plugin.clickhouse.enums.type.ClickHouseEngineTypeEnum;
import ai.chat2db.plugin.clickhouse.enums.type.ClickHouseIndexTypeEnum;
import ai.chat2db.spi.ICommandExecutor;
import ai.chat2db.spi.IDbMetaData;
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
import jakarta.validation.constraints.NotEmpty;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;



public final class ClickHouseMetaDataConstants {

    public static final String SQL_NAME = " AND name = ?";
    public static final String SQL_SELECT_NAME_SYSTEM_TABLES_DATABASE = "SELECT name FROM system.tables WHERE database = ?";
    public static final String SQL_SHOW_DATABASES = "SHOW databases";
    public static final String FUNCTION_SQL = "SELECT name,create_query as ddl from system.functions where origin='SQLUserDefined'";
    public static final String ROUTINES_SQL = "SELECT SPECIFIC_NAME, ROUTINE_COMMENT, ROUTINE_DEFINITION FROM information_schema.routines WHERE "
                    + "routine_type = '%s' AND ROUTINE_SCHEMA ='%s'  AND "
                    + "routine_name = '%s';";
    public static final String TRIGGER_SQL = "SELECT TRIGGER_NAME,EVENT_MANIPULATION, ACTION_STATEMENT  FROM INFORMATION_SCHEMA.TRIGGERS where "
            + "TRIGGER_SCHEMA = '%s' AND TRIGGER_NAME = '%s';";
    public static final String TRIGGER_SQL_LIST = "SELECT TRIGGER_NAME FROM INFORMATION_SCHEMA.TRIGGERS where TRIGGER_SCHEMA = '%s';";
    public static final String SELECT_TABLE_COLUMNS = "select * from `system`.columns where table ='%s' and database='%s';";
    public static final String VIEW_SQL = "SELECT create_table_query from system.`tables` WHERE `database`='%s' and name='%s'";
    public static final List<String> SYSTEM_DATABASES = List.of("information_schema", "system");


    private ClickHouseMetaDataConstants() {
    }
}
