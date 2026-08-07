package ai.chat2db.plugin.h2.constant;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.*;
import java.util.stream.Collectors;

import ai.chat2db.plugin.h2.builder.H2SqlBuilder;
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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;


public final class H2MetaConstants {

    public static final String SQL_COMMENT = " COMMENT '";
    public static final String SQL_CREATE_INDEX = "CREATE INDEX %s ON %s (%s);";
    public static final String SQL_CREATE_TABLE = "CREATE TABLE ";
    public static final String ROUTINES_SQL = "SELECT SPECIFIC_NAME, ROUTINE_DEFINITION FROM information_schema.routines WHERE "
            + "routine_type = '%s' AND ROUTINE_SCHEMA ='%s'  AND "
            + "routine_name = '%s';";
    public static final String TRIGGER_SQL = "SELECT TRIGGER_NAME,JAVA_CLASS  FROM INFORMATION_SCHEMA.TRIGGERS where "
        + "TRIGGER_SCHEMA = '%s' AND TRIGGER_NAME = '%s';";
    public static final String TRIGGER_SQL_LIST = "SELECT TRIGGER_NAME FROM INFORMATION_SCHEMA.TRIGGERS where TRIGGER_CATALOG = '%s' AND TRIGGER_SCHEMA = '%s';";
    public static final String VIEW_SQL = "SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_CATALOG = '%s' AND TABLE_SCHEMA = '%s' "
        + "AND TABLE_NAME = '%s';";
    public static final List<String> SYSTEM_SCHEMAS = List.of("INFORMATION_SCHEMA");


    private H2MetaConstants() {
    }
}
