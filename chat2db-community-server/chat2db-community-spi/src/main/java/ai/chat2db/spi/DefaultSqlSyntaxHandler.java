package ai.chat2db.spi;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.model.parser.result.SqlParserResponse;
import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.model.parser.statement.create.CreateTableStatement;
import ai.chat2db.community.domain.api.service.task.ITaskProgressListener;
import ai.chat2db.community.domain.api.service.db.ISqlBatchHandler;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionEditorHint;
import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionRequest;
import ai.chat2db.community.domain.api.model.completion.result.SqlCompletionResponse;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.util.TokenUtil;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.Token;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class DefaultSqlSyntaxHandler {


    private static final Map<String, ISqlSyntaxPlugin> sqlSyntaxPluginMap = new ConcurrentHashMap<>();

    static {
        log.info("load ISqlSyntaxPlugin start");
        loadFromDatabasePlugins();
        if(sqlSyntaxPluginMap.isEmpty()) {
            log.error("Failed to load ISqlSyntaxPlugin from database plugins");
        } else {
            log.info("Successfully loaded {} plugins", sqlSyntaxPluginMap.size());
        }
    }


    public static ISQLParser getSQLParser(DatabaseTypeEnum databaseTypeEnum) {
        ISqlSyntaxPlugin sqlSyntaxPlugin = sqlSyntaxPluginMap.get(resolvePluginKey(databaseTypeEnum.name()));
        if (sqlSyntaxPlugin == null) {
            if (databaseTypeEnum == DatabaseTypeEnum.REDIS
                    || databaseTypeEnum == DatabaseTypeEnum.MONGODB) {
                return null;
            }
            sqlSyntaxPlugin = sqlSyntaxPluginMap.get(DatabaseTypeEnum.MYSQL.name());
        }
        return sqlSyntaxPlugin.getSQLParser();
    }

    public static ISQLParser getSQLParser(String databaseType) {
        String normalizedDatabaseType = databaseType.toUpperCase();
        ISqlSyntaxPlugin sqlSyntaxPlugin = sqlSyntaxPluginMap.get(resolvePluginKey(normalizedDatabaseType));
        if (sqlSyntaxPlugin == null) {
            if (StringUtils.equalsAnyIgnoreCase(normalizedDatabaseType,
                    DatabaseTypeEnum.REDIS.name(),
                    DatabaseTypeEnum.MONGODB.name())) {
                return null;
            }
            sqlSyntaxPlugin = sqlSyntaxPluginMap.get(DatabaseTypeEnum.MYSQL.name());
        }
        return sqlSyntaxPlugin.getSQLParser();
    }

    private static String resolvePluginKey(String databaseType) {
        DatabaseTypeEnum databaseTypeEnum = DatabaseTypeEnum.from(databaseType);
        if (databaseTypeEnum == null) {
            return StringUtils.upperCase(databaseType);
        }
        if (StringUtils.equalsIgnoreCase(databaseType, DatabaseTypeEnum.OSCAR.name())) {
            return DatabaseTypeEnum.ORACLE.name();
        }
        return databaseTypeEnum.name();
    }

    public static SqlParserResponse parserStatements(String sql, String databaseType) {
        ISQLParser sqlParser = getSQLParser(databaseType);
        if (sqlParser == null) {
            return null;
        }
        return sqlParser.parserStatements(sql);
    }

    public static SqlParserResponse parserStatements(String sql, DatabaseTypeEnum databaseTypeEnum) {
        ISQLParser sqlParser = getSQLParser(databaseTypeEnum);
        if (sqlParser == null) {
            return null;
        }
        return sqlParser.parserStatements(sql);
    }

    public static CreateTableStatement parserCreateTableStatement(String sql, String databaseType) {
        ISQLParser sqlParser = getSQLParser(databaseType);
        if (sqlParser == null) {
            return null;
        }
        return sqlParser.parserCreateTableStatement(sql);
    }

    public static Set<String> getSqlKeysWords(String databaseType) {
        ISQLParser sqlParser = getSQLParser(databaseType);
        if (sqlParser == null) {
            return null;
        }
        return sqlParser.getSqlStartKeywords();
    }

    public static String buildSqlByKeywordsAroundCursor(String beforeSql, String afterSql, String databaseType) {
        ISQLParser sqlParser = getSQLParser(databaseType.toUpperCase());
        if (sqlParser == null) {
            return null;
        }
        Set<String> sqlKeywords = sqlParser.getSqlStartKeywords();
        List<Token> beforeAllTokens = sqlParser.getAllTokens(beforeSql);
        List<Token> afterAllTokens = sqlParser.getAllTokens(afterSql);
        return TokenUtil.buildSqlByKeywordsAroundCursor(beforeAllTokens, afterAllTokens, sqlKeywords);

    }

    public static String buildSqlByKeywordsBeforeCursor(String beforeSql, String databaseType) {
        ISQLParser sqlParser = getSQLParser(databaseType.toUpperCase());
        if (Objects.isNull(sqlParser)) {
            return null;
        }
        Set<String> sqlKeywords = sqlParser.getSqlStartKeywords();
        List<Token> beforeAllTokens = sqlParser.getAllTokens(beforeSql);
        return TokenUtil.buildSqlByKeywordsBeforeCursor(beforeAllTokens, sqlKeywords);
    }

    public static String buildSqlByKeywordsAfterCursor(String afterSql, String databaseType) {
        ISQLParser sqlParser = getSQLParser(databaseType.toUpperCase());
        if (Objects.isNull(sqlParser)) {
            return null;
        }
        Set<String> sqlKeywords = sqlParser.getSqlStartKeywords();
        List<Token> beforeAllTokens = sqlParser.getAllTokens(afterSql);
        return TokenUtil.buildSqlByKeywordsAfterCursor(beforeAllTokens, sqlKeywords);
    }

    public static List<Token> getTokensOnDefault(String sql, String databaseType) {
        ISQLParser sqlParser = getSQLParser(databaseType.toUpperCase());
        if (Objects.isNull(sqlParser)) {
            return null;
        }
        return sqlParser.getAllTokensOnDefault(sql);
    }

    public static List<Statement> simpleParserStatements(String sql, String databaseType) {
        ISQLParser sqlParser = getSQLParser(databaseType.toUpperCase());
        if (Objects.isNull(sqlParser)) {
            return null;
        }
        return sqlParser.simpleParserStatements(sql).getStatements();
    }

    public static List<Statement> validTableStatements(String sql,String databaseType){
        ISQLParser sqlParser = getSQLParser(databaseType.toUpperCase());
        if (Objects.isNull(sqlParser)) {
            return null;
        }
        return sqlParser.validTableStatements(sql).getStatements();
    }

    public static boolean isSelect(String sql, String databaseType) {
        ISQLParser sqlParser = getSQLParser(databaseType.toUpperCase());
        if (Objects.isNull(sqlParser)) {
            return false;
        }
        return sqlParser.isSelect(sql);
    }

    public static List<Statement> parserSqlScript(String sql, String databaseType) {
        ISQLParser sqlParser = getSQLParser(databaseType.toUpperCase());
        if (Objects.isNull(sqlParser)) {
            return List.of();
        }
        return sqlParser.parserSqlScript(sql);
    }

    public static SqlCompletionResponse complete(DbSqlCompletionRequest request) {
        if (Objects.isNull(request)) {
            return SqlCompletionResponse.rejected("sql.completion.request.null");
        }
        String databaseType = request.databaseType();
        if (StringUtils.isBlank(databaseType)) {
            return SqlCompletionResponse.unsupported(databaseType);
        }
        ISqlSyntaxPlugin sqlSyntaxPlugin = sqlSyntaxPluginMap.get(resolvePluginKey(databaseType));
        if (Objects.isNull(sqlSyntaxPlugin)) {
            return SqlCompletionResponse.unsupported(databaseType);
        }
        ISqlCompletionProvider completionProvider = sqlSyntaxPlugin.getSqlCompletionProvider();
        if (Objects.isNull(completionProvider)) {
            return SqlCompletionResponse.unsupported(databaseType);
        }
        return completionProvider.complete(request);
    }

    public static List<SqlCompletionEditorHint> editorHints(DbSqlCompletionRequest request) {
        if (Objects.isNull(request) || StringUtils.isBlank(request.databaseType())) {
            return List.of();
        }
        ISqlSyntaxPlugin sqlSyntaxPlugin = sqlSyntaxPluginMap.get(resolvePluginKey(request.databaseType()));
        if (Objects.isNull(sqlSyntaxPlugin)) {
            return List.of();
        }
        List<SqlCompletionEditorHint> hints = sqlSyntaxPlugin.getSqlEditorHints(request);
        return hints == null ? List.of() : hints;
    }

    public static int parserSqlScript(File file, String databaseType, ITaskProgressListener progressListener, ISqlBatchHandler sqlBatchHandler) {
        ISQLParser sqlParser = getSQLParser(databaseType.toUpperCase());
        if (Objects.isNull(sqlParser)) {
            return 0;
        }
       return sqlParser.parserSqlScript(file, progressListener, sqlBatchHandler);
    }

    private static void loadFromDatabasePlugins() {
        Chat2DBContext.PLUGIN_MAP.forEach((databaseType, plugin) -> {
            if (plugin == null) {
                return;
            }
            ISqlSyntaxPlugin sqlSyntaxPlugin = plugin.getSqlSyntaxPlugin();
            if (sqlSyntaxPlugin != null) {
                sqlSyntaxPluginMap.put(resolvePluginKey(databaseType), sqlSyntaxPlugin);
            }
        });
    }

}
