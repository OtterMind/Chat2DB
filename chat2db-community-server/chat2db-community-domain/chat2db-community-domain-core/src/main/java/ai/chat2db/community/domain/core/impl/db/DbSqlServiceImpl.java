package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlExecuteWithConnectionRequest;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlFormatRequest;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlValidSelectRequest;
import ai.chat2db.community.domain.api.service.db.IDbSqlService;
import ai.chat2db.spi.model.request.SqlStatementExecuteRequest;
import ai.chat2db.spi.util.JdbcUtils;
import ai.chat2db.spi.util.SqlParameterParser;
import ai.chat2db.spi.util.SqlParameterSyntax;
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
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class DbSqlServiceImpl implements IDbSqlService {

    private static final FormatConfig POSTGRESQL_FORMAT_CONFIG = FormatConfig.builder()
            .maxColumnLength(1)
            .build();

    private static final String PLACEHOLDER_MASK_PREFIX = "__c2db_param_";

    @Override
    public String format(DbSqlFormatRequest sqlFormatRequest) {
        String originalSql = sqlFormatRequest.getSql();
        String dbType = StringUtils.defaultString(sqlFormatRequest.getDbType()).toLowerCase();
        // The formatter splits named placeholders (`id=:id` becomes `id =: id`), so they are
        // swapped for plain identifiers while formatting and restored afterwards.
        List<String> namedPlaceholders = new ArrayList<>();
        String sql = maskNamedPlaceholders(originalSql, sqlFormatRequest.getDbType(), namedPlaceholders);
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
            return originalSql;
        }
        return unmaskNamedPlaceholders(sql, namedPlaceholders, originalSql);
    }

    private static String maskNamedPlaceholders(String sql, String dbType, List<String> namedPlaceholders) {
        SqlParameterSyntax syntax = SqlParameterSyntax.forDatabaseType(dbType);
        if (syntax == null || StringUtils.isEmpty(sql) || sql.contains(PLACEHOLDER_MASK_PREFIX)) {
            return sql;
        }
        StringBuilder masked = new StringBuilder(sql.length());
        int copiedUntil = 0;
        for (SqlParameterParser.Placeholder placeholder : SqlParameterParser.findPlaceholders(sql, syntax)) {
            if (placeholder.style() != SqlParameterParser.Style.NAMED) {
                continue;
            }
            masked.append(sql, copiedUntil, placeholder.start()).append(maskFor(namedPlaceholders.size()));
            namedPlaceholders.add(sql.substring(placeholder.start(), placeholder.end()));
            copiedUntil = placeholder.end();
        }
        return masked.append(sql, copiedUntil, sql.length()).toString();
    }

    private static String unmaskNamedPlaceholders(String formatted, List<String> namedPlaceholders, String originalSql) {
        for (int index = 0; index < namedPlaceholders.size(); index++) {
            String mask = maskFor(index);
            if (!formatted.contains(mask)) {
                // The formatter rewrote a mask; keep the user's SQL rather than lose a placeholder.
                return originalSql;
            }
            formatted = formatted.replace(mask, namedPlaceholders.get(index));
        }
        return formatted;
    }

    private static String maskFor(int index) {
        return PLACEHOLDER_MASK_PREFIX + index + "__";
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
