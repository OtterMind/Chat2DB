package ai.chat2db.plugin.sundb.constant;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.plugin.sundb.enums.type.SUNDBColumnTypeEnum;
import ai.chat2db.plugin.sundb.enums.type.SUNDBIndexTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;


public final class SUNDBSqlBuilderConstants {

    public static final String SQL_ALTER_TABLE = "ALTER TABLE ";
    public static final String SQL_COMMENT_COLUMN = "COMMENT ON COLUMN ";
    public static final String SQL_COMMENT_TABLE = "COMMENT ON TABLE ";
    public static final String SQL_CREATE_SCHEMA = "CREATE SCHEMA ";
    public static final String SQL_CREATE_TABLE = "CREATE TABLE ";
    public static final String SQL_LIMIT = " LIMIT ";
    public static final String SQL_OFFSET = " OFFSET ";
    public static final String SQL_RENAME = "RENAME TO ";

    private SUNDBSqlBuilderConstants() {
    }
}
