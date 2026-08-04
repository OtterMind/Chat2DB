package ai.chat2db.plugin.oracle.constant;

import ai.chat2db.plugin.oracle.builder.OracleSqlBuilder;
import ai.chat2db.plugin.oracle.enums.*;
import ai.chat2db.plugin.oracle.identifier.OracleIdentifierProcessor;
import ai.chat2db.plugin.oracle.enums.type.OracleColumnTypeEnum;
import ai.chat2db.plugin.oracle.enums.type.OracleDefaultValueEnum;
import ai.chat2db.plugin.oracle.enums.type.OracleIndexTypeEnum;
import ai.chat2db.plugin.oracle.value.OracleValueProcessor;
import ai.chat2db.community.tools.util.EasyStringUtils;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
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
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.util.SortUtils;
import ai.chat2db.spi.util.SqlUtils;
import com.google.common.collect.Lists;
import jakarta.validation.constraints.NotEmpty;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.Reader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;


public final class OracleMetaDataConstants {

    public static final String SQL_CREATE = "create ";
    public static final String SQL_CREATE_REPLACE = "CREATE OR REPLACE ";
    public static final String TABLE_DDL_SQL = "select dbms_metadata.get_ddl('TABLE','%s','%s') as sql from dual";
    public static final String TABLE_COMMENT_SQL = "select owner, table_name, comments from ALL_TAB_COMMENTS where OWNER = '%s'  and TABLE_NAME = '%s'";
    public static final String TABLE_COLUMN_COMMENT_SQL = """
            SELECT owner, table_name, column_name, comments
            FROM all_col_comments
            WHERE  owner = '%s' and table_name = '%s' and comments is not null""";
    public static final String PROCEDURE_LIST_DDL = """
            SELECT OBJECT_NAME, OBJECT_TYPE
            FROM ALL_OBJECTS
            WHERE OBJECT_TYPE IN ('PROCEDURE')
              AND OWNER = '%s'""";
    public static final String TABLE_INDEX_DDL_SQL = """
            SELECT DBMS_METADATA.GET_DDL('INDEX', index_name, table_owner) AS ddl,
            index_name AS INDEX_NAME
            FROM all_indexes
            WHERE table_owner = '%s' AND table_name = '%s'""";
    public static final String PU_INDEX_NAME_SQL = """
            SELECT DISTINCT AC.INDEX_NAME
            FROM ALL_CONSTRAINTS AC
            WHERE  AC.OWNER = '%s' AND AC.TABLE_NAME = '%s'
              AND AC.CONSTRAINT_TYPE IN ('P', 'U')""";
    public static final String TRIGGER_DDL_SQL = "SELECT DBMS_METADATA.GET_DDL('TRIGGER', '%s', '%s') as ddl FROM DUAL";
    public static final String SELECT_TABLE_SQL = "SELECT A.OWNER, A.TABLE_NAME, B.COMMENTS " +
            "FROM ALL_TABLES A LEFT JOIN ALL_TAB_COMMENTS B ON  A.OWNER = B.OWNER  AND A.TABLE_NAME = B.TABLE_NAME\n" +
            "where A.OWNER = '%s' ";
    public static final String SELECT_TAB_COLS = "SELECT atc.column_id , atc.column_name as COLUMN_NAME, atc.data_type as DATA_TYPE , atc.data_length as DATA_LENGTH , atc.data_type_mod , atc.nullable ,  atc.data_default as DATA_DEFAULT,  acc.comments ,  atc.DATA_PRECISION ,  atc.DATA_SCALE , atc.CHAR_USED  FROM  all_tab_columns atc, all_col_comments acc WHERE atc.owner = acc.owner AND atc.table_name = acc.table_name AND atc.column_name = acc.column_name AND atc.owner = '%s'  AND atc.table_name = '%s'  order by atc.column_id";
    public static final String ROUTINES_SQL = "SELECT LINE, TEXT "
            + "FROM ALL_SOURCE "
            + "WHERE TYPE = '%s' AND OWNER = '%s' AND NAME = '%s'"
            + "ORDER BY LINE";
    public static final String TRIGGER_SQL_LIST = "SELECT TRIGGER_NAME "
            + "FROM ALL_TRIGGERS WHERE OWNER = '%s'";
    public static final String SELECT_PK_SQL = "select  acc.CONSTRAINT_NAME from  all_cons_columns acc,  all_constraints ac  where  acc.constraint_name = ac.constraint_name  and acc.owner = ac.owner  and acc.owner = '%s'  and ac.constraint_type = 'P'  and ac.table_name = '%s' ";
    public static final String SELECT_TABLE_INDEX = "SELECT ai.index_name AS Key_name, aic.column_name AS Column_name, ai.index_type AS Index_type, ai.uniqueness AS Unique_name, aic.COLUMN_POSITION as Seq_in_index, aic.descend AS Collation, ex.COLUMN_EXPRESSION as COLUMN_EXPRESSION FROM all_ind_columns aic JOIN all_indexes ai ON aic.index_owner = ai.owner and aic.table_owner = ai.table_owner and aic.table_name = ai.table_name and aic.index_name = ai.index_name LEFT JOIN ALL_IND_EXPRESSIONS ex ON aic.index_owner = ex.index_owner and aic.table_owner = ex.table_owner and aic.table_name = ex.table_name and aic.index_name = ex.index_name and aic.COLUMN_POSITION = ex.COLUMN_POSITION where ai.table_owner = '%s' AND ai.table_name = '%s' ";
    public static final String VIEW_DDL_SQL = "SELECT VIEW_NAME, TEXT FROM ALL_VIEWS WHERE OWNER = '%s' AND VIEW_NAME = '%s'";
    public static final List<String> SYSTEM_SCHEMAS = List.of("ANONYMOUS", "APEX_030200", "APEX_PUBLIC_USER",
            "APPQOSSYS", "BI", "CTXSYS", "DBSNMP", "DIP", "EXFSYS", "FLOWS_FILES", "HR", "IX", "MDDATA",
            "MDSYS", "MGMT_VIEW", "OE", "OLAPSYS", "ORACLE_OCM", "ORDDATA", "ORDPLUGINS", "ORDSYS",
            "OUTLN", "OWBSYS", "OWBSYS_AUDIT", "PM", "SCOTT", "SH", "SI_INFORMTN_SCHEMA",
            "SPATIAL_CSW_ADMIN_USR", "SPATIAL_WFS_ADMIN_USR", "SYS", "SYSMAN", "SYSTEM", "WMSYS", "XDB",
            "XS$NULL");


    private OracleMetaDataConstants() {
    }
}
