package ai.chat2db.plugin.mysql.constant;

import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.plugin.mysql.builder.MysqlSqlBuilder;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.model.metadata.Procedure;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.constant.SQLConstants;
import cn.hutool.core.date.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.Date;



public final class MysqlDBManagerConstants {

    public static final String DELIMITER_BLOCK_START = "delimiter ;;";
    public static final String DELIMITER_BLOCK_END = "delimiter ;";
    public static final String CREATE_FUNCTION_COLUMN = "Create Function";
    public static final String CREATE_PROCEDURE_COLUMN = "Create Procedure";
    public static final String CREATE_EVENT_COLUMN = "Create Event";
    public static final String CREATE_TABLE_COLUMN = "Create Table";
    public static final String CREATE_VIEW_COLUMN = "Create View";
    public static final String EXPORT_TABLE_ERROR_MESSAGE = "export table %s error:%s";
    public static final String FUNCTION_NAME_COLUMN = "FUNCTION_NAME";
    public static final String EVENT_NAME_COLUMN = "EVENT_NAME";
    public static final String PROCEDURE_NAME_COLUMN = "Name";
    public static final String ORIGINAL_STATEMENT_COLUMN = "SQL Original Statement";
    public static final String SYSTEM_TABLE_TYPE = "SYSTEM TABLE";
    public static final String TABLE_NAME_COLUMN = "TABLE_NAME";
    public static final String TABLE_TYPE = "TABLE";
    public static final String TRIGGER_NAME_COLUMN = "Trigger";
    public static final String VIEW_TYPE = "VIEW";
    public static final String EXPORTING_TABLE_MESSAGE = ":Exporting table ";
    public static final String EXPORTING_TABLE_DATA_MESSAGE = ":Exporting table data ";
    public static final String EXPORT_TABLE_ERROR_LOG = "export table error";
    public static final String EXPORT_TABLES_MESSAGE = ":Exporting tables";
    public static final String EXPORT_VIEWS_MESSAGE = ":Exporting views";
    public static final String EXPORT_PROCEDURES_MESSAGE = ":Exporting producers";
    public static final String EXPORT_TRIGGERS_MESSAGE = ":Exporting triggers";
    public static final String EXPORT_FUNCTIONS_MESSAGE = ":Exporting functions";
    public static final String ROUTINE_DELIMITER = ";;";

    private MysqlDBManagerConstants() {
    }
}
