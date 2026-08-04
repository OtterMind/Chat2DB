package ai.chat2db.plugin.h2.constant;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.model.async.AsyncContext;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;


public final class H2DBManagerConstants {

    public static final String SQL_DROP_TABLE = "DROP TABLE %s";
    public static final String SQL_SET_SCHEMA = "SET SCHEMA %s";

    private H2DBManagerConstants() {
    }
}
