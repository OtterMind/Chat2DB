package ai.chat2db.plugin.postgresql.constant;

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



public final class PostgreSQLMetaDataConstants {

    public static final String SQL_ALTER_TABLE = "alter table ";
    public static final String SQL_COMMENT = "comment on ";
    public static final String SQL_COMMENT_INDEX = "comment on index ";
    public static final String SQL_CREATE = "create ";
    public static final String SQL_CREATE_TABLE = "create table ";
    public static final String SQL_GRANT = "grant ";
    public static final String SQL_ON = " on ";
    public static final String SQL_SELECT_DATNAME_PG_DATABASE = "SELECT datname FROM pg_database";
    public static final String SELECT_KEY_INDEX = "SELECT ccu.table_schema AS Foreign_schema_name, ccu.table_name AS Foreign_table_name, ccu.column_name AS Foreign_column_name, constraint_type AS Constraint_type, tc.CONSTRAINT_NAME AS Key_name, tc.TABLE_NAME, kcu.Column_name, tc.is_deferrable, tc.initially_deferred FROM information_schema.table_constraints AS tc JOIN information_schema.key_column_usage AS kcu ON tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME JOIN information_schema.constraint_column_usage AS ccu ON ccu.constraint_name = tc.constraint_name WHERE tc.TABLE_SCHEMA = '%s'  AND tc.TABLE_NAME = '%s'";
    public static final List<String> SYSTEM_DATABASES = List.of("postgres");
    public static final List<String> SYSTEM_SCHEMAS = List.of("pg_toast", "pg_temp_1", "pg_toast_temp_1",
            "pg_catalog", "information_schema");

    private PostgreSQLMetaDataConstants() {
    }
}
