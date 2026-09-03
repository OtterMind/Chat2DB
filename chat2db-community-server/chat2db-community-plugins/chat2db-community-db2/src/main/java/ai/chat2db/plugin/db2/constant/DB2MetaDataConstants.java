package ai.chat2db.plugin.db2.constant;

import ai.chat2db.plugin.db2.builder.DB2SqlBuilder;
import ai.chat2db.plugin.db2.enums.type.DB2ColumnTypeEnum;
import ai.chat2db.plugin.db2.enums.type.DB2DefaultValueEnum;
import ai.chat2db.plugin.db2.enums.type.DB2IndexTypeEnum;
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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;



public final class DB2MetaDataConstants {

    public static final String GET_DDL_TOKEN = "call SYSPROC.DB2LK_GENERATE_DDL(?,?)";
    public static final String DB2LOOK_OPTIONS = "-e -x -xd -td \"%s\" -t \"%s\"";
    public static final String GET_DDL_SQL = "select SQL_STMT from SYSTOOLS.DB2LOOK_INFO where OP_TOKEN =  ? order by OP_SEQUENCE ASC";
    public static final String CLEAN_DDL_TOKEN = "CALL SYSPROC.DB2LK_CLEAN_TABLE(?)";
    public static final String IDX_SQL = "SELECT i.INDNAME, i.UNIQUERULE, i.REMARKS, ic.COLNAME, ic.COLSEQ, ic.COLORDER FROM SYSCAT.INDEXES i JOIN SYSCAT.INDEXCOLUSE ic ON i.INDNAME = ic.INDNAME AND i.INDSCHEMA = ic.INDSCHEMA WHERE i.TABNAME = '%s' AND i.INDSCHEMA = '%s' ORDER BY i.INDNAME, ic.COLSEQ";
    public static final String VIEW_DDL_SQL = "select TEXT from syscat.views where VIEWSCHEMA='%s' and VIEWNAME='%s'";
    public static final String ROUTINE_DDL_SQL = "select TEXT from syscat.routines where ROUTINESCHEMA='%s' and ROUTINENAME='%s' and ROUTINETYPE='%s'";
    public static final List<String> SYSTEM_SCHEMAS = List.of("NULLID", "SQLJ", "SYSCAT", "SYSFUN", "SYSIBM",
            "SYSIBMADM", "SYSIBMINTERNAL", "SYSIBMTS", "SYSPROC", "SYSPUBLIC", "SYSSTAT", "SYSTOOLS");


    private DB2MetaDataConstants() {
    }
}
