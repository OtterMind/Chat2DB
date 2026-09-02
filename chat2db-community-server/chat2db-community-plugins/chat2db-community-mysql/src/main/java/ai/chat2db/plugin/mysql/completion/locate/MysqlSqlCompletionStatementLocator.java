package ai.chat2db.plugin.mysql.completion.locate;

import ai.chat2db.plugin.mysql.enums.completion.locate.RoutineBlockTypeEnum;
import ai.chat2db.plugin.mysql.enums.completion.locate.StatementWindowAuthorityEnum;
import ai.chat2db.plugin.mysql.completion.util.MysqlSqlCompletionTokenUtil;
import ai.chat2db.mysql.parser.base.MySqlLexer;
import ai.chat2db.mysql.parser.base.MySqlParser;
import ai.chat2db.community.domain.api.model.completion.result.SqlCompletionInputCleanResponse;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionStatementWindowTypeEnum;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionStatementWindow;
import ai.chat2db.spi.parser.completion.SqlCompletionPipelineState;
import ai.chat2db.spi.ISqlCompletionStatementLocator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;


public final class MysqlSqlCompletionStatementLocator implements ISqlCompletionStatementLocator {

    private static final Set<Integer> SQL_STATEMENT_START_TOKENS = Set.of(
            MySqlLexer.ALTER,
            MySqlLexer.ANALYZE,
            MySqlLexer.BEGIN,
            MySqlLexer.BINLOG,
            MySqlLexer.CALL,
            MySqlLexer.CACHE,
            MySqlLexer.CHANGE,
            MySqlLexer.CHECK,
            MySqlLexer.CHECKSUM,
            MySqlLexer.COMMIT,
            MySqlLexer.CREATE,
            MySqlLexer.DEALLOCATE,
            MySqlLexer.DELETE,
            MySqlLexer.DESC,
            MySqlLexer.DESCRIBE,
            MySqlLexer.DO,
            MySqlLexer.DROP,
            MySqlLexer.EXECUTE,
            MySqlLexer.EXPLAIN,
            MySqlLexer.FLUSH,
            MySqlLexer.GET,
            MySqlLexer.GRANT,
            MySqlLexer.HANDLER,
            MySqlLexer.HELP,
            MySqlLexer.INSERT,
            MySqlLexer.INSTALL,
            MySqlLexer.KILL,
            MySqlLexer.LOAD,
            MySqlLexer.LOCK,
            MySqlLexer.OPTIMIZE,
            MySqlLexer.PREPARE,
            MySqlLexer.PURGE,
            MySqlLexer.RENAME,
            MySqlLexer.REPLACE,
            MySqlLexer.REPAIR,
            MySqlLexer.RELEASE,
            MySqlLexer.RESET,
            MySqlLexer.RESIGNAL,
            MySqlLexer.REVOKE,
            MySqlLexer.ROLLBACK,
            MySqlLexer.SAVEPOINT,
            MySqlLexer.SET,
            MySqlLexer.SELECT,
            MySqlLexer.SHOW,
            MySqlLexer.SHUTDOWN,
            MySqlLexer.SIGNAL,
            MySqlLexer.START,
            MySqlLexer.STOP,
            MySqlLexer.TABLE,
            MySqlLexer.TRUNCATE,
            MySqlLexer.UNINSTALL,
            MySqlLexer.UNLOCK,
            MySqlLexer.UPDATE,
            MySqlLexer.USE,
            MySqlLexer.VALUES,
            MySqlLexer.WITH,
            MySqlLexer.XA);

    private static final Set<Integer> NON_ROUTINE_CREATE_OBJECT_TOKENS = Set.of(
            MySqlLexer.DATABASE,
            MySqlLexer.EVENT,
            MySqlLexer.INDEX,
            MySqlLexer.ROLE,
            MySqlLexer.SCHEMA,
            MySqlLexer.SEQUENCE,
            MySqlLexer.SERVER,
            MySqlLexer.TABLE,
            MySqlLexer.TABLESPACE,
            MySqlLexer.TEMPORARY,
            MySqlLexer.USER,
            MySqlLexer.VIEW);

    @Override
    public SqlCompletionStatementWindow locate(SqlCompletionPipelineState state) {
        return locate(state == null ? null : state.input());
    }

