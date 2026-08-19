package ai.chat2db.plugin.sundb.constant;

import ai.chat2db.plugin.sundb.builder.SUNDBSqlBuilder;
import ai.chat2db.plugin.sundb.enums.type.SUNDBColumnTypeEnum;
import ai.chat2db.plugin.sundb.enums.type.SUNDBDefaultValueEnum;
import ai.chat2db.plugin.sundb.enums.type.SUNDBIndexTypeEnum;
import ai.chat2db.plugin.sundb.enums.type.SUNDBObjectTypeEnum;
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
import com.google.common.collect.Lists;
import jakarta.validation.constraints.NotEmpty;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;


public final class SUNDBMetaDataConstants {

    public static final String SQL_COLS_INDEX_NAME = "AND COLS.INDEX_NAME = '";
    public static final String SQL_COLS_INDEX_SCHEMA = "AND COLS.INDEX_SCHEMA = '";
    public static final String SQL_CREATE_TABLE = "CREATE TABLE ";
    public static final String SQL_ON = "ON ";
    public static final String SQL_ORDER_COLS_COLUMN_POSITION = "ORDER BY COLS.COLUMN_POSITION;";
    public static final String SQL_SELECT_COLS_INDEX_SCHEMA_COLS = "SELECT COLS.INDEX_SCHEMA,COLS.INDEX_NAME,COLS.TABLE_SCHEMA,COLS.TABLE_NAME,COLS.COLUMN_NAME,COLS.DESCEND,COLS.NULL_ORDER,IDX.UNIQUENESS,IDX.PCT_FREE,IDX.INI_TRANS,IDX.MAX_TRANS,IDX.INITIAL_EXTENT*TBS.EXTENT_SIZE,IDX.NEXT_EXTENT*TBS.EXTENT_SIZE,IDX.MIN_EXTENTS*TBS.EXTENT_SIZE,IDX.MAX_EXTENTS*TBS.EXTENT_SIZE,IDX.TABLESPACE_NAME FROM ALL_IND_COLUMNS AS COLS JOIN ALL_INDEXES AS IDX ON COLS.INDEX_NAME  = IDX.INDEX_NAME JOIN V$TABLESPACE AS TBS ON TBS.TBS_NAME = IDX.TABLESPACE_NAME WHERE COLS.TABLE_NAME = '";
    public static final String SQL_SELECT_INDEX_SCHEMA_INDEX_NAME = "select INDEX_SCHEMA, INDEX_NAME, TABLE_SCHEMA, TABLE_NAME, UNIQUENESS from ALL_INDEXES where table_schema = '";
    public static final String SQL_SELECT_TC_TABLE_SCHEMA_TC = "SELECT\r\nTC.TABLE_SCHEMA,\r\nTC.TABLE_NAME,\r\nTC.CONSTRAINT_NAME,\r\nTC.CONSTRAINT_TYPE,\r\nUCC.COLUMN_NAME\r\nFROM TABLE_CONSTRAINTS TC\r\nJOIN USER_CONS_COLUMNS UCC ON TC.TABLE_NAME = UCC.TABLE_NAME\r\nWHERE TC.TABLE_NAME = '";
    public static final String SQL_SELECT_UT_TABLE_SCHEMA_UT = "SELECT \r\nUT.TABLE_SCHEMA, \r\nUT.TABLE_NAME,\r\nUT.TABLESPACE_NAME,\r\nUT.PCT_FREE,\r\nUT.PCT_USED,\r\nUT.INI_TRANS,\r\nUT.MAX_TRANS,\r\nUT.INITIAL_EXTENT*TBS.EXTENT_SIZE,\r\nUT.NEXT_EXTENT*TBS.EXTENT_SIZE,\r\nUT.MIN_EXTENTS*TBS.EXTENT_SIZE,\r\nUT.MAX_EXTENTS*TBS.EXTENT_SIZE\r\nFROM ALL_TABLES UT \r\nJOIN V$TABLESPACE TBS ON TBS.TBS_NAME = UT.TABLESPACE_NAME \r\nWHERE UT.TABLE_NAME = '";
    public static final String ALL_PROCEDURES_SQL = "select OBJECT_NAME from ALL_PROCEDURES where owner = '%s' and schema_name = '%s' and OBJECT_TYPE = '%s' order by OBJECT_NAME";
    public static final String ALL_SOURCE_SQL = "select text from all_source where TYPE = '%s' and owner = '%s' and schema_name = '%s' and name = '%s'";
    public static final String TRIGGER_SQL = "SELECT OWNER, TRIGGER_NAME, TABLE_OWNER, TABLE_NAME, TRIGGERING_TYPE, TRIGGERING_EVENT, STATUS, TRIGGER_BODY "
            + "FROM ALL_TRIGGERS WHERE OWNER = '%s' AND TRIGGER_NAME = '%s'";
    public static final String TRIGGER_SQL_LIST = "SELECT OWNER, TRIGGER_NAME FROM ALL_TRIGGERS WHERE OWNER = '%s'";
    public static final String VIEW_SQL = "SELECT OWNER, VIEW_NAME, TEXT FROM ALL_VIEWS WHERE OWNER = '%s' AND VIEW_NAME = '%s'";
    public static final String INDEX_SQL = "SELECT i.TABLE_NAME, i.INDEX_TYPE, i.INDEX_NAME, i.UNIQUENESS ,c.COLUMN_NAME, c.COLUMN_POSITION, c.DESCEND, cons.CONSTRAINT_TYPE FROM ALL_INDEXES i JOIN ALL_IND_COLUMNS c ON i.INDEX_NAME = c.INDEX_NAME AND i.TABLE_NAME = c.TABLE_NAME AND i.TABLE_OWNER = c.TABLE_OWNER LEFT JOIN ALL_CONSTRAINTS cons ON i.INDEX_NAME = cons.INDEX_NAME AND i.TABLE_NAME = cons.TABLE_NAME AND i.TABLE_OWNER = cons.OWNER WHERE i.TABLE_OWNER = '%s' AND i.TABLE_NAME = '%s' ORDER BY i.INDEX_NAME, c.COLUMN_POSITION;";
    public static final List<String> SYSTEM_SCHEMAS = List.of("DEFINITION_SCHEMA", "DICTIONARY_SCHEMA",
            "FIXED_TABLE_SCHEMA", "INFORMATION_SCHEMA", "PERFORMANCE_VIEW_SCHEMA", "PUBLIC");


    private SUNDBMetaDataConstants() {
    }
}
