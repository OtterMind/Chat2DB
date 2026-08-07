package ai.chat2db.plugin.sqlserver.constant;

import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.spi.model.request.SqlStatementExecuteRequest;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;


public final class SqlServerExecutorConstants {

    public static final Pattern GO_DELIMITER_PATTERN = Pattern.compile(
            "(?mi)(?:^[ \\t]*|(?<=;)[ \\t]*)go[ \\t]*;?[ \\t]*(?:--.*)?(?=\\r?\\n|$)");

    private SqlServerExecutorConstants() {
    }
}