    public SqlCompletionStatementWindow locate(SqlCompletionInputCleanResponse input) {
        String sourceSql = input.sourceSql();
        String parseSql = input.parseSql();
        int cursor = input.cursor();
        if (sourceSql.isEmpty()) {
            return new SqlCompletionStatementWindow("", "", 0, 0, 0,
                    SqlCompletionStatementWindowTypeEnum.EMPTY_STATEMENT.name());
        }

        List<Token> tokens = MysqlSqlCompletionTokenUtil.tokens(parseSql);
        if (canUseTokenWindow(tokens) && !hasUnsafeLexerErrors(sourceSql)) {
            return tokenFallbackWindow(sourceSql, parseSql, cursor, tokens, List.of());
        }

        ParserWindowResult rawResult = parseWindow(parseSql, cursor, StatementWindowAuthorityEnum.RAW_PARSE);
        if (rawResult.proof().isPresent() && rawResult.syntaxErrors() == 0) {
            return toWindow(sourceSql, parseSql, rawResult.proof().get(), cursor);
        }

        Optional<StatementWindowProof> fullProbe = probeProof(parseSql, cursor, false);
        if (fullProbe.isPresent()) {
            return toWindow(sourceSql, parseSql, fullProbe.get(), cursor);
        }

        Optional<StatementWindowProof> prefixProbe = probeProof(parseSql, cursor, true);
        if (prefixProbe.isPresent()) {
            return toWindow(sourceSql, parseSql, prefixProbe.get(), cursor);
        }

        if (rawResult.proof().isPresent()) {
            return toWindow(sourceSql, parseSql, rawResult.proof().get(), cursor);
        }

        return tokenFallbackWindow(sourceSql, parseSql, cursor);
    }

