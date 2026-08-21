package ai.chat2db.plugin.sqlserver.constant;

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
import ai.chat2db.community.tools.util.EasyStringUtils;
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
import net.sf.jsqlparser.statement.ReferentialAction;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;



public final class SqlServerMetaDataConstants {

    public static final String FUNCTION_TYPE_LIST = "'FN', 'IF', 'TF'";
    public static final String FUNCTIONS_SQL = "SELECT name FROM sys.objects WHERE type IN (%s) AND schema_id = SCHEMA_ID(?) ORDER BY name";
    public static final String SQL_CREATE = "create ";
    public static final String SQL_CREATE_TABLE = "CREATE TABLE";
    public static final String SQL_DELETE = " on delete ";
    public static final String SQL_ON = " on ";
    public static final String SQL_SELECT_SCHEMA_NAME_DEFAULT_SCHEMA = "SELECT SCHEMA_NAME() as DEFAULT_SCHEMA";
    public static final String SQL_UPDATE = " on update ";
    public static final String SELECT_TABLES_SQL = "SELECT t.name AS TableName, mm.value as comment FROM sys.tables t LEFT JOIN(SELECT * from sys.extended_properties ep where ep.minor_id = 0 AND ep.name = 'MS_Description') mm ON t.object_id = mm.major_id WHERE t.schema_id= SCHEMA_ID('%s')";
    public static final String VIEW_SQL = """
            SELECT
              v.name AS ViewName,
              m.definition AS VIEW_DEFINITION
            FROM
              sys.views AS v
            JOIN
              sys.sql_modules AS m ON v.object_id = m.object_id
            WHERE
              v.schema_id = SCHEMA_ID(?)
              AND v.name = ?;
            """;
    public static final List<String> SYSTEM_DATABASES = List.of("master", "model", "msdb", "tempdb");
    public static final List<String> SYSTEM_SCHEMAS = List.of("guest", "INFORMATION_SCHEMA", "sys", "db_owner",
            "db_accessadmin", "db_securityadmin", "db_ddladmin", "db_backupoperator", "db_datareader",
            "db_datawriter", "db_denydatareader", "db_denydatawriter");


    private SqlServerMetaDataConstants() {
    }
}
