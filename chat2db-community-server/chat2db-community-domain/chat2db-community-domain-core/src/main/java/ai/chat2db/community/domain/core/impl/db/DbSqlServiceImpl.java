package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlExecuteWithConnectionRequest;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlFormatRequest;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlValidSelectRequest;
import ai.chat2db.community.domain.api.service.db.IDbSqlService;
import ai.chat2db.spi.model.request.SqlStatementExecuteRequest;
import ai.chat2db.spi.util.JdbcUtils;
import ai.chat2db.spi.util.SqlUtils;
import ai.chat2db.spi.DefaultSQLExecutor;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.github.vertical_blank.sqlformatter.SqlFormatter;
import com.github.vertical_blank.sqlformatter.core.FormatConfig;
import com.github.vertical_blank.sqlformatter.languages.Dialect;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.List;

@Slf4j
@Service
public class DbSqlServiceImpl implements IDbSqlService {

    private static final FormatConfig POSTGRESQL_FORMAT_CONFIG = FormatConfig.builder()
            .maxColumnLength(1)
            .build();

    @Override
    public String format(DbSqlFormatRequest sqlFormatRequest) {
        String sql = sqlFormatRequest.getSql();
        String dbType = StringUtils.defaultString(sqlFormatRequest.getDbType()).toLowerCase();
        try {
            switch (dbType) {
                case "mysql":
                    sql = SqlFormatter.of(Dialect.MySql).format(sql);
                    break;
                case "postgresql":
                    sql = isPostgreSqlInsertScript(sql)
                            ? SqlFormatter.of(Dialect.PostgreSql).format(sql, POSTGRESQL_FORMAT_CONFIG)
                            : SqlFormatter.of(Dialect.PostgreSql).format(sql);
                    break;
                case "oracle":
                    sql = SqlFormatter.of(Dialect.PlSql).format(sql);
                    break;
                case "sqlserver":
                    sql = SqlFormatter.of(Dialect.TSql).format(sql);
                    break;
                case "db2":
                    sql = SqlFormatter.of(Dialect.Db2).format(sql);
                    break;
                case "mariadb":
                    sql = SqlFormatter.of(Dialect.MariaDb).format(sql);
                    break;
                default:
                    sql = SqlFormatter.format(sql);
                    break;
            }
        } catch (Exception e) { // impl-contract: fallback - SQL formatter failure returns the original SQL.
            log.debug("sql format failed", e);
        }
        return sql;
    }

    private boolean isPostgreSqlInsertScript(String sql) {
        try {
            List<SQLStatement> statements = SQLUtils.parseStatements(sql, DbType.postgresql);
            return !statements.isEmpty() && statements.stream().allMatch(SQLInsertStatement.class::isInstance);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Boolean validSelect(DbSqlValidSelectRequest sqlValidSelectRequest) {
        DbType dbType = JdbcUtils.parse2DruidDbType(sqlValidSelectRequest.getDbType());
        try {
            SQLStatement sqlStatement = SQLUtils.parseSingleStatement(sqlValidSelectRequest.getSql(), dbType);
            return sqlStatement instanceof SQLSelectStatement;
        } catch (Exception e) { // impl-contract: fallback - parse failure means the SQL is not a valid SELECT.
            log.error("validSelect error", e);
            return Boolean.FALSE;
        }
    }

    @Override
    public String removeComment(String sql, String dbType) {
        DbType druidDbType = JdbcUtils.parse2DruidDbType(dbType);
        druidDbType = druidDbType == null ? DbType.mysql : druidDbType;
        return SQLParserUtils.removeComment(sql, druidDbType);
    }

    @Override
    public List<SimpleSqlStatement> parseStatements(String sql, String dbType) {
        DbType druidDbType = JdbcUtils.parse2DruidDbType(dbType);
        return SqlUtils.parseStatements(sql, druidDbType, dbType);
    }

    @Override
    public List<SimpleSqlStatement> parseAndValidTableStatements(String sql, String dbType) {
        return SqlUtils.parseAndValidTableStatements(sql, dbType);
    }

    @Override
    public String getInheritedType(String dbType) {
        return SqlUtils.getInheritedType(dbType);
    }

    @Override
    public String result2Markdown(ExecuteResponse result) {
        return SqlUtils.result2Markdown(result);
    }

    @Override
    public ExecuteResponse executeWithConnection(DbSqlExecuteWithConnectionRequest sqlExecuteWithConnectionRequest)
            throws SQLException {
        return DefaultSQLExecutor.getInstance().execute(SqlStatementExecuteRequest.builder()
                .sql(sqlExecuteWithConnectionRequest.getSql())
                .connection(sqlExecuteWithConnectionRequest.getConnection())
                .limitRowSize(sqlExecuteWithConnectionRequest.isOffset())
                .offset(sqlExecuteWithConnectionRequest.getPageNo())
                .count(sqlExecuteWithConnectionRequest.getPageSize())
                .build());
    }
}