    private Optional<StatementWindowProof> probeProof(String parseSql, int cursor, boolean prefixOnly) {
        StatementProbeSql probeSql = StatementProbeSql.build(parseSql, cursor, prefixOnly);
        ParserWindowResult probeResult = parseWindow(probeSql.sql(), probeSql.probeCursor(),
                StatementWindowAuthorityEnum.PROBE_PARSE);
        if (probeResult.syntaxErrors() > 0 || probeResult.proof().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(probeSql.mapBack(probeResult.proof().get(), parseSql.length()));
    }

    private SqlCompletionStatementWindow tokenFallbackWindow(String sourceSql, String parseSql, int cursor) {
        List<Token> tokens = MysqlSqlCompletionTokenUtil.tokens(parseSql);
        List<RoutineWindow> routineWindows = findRoutineWindows(tokens, parseSql);
        return tokenFallbackWindow(sourceSql, parseSql, cursor, tokens, routineWindows);
    }

    private SqlCompletionStatementWindow tokenFallbackWindow(String sourceSql,
                                                              String parseSql,
                                                              int cursor,
                                                              List<Token> tokens,
                                                              List<RoutineWindow> routineWindows) {
        RoutineWindow routineWindow = containingRoutineWindow(routineWindows, cursor);
        if (Objects.nonNull(routineWindow)) {
            return new SqlCompletionStatementWindow(
                    sourceSql.substring(routineWindow.start(), routineWindow.end()),
                    parseSql.substring(routineWindow.start(), routineWindow.end()),
                    routineWindow.start(),
                    routineWindow.end(),
                    cursor - routineWindow.start(),
                    SqlCompletionStatementWindowTypeEnum.CURRENT_STATEMENT.name());
        }

        int segmentStart = findSegmentStart(tokens, routineWindows, cursor);
        int segmentEnd = findSegmentEnd(tokens, routineWindows, cursor, parseSql.length());
        Token statementStart = firstStatementStart(tokens, segmentStart, cursor);
        int firstContent = firstNonWhitespace(parseSql, segmentStart, cursor);
        if (Objects.isNull(statementStart) && firstContent < 0) {
            return new SqlCompletionStatementWindow(
                    sourceSql.substring(segmentStart, cursor),
                    parseSql.substring(segmentStart, cursor),
                    segmentStart,
                    cursor,
                    cursor - segmentStart,
                    SqlCompletionStatementWindowTypeEnum.EMPTY_STATEMENT.name());
        }
        int windowStart = Objects.nonNull(statementStart) ? statementStart.getStartIndex() : firstContent;
        int windowEnd = findWindowEnd(parseSql, windowStart, segmentEnd, cursor);
        SqlCompletionStatementWindowTypeEnum type = resolveType(parseSql, windowStart, windowEnd, cursor);
        return new SqlCompletionStatementWindow(
                sourceSql.substring(windowStart, windowEnd),
                parseSql.substring(windowStart, windowEnd),
                windowStart,
                windowEnd,
                cursor - windowStart,
                type.name());
    }

    private boolean canUseTokenWindow(List<Token> tokens) {
        boolean hasStatementSeparator = false;
        int parenthesisDepth = 0;
        int topLevelStatementStarts = 0;
        boolean balancedParentheses = true;
        for (Token token : tokens) {
            if (!MysqlSqlCompletionTokenUtil.isDefaultToken(token)) {
                continue;
            }
            int tokenType = token.getType();
            if (tokenType == MySqlLexer.SEMI) {
                hasStatementSeparator = true;
                continue;
            }
            if (tokenType == MySqlLexer.CREATE || tokenType == MySqlLexer.DELIMITER_STATEMENT) {
                return false;
            }
            if (tokenType == MySqlLexer.LR_BRACKET) {
                parenthesisDepth++;
                continue;
            }
            if (tokenType == MySqlLexer.RR_BRACKET) {
                if (parenthesisDepth == 0) {
                    balancedParentheses = false;
                } else {
                    parenthesisDepth--;
                }
                continue;
            }
            if (parenthesisDepth == 0 && isSqlStatementStartToken(tokenType)) {
                topLevelStatementStarts++;
            }
        }
        return balancedParentheses && parenthesisDepth == 0
                && (hasStatementSeparator || topLevelStatementStarts == 1);
    }

    private boolean hasUnsafeLexerErrors(String sourceSql) {
        for (Token token : MysqlSqlCompletionTokenUtil.tokens(sourceSql)) {
            if (token.getChannel() == MySqlLexer.ERRORCHANNEL && !"?".equals(token.getText())) {
                return true;
            }
        }
        return false;
    }

    private ParserWindowResult parseWindow(String sql, int cursor, StatementWindowAuthorityEnum authority) {
        CommonTokenStream tokenStream = MysqlSqlCompletionTokenUtil.tokenStream(sql);
        ParsedRoot parsedRoot = parseRoot(tokenStream);
        return new ParserWindowResult(parserProof(parsedRoot.root(), sql, cursor, authority),
                parsedRoot.syntaxErrorCount());
    }

    private ParsedRoot parseRoot(CommonTokenStream tokenStream) {
        MySqlParser parser = new MySqlParser(tokenStream);
        parser.removeErrorListeners();
        parser.setErrorHandler(new DefaultErrorStrategy());
        MySqlParser.RootContext root = parser.root();
        int syntaxErrorCount = parser.getNumberOfSyntaxErrors();
        tokenStream.seek(0);
        return new ParsedRoot(root, syntaxErrorCount);
    }

    private Optional<StatementWindowProof> parserProof(MySqlParser.RootContext root,
                                                       String sql,
                                                       int cursor,
                                                       StatementWindowAuthorityEnum authority) {
        if (Objects.isNull(root) || Objects.isNull(root.sqlStatements())
                || Objects.isNull(root.sqlStatements().children)) {
            return Optional.empty();
        }
        StatementWindowProof selected = null;
        for (ParseTree child : root.sqlStatements().children) {
            if (!(child instanceof MySqlParser.SqlStatementContext context)) {
                continue;
            }
            StatementWindowProof proof = proofFor(context, sql, cursor, authority).orElse(null);
            if (Objects.isNull(proof)) {
                continue;
            }
            if (Objects.isNull(selected)
                    || proof.startOffset() < selected.startOffset()
                    || proof.startOffset() == selected.startOffset() && proof.endOffset() > selected.endOffset()) {
                selected = proof;
            }
        }
        return Optional.ofNullable(selected);
    }

    private Optional<StatementWindowProof> proofFor(ParserRuleContext context,
                                                    String sql,
                                                    int cursor,
                                                    StatementWindowAuthorityEnum authority) {
        if (Objects.isNull(context) || !hasUsableToken(context.start)) {
            return Optional.empty();
        }
        if (!isSqlStatementStartToken(context.start.getType())) {
            return Optional.empty();
        }
        int start = context.start.getStartIndex();
        int stop = statementStopOffset(context, sql.length());
        if (!ownsCursor(sql, start, stop, cursor)) {
            return Optional.empty();
        }
        int end = Math.max(cursor, trimRight(sql, start, stop));
        return Optional.of(new StatementWindowProof(authority, start, Math.max(start, end)));
    }

    private int statementStopOffset(ParserRuleContext context, int sqlLength) {
        if (Objects.isNull(context.stop) || context.stop.getType() == Token.EOF || context.stop.getStopIndex() < 0) {
            return sqlLength;
        }
        return Math.max(0, Math.min(context.stop.getStopIndex() + 1, sqlLength));
    }

    private boolean ownsCursor(String sql, int start, int stop, int cursor) {
        int safeCursor = Math.max(0, Math.min(cursor, sql.length()));
        if (start <= safeCursor && safeCursor <= stop) {
            return true;
        }
        return start <= safeCursor && stop <= safeCursor && isBlank(sql, stop, safeCursor);
    }

    private SqlCompletionStatementWindow toWindow(String sourceSql,
                                                  String parseSql,
                                                  StatementWindowProof proof,
                                                  int cursor) {
        int start = Math.max(0, Math.min(proof.startOffset(), parseSql.length()));
        int proofEnd = Math.max(start, Math.min(proof.endOffset(), parseSql.length()));
        int end = Math.max(cursor, trimRight(parseSql, start, proofEnd));
        end = Math.max(start, Math.min(end, parseSql.length()));
        SqlCompletionStatementWindowTypeEnum type = resolveType(parseSql, start, end, cursor);
        return new SqlCompletionStatementWindow(
                sourceSql.substring(start, end),
                parseSql.substring(start, end),
                start,
                end,
                cursor - start,
                type.name());
    }

    private int findSegmentStart(List<Token> tokens, List<RoutineWindow> routineWindows, int cursor) {
        int segmentStart = 0;
        for (RoutineWindow routineWindow : routineWindows) {
            if (routineWindow.separatorEnd() <= cursor) {
                segmentStart = Math.max(segmentStart, routineWindow.separatorEnd());
            }
        }
        for (Token token : tokens) {
            if (!MysqlSqlCompletionTokenUtil.isDefaultToken(token) || token.getType() != MySqlLexer.SEMI) {
                continue;
            }
            if (isRoutineSeparator(token, routineWindows)) {
                continue;
            }
            if (token.getStopIndex() < cursor) {
                segmentStart = token.getStopIndex() + 1;
                continue;
            }
            break;
        }
        return segmentStart;
    }

    private int findSegmentEnd(List<Token> tokens, List<RoutineWindow> routineWindows, int cursor, int sqlLength) {
        for (Token token : tokens) {
            if (!MysqlSqlCompletionTokenUtil.isDefaultToken(token) || token.getType() != MySqlLexer.SEMI) {
                continue;
            }
            if (isRoutineSeparator(token, routineWindows)) {
                continue;
            }
            if (token.getStartIndex() >= cursor) {
                return token.getStartIndex();
            }
        }
        return sqlLength;
    }

    private List<RoutineWindow> findRoutineWindows(List<Token> tokens, String sql) {
        List<RoutineWindow> windows = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (!MysqlSqlCompletionTokenUtil.isDefaultToken(token) || token.getType() != MySqlLexer.CREATE) {
                continue;
            }
            int routineKeywordIndex = findRoutineKeywordIndex(tokens, index);
            if (routineKeywordIndex < 0) {
                continue;
            }
            RoutineWindow window = resolveRoutineWindow(tokens, index, routineKeywordIndex, sql);
            if (Objects.nonNull(window)) {
                windows.add(window);
                index = Math.max(index, window.lastTokenIndex());
            }
        }
        return windows;
    }

