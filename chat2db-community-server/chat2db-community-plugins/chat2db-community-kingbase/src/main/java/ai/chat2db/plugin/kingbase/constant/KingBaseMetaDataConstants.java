package ai.chat2db.plugin.kingbase.constant;

import ai.chat2db.plugin.kingbase.builder.KingBaseSqlBuilder;
import ai.chat2db.plugin.kingbase.identifier.KingBaseSQLIdentifierProcessor;
import ai.chat2db.plugin.kingbase.enums.type.KingBaseColumnTypeEnum;
import ai.chat2db.plugin.kingbase.enums.type.KingBaseDefaultValueEnum;
import ai.chat2db.plugin.kingbase.enums.type.KingBaseIndexTypeEnum;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
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
import ai.chat2db.spi.util.SqlUtils;
import jakarta.validation.constraints.NotEmpty;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;



public final class KingBaseMetaDataConstants {

    public static final String SQL_ALTER_TABLE = "alter table ";
    public static final String SQL_COMMENT_COLUMN = "comment on column ";
    public static final String SQL_COMMENT_INDEX = "comment on index ";
    public static final String SQL_COMMENT_TABLE = "comment on table ";
    public static final String SQL_CREATE_TABLE = "create table ";
    public static final String SQL_GRANT = "grant ";
    public static final String SQL_ON = " on ";
    public static final String SELECT_KEY_INDEX = "SELECT ccu.table_schema AS Foreign_schema_name, ccu.table_name AS Foreign_table_name, ccu.column_name AS Foreign_column_name, constraint_type AS Constraint_type, tc.CONSTRAINT_NAME AS Key_name, tc.TABLE_NAME, kcu.Column_name, tc.is_deferrable, tc.initially_deferred FROM information_schema.table_constraints AS tc JOIN information_schema.key_column_usage AS kcu ON tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME JOIN information_schema.constraint_column_usage AS ccu ON ccu.constraint_name = tc.constraint_name WHERE tc.TABLE_SCHEMA = '%s'  AND tc.TABLE_NAME = '%s';";
    public static final String SELECT_TABLE_INDEX = "SELECT tmp.INDISPRIMARY AS Index_primary, tmp.TABLE_SCHEM, tmp.TABLE_NAME, tmp.NON_UNIQUE, tmp.INDEX_QUALIFIER, tmp.INDEX_NAME AS Key_name, tmp.indisclustered, tmp.ORDINAL_POSITION AS Seq_in_index, trim(BOTH '\"' FROM sys_get_indexdef( tmp.CI_OID, tmp.ORDINAL_POSITION, FALSE) ) AS Column_name, CASE tmp.AM_NAME WHEN 'btree' THEN CASE tmp.I_INDOPTION [ tmp.ORDINAL_POSITION - 1 ] & 1 :: SMALLINT WHEN 1 THEN 'D' ELSE'A' END ELSE NULL END AS Collation, tmp.CARDINALITY, tmp.PAGES, tmp.FILTER_CONDITION , tmp.AM_NAME AS Index_method, tmp.DESCRIPTION AS Index_comment FROM ( SELECT n.nspname AS TABLE_SCHEM, ct.relname AS TABLE_NAME, NOT i.indisunique AS NON_UNIQUE, NULL AS INDEX_QUALIFIER, ci.relname AS INDEX_NAME, i.INDISPRIMARY , i.indisclustered , ( information_schema._sys_expandarray ( i.indkey ) ).n AS ORDINAL_POSITION, ci.reltuples AS CARDINALITY, ci.relpages AS PAGES, sys_get_expr ( i.indpred, i.indrelid ) AS FILTER_CONDITION, ci.OID AS CI_OID, i.indoption AS I_INDOPTION, am.amname AS AM_NAME , d.description FROM sys_class ct JOIN sys_namespace n ON ( ct.relnamespace = n.OID ) JOIN sys_index i ON ( ct.OID = i.indrelid ) JOIN sys_class ci ON ( ci.OID = i.indexrelid ) JOIN sys_am am ON ( ci.relam = am.OID ) left outer join sys_description d on i.indexrelid = d.objoid WHERE n.nspname = '%s' AND ct.relname = '%s' ) AS tmp";
    public static final String SELECT_TABLE_INDEX_8R6 = "SELECT tmp.INDISPRIMARY AS Index_primary, tmp.TABLE_SCHEM, tmp.TABLE_NAME, tmp.NON_UNIQUE, tmp.INDEX_QUALIFIER, tmp.INDEX_NAME AS Key_name, tmp.indisclustered, tmp.ORDINAL_POSITION AS Seq_in_index, trim(BOTH '\"' FROM sys_get_indexdef( tmp.CI_OID, tmp.ORDINAL_POSITION, FALSE) ) AS Column_name, CASE tmp.AM_NAME WHEN 'btree' THEN CASE tmp.I_INDOPTION [ tmp.ORDINAL_POSITION - 1 ] & 1 :: SMALLINT WHEN 1 THEN 'D' ELSE'A' END ELSE NULL END AS Collation, tmp.CARDINALITY, tmp.PAGES, tmp.FILTER_CONDITION , tmp.AM_NAME AS Index_method, tmp.DESCRIPTION AS Index_comment FROM ( SELECT n.nspname AS TABLE_SCHEM, ct.relname AS TABLE_NAME, NOT i.indisunique AS NON_UNIQUE, NULL AS INDEX_QUALIFIER, ci.relname AS INDEX_NAME, i.INDISPRIMARY , i.indisclustered , ( information_schema._pg_expandarray ( i.indkey ) ).n AS ORDINAL_POSITION, ci.reltuples AS CARDINALITY, ci.relpages AS PAGES, sys_get_expr ( i.indpred, i.indrelid ) AS FILTER_CONDITION, ci.OID AS CI_OID, i.indoption AS I_INDOPTION, am.amname AS AM_NAME , d.description FROM sys_class ct JOIN sys_namespace n ON ( ct.relnamespace = n.OID ) JOIN sys_index i ON ( ct.OID = i.indrelid ) JOIN sys_class ci ON ( ci.OID = i.indexrelid ) JOIN sys_am am ON ( ci.relam = am.OID ) left outer join sys_description d on i.indexrelid = d.objoid WHERE n.nspname = '%s' AND ct.relname = '%s' ) AS tmp";
    public static final String ROUTINES_SQL = " SELECT p.proname, p.prokind, sys_catalog.sys_get_functiondef(p.oid) as \"code\" FROM sys_catalog.sys_proc p where p.proname='%s'";
    public static final List<String> SYSTEM_DATABASES = List.of("SAMPLES", "SECURITY");
    public static final List<String> SYSTEM_SCHEMAS = List.of("pg_toast", "pg_temp_1", "pg_toast_temp_1",
            "pg_catalog", "information_schema");


    private KingBaseMetaDataConstants() {
    }
}
