package ai.chat2db.plugin.dm.constant;

import ai.chat2db.plugin.dm.enums.type.DMIndexTypeEnum;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import ai.chat2db.spi.DefaultSQLExecutor;
import cn.hutool.core.date.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;



public final class DMDBManagerConstants {

    public static final String SQL_COMMENT_COLUMN = "COMMENT ON COLUMN ";
    public static final String SQL_COMMENT_TABLE = "COMMENT ON TABLE ";
    public static final String SQL_DROP_TABLE_EXISTS = "DROP TABLE IF EXISTS %s";
    public static final String SQL_SET_SCHEMA = "SET SCHEMA %s";
    public static final String SQL_SELECT_DBMS_METADATA_GET_DDL = "SELECT DBMS_METADATA.GET_DDL('VIEW','%s','%s') as ddl FROM DUAL;";
    public static final String SQL_SELECT_TABLE_NAME_ALL_TABLES = "SELECT TABLE_NAME FROM ALL_TABLES where OWNER='%s' ";
    public static final String ROUTINES_SQL = "SELECT OWNER, NAME, TEXT FROM ALL_SOURCE WHERE TYPE = '%s' AND OWNER = '%s' AND NAME = '%s' ORDER BY LINE";
    public static final String TRIGGER_SQL_LIST = "SELECT OWNER, TRIGGER_NAME FROM ALL_TRIGGERS WHERE OWNER = '%s'";
    public static final String TRIGGER_SQL = "SELECT OWNER, TRIGGER_NAME, TABLE_OWNER, TABLE_NAME, TRIGGERING_TYPE, TRIGGERING_EVENT, STATUS, TRIGGER_BODY "
            + "FROM ALL_TRIGGERS WHERE OWNER = '%s' AND TRIGGER_NAME = '%s'";


    private DMDBManagerConstants() {
    }
}
