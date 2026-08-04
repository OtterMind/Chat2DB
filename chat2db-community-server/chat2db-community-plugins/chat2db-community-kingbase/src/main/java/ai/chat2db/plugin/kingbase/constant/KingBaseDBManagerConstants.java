package ai.chat2db.plugin.kingbase.constant;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.model.async.AsyncContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.DefaultSQLExecutor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;


public final class KingBaseDBManagerConstants {

    public static final String SQL_SET_SEARCH_PATH_USER_PUBLIC = "SET search_path TO %s,\"$user\",\"public\"";

    private KingBaseDBManagerConstants() {
    }
}
