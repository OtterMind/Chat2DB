package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionPlan;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.mysql.parser.base.MySqlLexer;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.util.SqlUtils;
import com.alibaba.druid.DbType;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MysqlTransactionPolicy {

    private static final Pattern MYSQL_EXECUTABLE_COMMENT =
            Pattern.compile("/\\*!\\d{0,6}\\s*(.*?)\\*/", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern AUTOCOMMIT_ON_ASSIGNMENT = Pattern.compile(
            "(?i)(?:^|,)\\s*(?:(?:SESSION|LOCAL)\\s+|@@(?:session\\.)?)?autocommit\\s*(?:=|:=)\\s*(?:1|ON|TRUE)\\b");
    private static final Pattern PREPARE_SQL_LITERAL = Pattern.compile(
            "(?is)\\bFROM\\s+('(?:''|\\\\.|[^'])*'|\"(?:\"\"|\\\\.|[^\"])*\")");

    private static final Set<String> IMPLICIT_COMMIT_TYPES = Set.of(
            "CREATE", "ALTER", "DROP", "RENAME_TABLE", "TRUNCATE_TABLE",
            "CREATE_TABLE", "CREATE_VIEW", "CREATE_DATABASE", "CREATE_SCHEMA",
            "CREATE_FUNCTION", "CREATE_PROCEDURE", "CREATE_USER", "CREATE_EVENT",
            "CREATE_INDEX", "CREATE_TRIGGER", "CREATE_ROLE", "CREATE_CONSTRAINT",
            "CREATE_TABLESPACE", "CREATE_SERVER", "CREATE_LOGFILE_GROUP", "CREATE_UDF",
            "ALTER_TABLE", "ALTER_DATABASE", "ALTER_EVENT", "ALTER_FUNCTION",
            "ALTER_INSTANCE", "ALTER_LOGFILE_GROUP", "ALTER_PROCEDURE", "ALTER_SERVER",
            "ALTER_TABLESPACE", "ALTER_VIEW", "ALTER_USER",
            "DROP_DATABASE", "DROP_TABLE", "DROP_VIEW", "DROP_FUNCTION", "DROP_PROCEDURE",
            "DROP_USER", "DROP_ROLE", "DROP_EVENT", "DROP_INDEX", "DROP_TRIGGER",
            "DROP_CONSTRAINT", "DROP_SCHEMA", "DROP_LOGFILE_GROUP", "DROP_SERVER",
            "DROP_TABLESPACE", "GRANT", "REVOKE", "RENAME_USER", "SET_PASSWORD",
            "START_TRANSACTION", "BEGIN_WORK", "LOCK_TABLES", "UNLOCK_TABLES",
            "ANALYZE", "CHECK_TABLE", "OPTIMIZE_TABLE", "REPAIR_TABLE", "CACHE_INDEX",
            "FLUSH", "LOAD_INDEX", "RESET", "INSTALL_PLUGIN", "UNINSTALL_PLUGIN"
    );

    public void beforeExecute(SqlExecutionPlan plan) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo == null
                || !"MYSQL".equalsIgnoreCase(connectInfo.getDbType())
                || !Boolean.TRUE.equals(connectInfo.getConsoleOwn())) {
            return;
        }
        if (containsExecutableCommentImplicitCommit(plan.getSql())) {
            throw new BusinessException("transaction.implicitCommit.blocked");
        }
        List<SimpleSqlStatement> statements = SqlUtils.parseStatements(plan.getSql(), DbType.mysql, "MYSQL");
        boolean implicitCommit = statements.stream().anyMatch(statement ->
                isImplicitCommitStatement(statement.getSqlType(), statement.getSql()));
        if (implicitCommit) {
            throw new BusinessException("transaction.implicitCommit.blocked");
        }
    }

    static boolean isImplicitCommitStatement(String sqlType, String sql) {
        String normalizedType = normalizeType(sqlType);
        String normalizedSql = normalizeSql(sql);
        if (IMPLICIT_COMMIT_TYPES.contains(normalizedType)) {
            return true;
        }
        if (isTransactionEndingType(normalizedType)) {
            return isTransactionEndingSql(normalizedSql);
        }
        if ("EXECUTE".equals(normalizedType)) {
            return true;
        }
        if ("CALL".equals(normalizedType)) {
            return true;
        }
        if ("PREPARE".equals(normalizedType) && isUnsafePrepare(normalizedSql)) {
            return true;
        }
        if ("SET_AUTOCOMMIT".equals(normalizedType) && isAutocommitEnabled(normalizedSql)) {
            return true;
        }
        return isImplicitCommitBySql(sql) || containsExecutableCommentImplicitCommit(sql);
    }

    private static boolean containsExecutableCommentImplicitCommit(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        CommonTokenStream tokenStream = new CommonTokenStream(new MySqlLexer(CharStreams.fromString(sql)));
        tokenStream.fill();
        for (Token token : tokenStream.getTokens()) {
            if (token.getType() != MySqlLexer.SPEC_MYSQL_COMMENT) {
                continue;
            }
            Matcher executableComment = MYSQL_EXECUTABLE_COMMENT.matcher(token.getText());
            if (executableComment.matches() && isImplicitCommitBySql(executableComment.group(1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isImplicitCommitBySql(String sql) {
        String normalizedSql = normalizeSql(sql);
        if (normalizedSql.isEmpty()) {
            return false;
        }
        if (isAutocommitEnabled(normalizedSql)
                || isTransactionEndingSql(normalizedSql)
                || isUnsafePrepare(normalizedSql)
                || startsWithKeyword(normalizedSql, "EXECUTE")
                || isProcedureCall(normalizedSql)) {
            return true;
        }
        return startsWithKeyword(normalizedSql, "CREATE")
                || startsWithKeyword(normalizedSql, "ALTER")
                || startsWithKeyword(normalizedSql, "DROP")
                || startsWithKeyword(normalizedSql, "TRUNCATE")
                || startsWithKeyword(normalizedSql, "RENAME")
                || startsWithKeyword(normalizedSql, "GRANT")
                || startsWithKeyword(normalizedSql, "REVOKE")
                || startsWithKeyword(normalizedSql, "START TRANSACTION")
                || startsWithKeyword(normalizedSql, "BEGIN")
                || startsWithKeyword(normalizedSql, "LOCK TABLES")
                || startsWithKeyword(normalizedSql, "UNLOCK TABLES")
                || startsWithKeyword(normalizedSql, "ANALYZE")
                || startsWithKeyword(normalizedSql, "CHECK")
                || startsWithKeyword(normalizedSql, "OPTIMIZE")
                || startsWithKeyword(normalizedSql, "REPAIR")
                || startsWithKeyword(normalizedSql, "CACHE INDEX")
                || startsWithKeyword(normalizedSql, "FLUSH")
                || startsWithKeyword(normalizedSql, "LOAD INDEX")
                || startsWithKeyword(normalizedSql, "RESET")
                || startsWithKeyword(normalizedSql, "INSTALL PLUGIN")
                || startsWithKeyword(normalizedSql, "UNINSTALL PLUGIN")
                || startsWithKeyword(normalizedSql, "SET PASSWORD");
    }

    private static boolean isProcedureCall(String sql) {
        return startsWithKeyword(sql, "CALL") || startsWithKeyword(sql, "{ CALL");
    }

    private static boolean isAutocommitEnabled(String sql) {
        if (!startsWithKeyword(sql, "SET")) {
            return false;
        }
        String setBody = normalizeSql(sql).substring(3).trim();
        return AUTOCOMMIT_ON_ASSIGNMENT.matcher(setBody).find();
    }

    private static boolean isUnsafePrepare(String sql) {
        if (!startsWithKeyword(sql, "PREPARE")) {
            return false;
        }
        Matcher matcher = PREPARE_SQL_LITERAL.matcher(sql);
        if (!matcher.find()) {
            return true;
        }
        String preparedSql = unquoteSqlLiteral(matcher.group(1));
        return isImplicitCommitBySql(preparedSql) || containsExecutableCommentImplicitCommit(preparedSql);
    }

    private static boolean isTransactionEndingType(String normalizedType) {
        return "COMMIT".equals(normalizedType)
                || "ROLLBACK".equals(normalizedType)
                || "ROLLBACK_WORK".equals(normalizedType);
    }

    private static boolean isTransactionEndingSql(String sql) {
        String normalizedSql = normalizeSql(sql);
        if (startsWithKeyword(normalizedSql, "COMMIT")) {
            return true;
        }
        if (!startsWithKeyword(normalizedSql, "ROLLBACK")) {
            return false;
        }
        return !startsWithKeyword(normalizedSql, "ROLLBACK TO");
    }

    private static boolean startsWithKeyword(String sql, String keyword) {
        String normalizedSql = normalizeSql(sql).toUpperCase(Locale.ROOT);
        String normalizedKeyword = keyword.toUpperCase(Locale.ROOT);
        return normalizedSql.equals(normalizedKeyword) || normalizedSql.startsWith(normalizedKeyword + " ");
    }

    private static String normalizeType(String sqlType) {
        return sqlType == null ? "" : sqlType.toUpperCase(Locale.ROOT);
    }

    private static String normalizeSql(String sql) {
        if (sql == null) {
            return "";
        }
        CommonTokenStream tokenStream = new CommonTokenStream(new MySqlLexer(CharStreams.fromString(sql)));
        tokenStream.fill();
        StringBuilder normalizedSql = new StringBuilder();
        for (Token token : tokenStream.getTokens()) {
            if (token.getChannel() != Token.DEFAULT_CHANNEL || token.getType() == Token.EOF) {
                continue;
            }
            if (!normalizedSql.isEmpty()) {
                normalizedSql.append(' ');
            }
            normalizedSql.append(token.getText());
        }
        String normalized = normalizedSql.toString().trim();
        while (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private static String unquoteSqlLiteral(String literal) {
        if (literal == null || literal.length() < 2) {
            return "";
        }
        char quote = literal.charAt(0);
        String body = literal.substring(1, literal.length() - 1);
        String doubledQuote = String.valueOf(quote) + quote;
        return body.replace(doubledQuote, String.valueOf(quote))
                .replace("\\" + quote, String.valueOf(quote))
                .replace("\\\\", "\\");
    }

}