    private int findRoutineKeywordIndex(List<Token> tokens, int createIndex) {
        for (int index = createIndex + 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (!MysqlSqlCompletionTokenUtil.isDefaultToken(token)) {
                continue;
            }
            int tokenType = token.getType();
            if (tokenType == MySqlLexer.PROCEDURE || tokenType == MySqlLexer.FUNCTION || tokenType == MySqlLexer.TRIGGER) {
                return index;
            }
            if (tokenType == MySqlLexer.SEMI || tokenType == MySqlLexer.DELIMITER_STATEMENT) {
                return -1;
            }
            if (isNonRoutineCreateObjectToken(tokenType)) {
                return -1;
            }
        }
        return -1;
    }

    private RoutineWindow resolveRoutineWindow(List<Token> tokens,
                                               int createIndex,
                                               int routineKeywordIndex,
                                               String sql) {
        int beginIndex = findRoutineBeginIndex(tokens, routineKeywordIndex);
        if (beginIndex < 0) {
            return null;
        }
        Deque<RoutineBlockTypeEnum> blockStack = new ArrayDeque<>();
        for (int index = beginIndex; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (!MysqlSqlCompletionTokenUtil.isDefaultToken(token)) {
                continue;
            }
            int tokenType = token.getType();
            RoutineBlockTypeEnum startType = startBlockType(tokens, index);
            if (Objects.nonNull(startType)) {
                blockStack.push(startType);
                continue;
            }
            if (tokenType == MySqlLexer.END || tokenType == MySqlLexer.END_SYMBOLE) {
                int endSuffixIndex = endSuffixBlockIndex(tokens, index);
                RoutineBlockTypeEnum endSuffixType = endSuffixIndex < 0 ? null
                        : blockType(tokens.get(endSuffixIndex).getType());
                if (Objects.nonNull(endSuffixType)) {
                    popExpectedBlock(blockStack, endSuffixType);
                    index = endSuffixIndex;
                    continue;
                }
                RoutineBlockTypeEnum closedType = blockStack.poll();
                if (closedType == RoutineBlockTypeEnum.BEGIN && blockStack.isEmpty()) {
                    int end = trimRight(sql, tokens.get(createIndex).getStartIndex(),
                            Math.min(token.getStopIndex() + 1, sql.length()));
                    int separatorEnd = tokenType == MySqlLexer.END_SYMBOLE
                            ? Math.min(token.getStopIndex() + 1, sql.length())
                            : routineSeparatorEnd(tokens, index, end, sql.length());
                    return new RoutineWindow(
                            tokens.get(createIndex).getStartIndex(),
                            end,
                            separatorEnd,
                            index);
                }
            }
        }
        return null;
    }

