package ai.chat2db.plugin.h2.constant;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import org.apache.commons.lang3.StringUtils;


public final class H2SqlBuilderConstants {

    public static final String SQL_COMMENT_ON_SCHEMA = "\nCOMMENT ON SCHEMA ";
    public static final String VALUE_IS_SINGLE_QUOTE = " IS '";
    public static final String SQL_CREATE_SCHEMA = "CREATE SCHEMA ";

    private H2SqlBuilderConstants() {
    }
}
