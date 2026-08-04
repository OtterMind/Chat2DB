package ai.chat2db.plugin.dm.constant;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.plugin.dm.enums.type.DMColumnTypeEnum;
import ai.chat2db.plugin.dm.enums.type.DMIndexTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.model.request.PageLimitRequest;
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
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;


public final class DMSqlBuilderConstants {

    public static final String VALUE_DOUBLE_QUOTE_OPEN_PAREN = "\" (";
    public static final String VALUE_DOUBLE_QUOTE_IS_SINGLE_QUOTE = "\" IS '";
    public static final String SQL_ALTER_TABLE = "ALTER TABLE ";
    public static final String SQL_COMMENT_COLUMN = "COMMENT ON COLUMN ";
    public static final String SQL_COMMENT_TABLE = "COMMENT ON TABLE ";
    public static final String SQL_CREATE_SCHEMA = "CREATE SCHEMA ";
    public static final String SQL_CREATE_TABLE = "CREATE TABLE ";
    public static final String SQL_LIMIT = " LIMIT ";
    public static final String SQL_OFFSET = " OFFSET ";
    public static final String SQL_RENAME = "RENAME TO ";

    private DMSqlBuilderConstants() {
    }
}