    private int findRoutineBeginIndex(List<Token> tokens, int routineKeywordIndex) {
        for (int index = routineKeywordIndex + 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (!MysqlSqlCompletionTokenUtil.isDefaultToken(token)) {
                continue;
            }
            int tokenType = token.getType();
            if (tokenType == MySqlLexer.BEGIN) {
                return index;
            }
            if (tokenType == MySqlLexer.SEMI || tokenType == MySqlLexer.DELIMITER_STATEMENT) {
                return -1;
            }
        }
        return -1;
    }

    private RoutineBlockTypeEnum startBlockType(List<Token> tokens, int index) {
        Token token = tokens.get(index);
        if (!MysqlSqlCompletionTokenUtil.isDefaultToken(token)) {
            return null;
        }
        return switch (token.getType()) {
            case MySqlLexer.BEGIN -> RoutineBlockTypeEnum.BEGIN;
            case MySqlLexer.CASE -> RoutineBlockTypeEnum.CASE;
            case MySqlLexer.IF -> isIfFunction(tokens, index) ? null : RoutineBlockTypeEnum.IF;
            case MySqlLexer.LOOP -> RoutineBlockTypeEnum.LOOP;
            case MySqlLexer.REPEAT -> RoutineBlockTypeEnum.REPEAT;
            case MySqlLexer.WHILE -> RoutineBlockTypeEnum.WHILE;
            default -> null;
        };
    }

    private RoutineWindow containingRoutineWindow(List<RoutineWindow> routineWindows, int cursor) {
        for (RoutineWindow routineWindow : routineWindows) {
            if (cursor >= routineWindow.start() && cursor <= routineWindow.end()) {
                return routineWindow;
            }
        }
        return null;
    }

