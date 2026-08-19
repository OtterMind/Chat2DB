package ai.chat2db.plugin.snowflake.constant;

import ai.chat2db.plugin.snowflake.builder.SnowflakeSqlBuilder;
import ai.chat2db.plugin.snowflake.enums.type.*;
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
import ai.chat2db.spi.util.SortUtils;
import jakarta.validation.constraints.NotEmpty;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;


public final class SnowflakeMetaDataConstants {

    public static final String SQL_SHOW_PRIMARY_KEYS = "SHOW PRIMARY KEYS in ";
    public static final String GET_TABLE_DDL_SQL = "SELECT GET_DDL('TABLE', '%s.%s.%s');";
    public static final String VIEW_SQL = "SELECT TABLE_SCHEMA AS DatabaseName, TABLE_NAME AS ViewName, VIEW_DEFINITION AS DEFINITION, CHECK_OPTION, "
            + "IS_UPDATABLE FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_CATALOG = '%s' AND TABLE_SCHEMA = '%s' AND TABLE_NAME = '%s';";
    public static final String OBJECT_SQL = "SHOW USER FUNCTIONS IN SCHEMA \"%s\"";
    public static final String ROUTINES_SQL = "SELECT FUNCTION_NAME, FUNCTION_DEFINITION, COMMENT " +
                    "FROM INFORMATION_SCHEMA.FUNCTIONS " +
                    "WHERE FUNCTION_SCHEMA = '%s'  AND FUNCTION_NAME = '%s';";
    public static final List<String> SYSTEM_SCHEMAS = List.of("INFORMATION_SCHEMA", "PUBLIC", "SCHEMA");


    private SnowflakeMetaDataConstants() {
    }
}