    private boolean isRoutineSeparator(Token token, List<RoutineWindow> routineWindows) {
        for (RoutineWindow routineWindow : routineWindows) {
            if (token.getStartIndex() >= routineWindow.start() && token.getStopIndex() < routineWindow.separatorEnd()) {
                return true;
            }
        }
        return false;
    }

    private boolean isIfFunction(List<Token> tokens, int index) {
        Token next = MysqlSqlCompletionTokenUtil.nextDefaultToken(tokens, index + 1);
        return Objects.nonNull(next) && next.getType() == MySqlLexer.LR_BRACKET;
    }

    private int endSuffixBlockIndex(List<Token> tokens, int index) {
        for (int i = index + 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (!MysqlSqlCompletionTokenUtil.isDefaultToken(token)) {
                continue;
            }
            return Objects.nonNull(blockType(token.getType())) ? i : -1;
        }
        return -1;
    }

    private RoutineBlockTypeEnum blockType(int tokenType) {
        return switch (tokenType) {
            case MySqlLexer.CASE -> RoutineBlockTypeEnum.CASE;
            case MySqlLexer.IF -> RoutineBlockTypeEnum.IF;
            case MySqlLexer.LOOP -> RoutineBlockTypeEnum.LOOP;
            case MySqlLexer.REPEAT -> RoutineBlockTypeEnum.REPEAT;
            case MySqlLexer.WHILE -> RoutineBlockTypeEnum.WHILE;
            default -> null;
        };
    }

    private void popExpectedBlock(Deque<RoutineBlockTypeEnum> blockStack, RoutineBlockTypeEnum expectedType) {
        if (!blockStack.isEmpty() && blockStack.peek() == expectedType) {
            blockStack.pop();
        }
    }

    private int routineSeparatorEnd(List<Token> tokens, int endIndex, int end, int sqlLength) {
        Token separator = MysqlSqlCompletionTokenUtil.nextDefaultToken(tokens, endIndex + 1);
        if (Objects.nonNull(separator) && separator.getType() == MySqlLexer.SEMI) {
            return Math.min(separator.getStopIndex() + 1, sqlLength);
        }
        return end;
    }

    private int findWindowEnd(String sql, int windowStart, int segmentEnd, int cursor) {
        int end = Math.max(cursor, trimRight(sql, windowStart, segmentEnd));
        return Math.max(windowStart, Math.min(end, sql.length()));
    }

    private SqlCompletionStatementWindowTypeEnum resolveType(String sql, int start, int end, int cursor) {
        if (start == end || sql.substring(start, end).isBlank()) {
            return SqlCompletionStatementWindowTypeEnum.EMPTY_STATEMENT;
        }
        if (cursor > end || cursor < start) {
            return SqlCompletionStatementWindowTypeEnum.BETWEEN_STATEMENTS;
        }
        return SqlCompletionStatementWindowTypeEnum.CURRENT_STATEMENT;
    }

    private Token firstStatementStart(List<Token> tokens, int segmentStart, int cursor) {
        for (Token token : tokens) {
            if (!MysqlSqlCompletionTokenUtil.isDefaultToken(token)) {
                continue;
            }
            if (token.getStartIndex() < segmentStart) {
                continue;
            }
            if (token.getStartIndex() > cursor) {
                break;
            }
            if (isSqlStatementStartToken(token.getType())) {
                return token;
            }
        }
        return null;
    }

    private boolean hasUsableToken(Token token) {
        return Objects.nonNull(token) && token.getStartIndex() >= 0 && token.getStopIndex() >= token.getStartIndex();
    }

    private boolean isSqlStatementStartToken(int tokenType) {
        return SQL_STATEMENT_START_TOKENS.contains(tokenType);
    }

    private boolean isNonRoutineCreateObjectToken(int tokenType) {
        return NON_ROUTINE_CREATE_OBJECT_TOKENS.contains(tokenType);
    }

    private int firstNonWhitespace(String sql, int start, int end) {
        int safeStart = Math.max(0, Math.min(start, sql.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, sql.length()));
        for (int i = safeStart; i < safeEnd; i++) {
            if (!Character.isWhitespace(sql.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isBlank(String sql, int start, int end) {
        int safeStart = Math.max(0, Math.min(start, sql.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, sql.length()));
        for (int index = safeStart; index < safeEnd; index++) {
            if (!Character.isWhitespace(sql.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private int trimRight(String sql, int start, int end) {
        int safeStart = Math.max(0, Math.min(start, sql.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, sql.length()));
        while (safeEnd > safeStart && Character.isWhitespace(sql.charAt(safeEnd - 1))) {
            safeEnd--;
        }
        return safeEnd;
    }

    private record ParsedRoot(MySqlParser.RootContext root, int syntaxErrorCount) {
    }

    private record ParserWindowResult(Optional<StatementWindowProof> proof, int syntaxErrors) {
    }

    private record StatementWindowProof(StatementWindowAuthorityEnum authority, int startOffset, int endOffset) {
    }

    private record StatementProbeSql(String sql,
                                     int replaceStart,
                                     int replaceEnd,
                                     int insertedLength,
                                     int probeCursor) {

        private static StatementProbeSql build(String sql, int cursor, boolean prefixOnly) {
            String value = Objects.toString(sql, "");
            int safeCursor = Math.max(0, Math.min(cursor, value.length()));
            ProbeReplacement replacement = replacement(value, safeCursor);
            String dummy = ai.chat2db.plugin.mysql.constant.MysqlSqlCompletionTokenUtilConstants.COMPLETION_DUMMY_IDENTIFIER;
            String suffix = prefixOnly ? "" : value.substring(replacement.end());
            String patched = value.substring(0, replacement.start()) + dummy + suffix;
            int dummyEnd = replacement.start() + dummy.length();
            String repaired = closeParentheses(prefixOnly ? patched.substring(0, dummyEnd) : patched);
            if (!repaired.stripTrailing().endsWith(";")) {
                repaired += ";";
            }
            return new StatementProbeSql(repaired, replacement.start(), replacement.end(), dummy.length(),
                    replacement.start() + dummy.length());
        }

        private StatementWindowProof mapBack(StatementWindowProof proof, int originalLength) {
            int start = mapOffsetBack(proof.startOffset(), originalLength);
            int end = mapOffsetBack(proof.endOffset(), originalLength);
            if (proof.endOffset() >= replaceStart + insertedLength) {
                end = Math.max(end, replaceEnd);
            }
            return new StatementWindowProof(proof.authority(), start, Math.max(start, end));
        }

        private int mapOffsetBack(int offset, int originalLength) {
            int safeOffset = Math.max(0, offset);
            int dummyEnd = replaceStart + insertedLength;
            if (safeOffset <= replaceStart) {
                return Math.min(safeOffset, originalLength);
            }
            if (safeOffset <= dummyEnd) {
                return Math.min(replaceEnd, originalLength);
            }
            int delta = insertedLength - (replaceEnd - replaceStart);
            return Math.max(0, Math.min(safeOffset - delta, originalLength));
        }

        private static ProbeReplacement replacement(String sql, int cursor) {
            Token covering = MysqlSqlCompletionTokenUtil.tokenCoveringCursor(MysqlSqlCompletionTokenUtil.tokens(sql),
                    cursor);
            if (MysqlSqlCompletionTokenUtil.isIdentifierToken(covering)
                    && covering.getStartIndex() <= cursor
                    && cursor <= covering.getStopIndex() + 1) {
                return new ProbeReplacement(covering.getStartIndex(),
                        Math.min(covering.getStopIndex() + 1, sql.length()));
            }
            return new ProbeReplacement(cursor, cursor);
        }

        private static String closeParentheses(String sql) {
            int open = 0;
            for (Token token : MysqlSqlCompletionTokenUtil.tokens(sql)) {
                if (!MysqlSqlCompletionTokenUtil.isDefaultToken(token)) {
                    continue;
                }
                if (token.getType() == MySqlLexer.LR_BRACKET) {
                    open++;
                } else if (token.getType() == MySqlLexer.RR_BRACKET && open > 0) {
                    open--;
                }
            }
            return sql + ")".repeat(open);
        }
    }

    private record ProbeReplacement(int start, int end) {
    }

    private record RoutineWindow(int start, int end, int separatorEnd, int lastTokenIndex) {
    }

}
