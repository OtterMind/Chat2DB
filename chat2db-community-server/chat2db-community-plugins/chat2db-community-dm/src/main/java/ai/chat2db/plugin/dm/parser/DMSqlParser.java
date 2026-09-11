package ai.chat2db.plugin.dm.parser;

import ai.chat2db.spi.DefaultSQLFileSplitter;
import ai.chat2db.spi.constant.SQLConstants;
import ai.chat2db.community.domain.api.enums.parser.FileSizeUnitEnum;
import ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum;
import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.model.parser.statement.StatementContext;
import ai.chat2db.plugin.dm.parser.base.DMLexer;
import ai.chat2db.plugin.dm.parser.base.DMParser;
import ai.chat2db.plugin.dm.parser.base.DMParserBaseVisitor;
import ai.chat2db.plugin.dm.parser.error.strategy.DMErrorStrategy;
import ai.chat2db.plugin.dm.parser.visitor.DMCreateTableVisitor;
import ai.chat2db.plugin.dm.parser.visitor.DMParserVisitor;
import ai.chat2db.plugin.dm.parser.visitor.DMSimpleParserVisitor;
import ai.chat2db.plugin.dm.parser.visitor.DMValidTableVisitor;
import ai.chat2db.spi.parser.AbstractSqlParser;
import ai.chat2db.community.domain.api.service.task.ITaskProgressListener;
import ai.chat2db.spi.IRuleManager;
import ai.chat2db.community.domain.api.service.db.ISqlBatchHandler;
import ai.chat2db.spi.util.TokenUtil;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class DMSqlParser extends AbstractSqlParser<DMParser, DMDialect> {


    public DMSqlParser() {
        super(new DMParser(null), new DMDialect());
    }

    @Override
    protected Lexer createLexer(CharStream charStream) {
        return new DMLexer(charStream);
    }


    @Override
    protected ParseTree parserRoot(DMParser parser) {
        return parser.sql_script();
    }

    @Override
    protected StatementContext createStatementContext(CommonTokenStream tokenStream, SqlTypeEnum sqlTypeEnum) {
        StatementContext statementContext = new StatementContext(tokenStream);
        statementContext.setRecoverSet(DMErrorStrategy.recoverSet);
        DMParserBaseVisitor visitor = createVisitor(statementContext, sqlTypeEnum);
        statementContext.setVisitor(visitor);
        return statementContext;
    }


    private DMParserBaseVisitor createVisitor(StatementContext statementContext, SqlTypeEnum sqlTypeEnum) {
        return switch (sqlTypeEnum) {
            case SIMPLE -> new DMSimpleParserVisitor(statementContext);
            case CREATE_TABLE -> new DMCreateTableVisitor(statementContext);
            case VALID_TABLE -> new DMValidTableVisitor(statementContext);
            default -> new DMParserVisitor(statementContext);
        };
    }

    /**
     * Classifies an executable statement for the DM command executor.
     * Ordinary SQL only pays for a cheap lexer scan of the first keyword;
     * a full ANTLR parse runs only when that keyword is {@code EXPLAIN}.
     * Parse failures for EXPLAIN never silently fall back to JDBC.
     */
    public DMExecutableSql parseExecutableSql(String sql) {
        if (StringUtils.isBlank(sql)) {
            return new DMExecutableSql(SqlTypeEnum.OTHER.name(), sql, sql);
        }
        DMLexer lexer = new DMLexer(CharStreams.fromString(sql));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        if (!isExplainLeadingKeyword(tokens)) {
            return new DMExecutableSql(SqlTypeEnum.OTHER.name(), sql, sql);
        }

        DMParser parser = new DMParser(tokens);
        parser.removeErrorListeners();
        parser.setErrorHandler(new BailErrorStrategy());
        try {
            DMExecutableSql parsed = new DMExecutionVisitor(tokens, sql).visit(parser.sql_script());
            if (parsed == null || !parsed.isExplain()) {
                throw new IllegalArgumentException("Failed to parse DM EXPLAIN statement");
            }
            return parsed;
        } catch (ParseCancellationException e) {
            throw new IllegalArgumentException("Failed to parse DM EXPLAIN statement", e);
        }
    }

    private boolean isExplainLeadingKeyword(CommonTokenStream tokens) {
        for (Token token : tokens.getTokens()) {
            if (token.getType() == Token.EOF) {
                break;
            }
            if (!TokenUtil.hasValuableText(token) || dialect.isComment(token.getType())) {
                continue;
            }
            return token.getType() == DMLexer.EXPLAIN;
        }
        return false;
    }

    @Override
    public List<Statement> parserSqlScript(String sql) {
        Lexer lexer = getLexer(sql);
        CommonTokenStream tokenStream = createTokenStream(lexer);
        tokenStream.fill();
        List<Token> tokens = tokenStream.getTokens();

        List<Statement> statements = new ArrayList<>();
        IRuleManager ruleManager = dialect.getRuleManager();
        Token firstToken = null;
        Token lastToken = null;
        Token firstKeyword = null;
        Token lastKeyword = null;
        boolean encounteredBlockHeaderPrefix = false;
        SqlScriptBlockContext curBlock = null;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            int tokenType = token.getType();
            if (tokenType == Token.EOF) {
                break;
            }
            String currentTokenText = token.getText().trim();
            if (!TokenUtil.hasValuableText(token)) {
                continue;
            } else if (dialect.isComment(tokenType)) {
                continue;
            } else if (tokenType == DMLexer.SOLIDUS && Objects.isNull(firstToken)) {
                continue;
            }
            if (dialect.isStatementDelimiter(currentTokenText) && Objects.isNull(curBlock)) {
                if (Objects.isNull(firstToken)) {
                    continue;
                }
                Statement statement = getStatement(tokenStream, firstToken, lastToken);
                String originalSql = statement.getOriginalSql();
                if (StringUtils.isNotBlank(originalSql)) {
                    String trimSql = originalSql.trim();
                    if (trimSql.endsWith("/")) {
                        String substring = trimSql.substring(0, trimSql.length() - 1);
                        if (Objects.nonNull(lastKeyword)) {
                            if ("END".equalsIgnoreCase(lastKeyword.getText())) {
                                substring += ";";
                            }
                        }
                        statement.setSql(substring);
                    }
                }
                statements.add(statement);
                firstToken = null;
                lastToken = null;
                firstKeyword = null;
                lastKeyword = null;
                curBlock = null;
                encounteredBlockHeaderPrefix = false;
                continue;
            }
            if (Objects.isNull(firstToken)) {
                firstToken = token;
            }
            if (currentTokenText.length() == 1) {
                if (StringUtils.equalsAnyIgnoreCase(currentTokenText,
                        SQLConstants.OPEN_PARENTHESIS,
                        SQLConstants.OPEN_CURLY_BRACE,
                        SQLConstants.OPEN_SQUARE_BRACKET)) {
                    curBlock = new SqlScriptBlockContext(curBlock);
                }
                if (StringUtils.equalsAnyIgnoreCase(currentTokenText,
                        SQLConstants.CLOSE_PARENTHESIS,
                        SQLConstants.CLOSE_CURLY_BRACE,
                        SQLConstants.CLOSE_SQUARE_BRACKET)) {
                    if (Objects.nonNull(curBlock)) {
                        curBlock = curBlock.parent;
                    }
                }
            }
            if (dialect.isBlockHeaderPrefix(currentTokenText)) {
                encounteredBlockHeaderPrefix = true;
                lastToken = token;
                firstKeyword = token;
                lastKeyword = token;
                continue;
            }

            if (encounteredBlockHeaderPrefix) {
                if (dialect.isDependentBlockHeader(currentTokenText)) {
                    boolean isBlockHeader = true;
                    if (Objects.nonNull(ruleManager)) {
                        isBlockHeader = ruleManager.matchRules(tokens, i);
                    }
                    if (isBlockHeader) {
                        curBlock = new SqlScriptBlockContext(curBlock, true);
                        lastToken = token;
                        lastKeyword = token;
                        encounteredBlockHeaderPrefix = false;
                    }
                    continue;
                }
            } else {
                if (dialect.isIndependentBlockHeader(currentTokenText) && Objects.isNull(curBlock)) {
                    curBlock = new SqlScriptBlockContext(null, true);
                    if (Objects.isNull(firstKeyword)) {
                        firstKeyword = token;
                    }
                    lastToken = token;
                    lastKeyword = token;
                    continue;
                }
                if (Objects.nonNull(curBlock)
                        && dialect.isPackageBodyInnerBlockHeader(currentTokenText)
                        && Objects.nonNull(ruleManager)
                        && ruleManager.matchRules(tokens, i)) {
                    curBlock = new SqlScriptBlockContext(curBlock, true);
                    lastToken = token;
                    lastKeyword = token;
                    continue;

                }
            }

            if (dialect.isBlockBegin(currentTokenText)) {
                if (Objects.nonNull(lastToken) && dialect.isBlockEnd(lastToken.getText())) {
                    lastToken = token;
                    continue;
                } else {
                    boolean isBlock = true;
                    if (Objects.nonNull(ruleManager)) {
                        isBlock = ruleManager.matchRules(tokens, i);
                    }
                    if (isBlock) {
                        if (Objects.nonNull(curBlock) && curBlock.isHeader) {
                            curBlock = curBlock.parent;
                        }
                        curBlock = new SqlScriptBlockContext(curBlock);
                    }
                }
            } else if (dialect.isBlockEnd(currentTokenText)) {
                if (Objects.nonNull(curBlock)) {
                    curBlock = curBlock.parent;
                }
            }


            lastToken = token;
            if (dialect.isKeyword(currentTokenText)) {
                if (Objects.isNull(firstKeyword)) {
                    firstKeyword = token;
                }
                lastKeyword = token;
            }

        }

        return statements;
    }

    @Override
    public int parserSqlScript(File file, ITaskProgressListener progressListener, ISqlBatchHandler sqlBatchHandler) {
        try (DefaultSQLFileSplitter safeSQLFileSplitter = new DefaultSQLFileSplitter(10, FileSizeUnitEnum.MB, file, StandardCharsets.UTF_8)) {
            String content;
            Lexer lexer = new DMLexer(null);
            CharStream charStream = null;
            long bytesRead = 0L;
            int statementCount = 0;
            List<Token> currentTokens = new ArrayList<>(50);
            IRuleManager ruleManager = dialect.getRuleManager();
            while (StringUtils.isNotBlank(content = safeSQLFileSplitter.nextContent())) {
                if (CollectionUtils.isNotEmpty(currentTokens)) {
                    String leftTokens = currentTokens.stream().map(Token::getText).collect(Collectors.joining());
                    charStream = CharStreams.fromString(leftTokens + content);
                    currentTokens.clear();
                } else {
                    charStream = CharStreams.fromString(content);
                }
                lexer.setInputStream(charStream);
                UnbufferedTokenStream<Token> tokenStream = createUnbufferedTokenStream(lexer);
                boolean encounteredBlockHeaderPrefix = false;
                boolean encounteredOpenParenthesis = false;
                Token firstToken = null;
                Token lastToken = null;
                Token firstKeyword = null;
                Token lastKeyword = null;
                SqlScriptBlockContext curBlock = null;
                while (tokenStream.LA(1) != Token.EOF) {
                    Token token = tokenStream.LT(1);
                    int tokenType = token.getType();
                    String text = token.getText();
                    bytesRead += text.getBytes().length;
                    String currentTokenText = text.trim();
                    if (!TokenUtil.hasValuableText(token) || dialect.isComment(tokenType)) {
                        if (Objects.nonNull(firstToken)) {
                            currentTokens.add(token);
                        }
                        tokenStream.consume();
                        continue;
                    } else if (tokenType == DMLexer.SOLIDUS && Objects.isNull(firstToken)) {
                        tokenStream.consume();
                        continue;
                    }
                    if (dialect.isStatementDelimiter(currentTokenText) && Objects.isNull(curBlock)) {
                        if (Objects.isNull(firstToken)) {
                            tokenStream.consume();
                            continue;
                        }
                        Statement statement = getStatement(currentTokens);
                        String originalSql = statement.getOriginalSql();
                        if (StringUtils.isNotBlank(originalSql)) {
                            String trimSql = originalSql.trim();
                            if (trimSql.endsWith("/")) {
                                String substring = trimSql.substring(0, trimSql.length() - 1);
                                if (Objects.nonNull(lastKeyword)) {
                                    if ("END".equalsIgnoreCase(lastKeyword.getText())) {
                                        substring += ";";
                                    }
                                }
                                statement.setSql(substring);
                            }
                        }
                        sqlBatchHandler.handle(statement);
                        statementCount++;
                        firstToken = null;
                        lastToken = null;
                        firstKeyword = null;
                        lastKeyword = null;
                        curBlock = null;
                        encounteredBlockHeaderPrefix = false;
                        currentTokens.clear();
                        tokenStream.consume();
                        progressListener.onProgress(bytesRead, statementCount);
                        continue;
                    }
                    if (Objects.isNull(firstToken)) {
                        firstToken = token;
                    }
                    currentTokens.add(token);
                    if (currentTokenText.length() == 1) {
                        if (SQLConstants.OPEN_PARENTHESIS.equalsIgnoreCase(currentTokenText)) {
                            encounteredOpenParenthesis = true;
                            curBlock = new SqlScriptBlockContext(curBlock);

                        } else if (StringUtils.equalsAnyIgnoreCase(currentTokenText,
                                SQLConstants.OPEN_CURLY_BRACE,
                                SQLConstants.OPEN_SQUARE_BRACKET)) {
                            curBlock = new SqlScriptBlockContext(curBlock);
                        } else if (SQLConstants.CLOSE_PARENTHESIS.equalsIgnoreCase(currentTokenText)) {
                            encounteredOpenParenthesis = false;
                            if (Objects.nonNull(curBlock)) {
                                curBlock = curBlock.parent;
                            }
                        }
                        else if (StringUtils.equalsAnyIgnoreCase(currentTokenText,
                                SQLConstants.CLOSE_CURLY_BRACE,
                                SQLConstants.CLOSE_SQUARE_BRACKET)) {
                            if (Objects.nonNull(curBlock)) {
                                curBlock = curBlock.parent;
                            }
                        }
                    }
                    if (dialect.isBlockHeaderPrefix(currentTokenText)) {
                        encounteredBlockHeaderPrefix = true;
                        lastToken = token;
                        firstKeyword = token;
                        lastKeyword = token;
                        tokenStream.consume();
                        continue;
                    }

                    if (encounteredBlockHeaderPrefix) {
                        if (dialect.isDependentBlockHeader(currentTokenText)) {
                            boolean isBlockHeader = true;
                            if (Objects.nonNull(ruleManager)) {
                                isBlockHeader = ruleManager.matchRules(tokenStream);
                            }
                            if (isBlockHeader) {
                                curBlock = new SqlScriptBlockContext(curBlock, true);
                                lastToken = token;
                                lastKeyword = token;
                                encounteredBlockHeaderPrefix = false;
                            }
                            tokenStream.consume();
                            continue;
                        }
                    } else {
                        if (dialect.isIndependentBlockHeader(currentTokenText) && Objects.isNull(curBlock)) {
                            curBlock = new SqlScriptBlockContext(null, true);
                            if (Objects.isNull(firstKeyword)) {
                                firstKeyword = token;
                            }
                            lastToken = token;
                            lastKeyword = token;
                            tokenStream.consume();
                            continue;
                        }
                        if (Objects.nonNull(curBlock)
                                && dialect.isPackageBodyInnerBlockHeader(currentTokenText)
                                && Objects.nonNull(ruleManager)
                                && ruleManager.matchRules(tokenStream)) {
                            if (encounteredOpenParenthesis && DMLexer.PROCEDURE == tokenType) {
                                if (checkLastValuableToken(currentTokens, DMLexer.LEFT_PAREN)) {
                                    lastToken = token;
                                    lastKeyword = token;
                                    tokenStream.consume();
                                    continue;
                                }
                            } else {
                                curBlock = new SqlScriptBlockContext(curBlock, true);
                                lastToken = token;
                                lastKeyword = token;
                                tokenStream.consume();
                                continue;
                            }

                        }
                    }

                    if (dialect.isBlockBegin(currentTokenText)) {
                        if (Objects.nonNull(lastToken) && dialect.isBlockEnd(lastToken.getText())) {
                            lastToken = token;
                            tokenStream.consume();
                            continue;
                        } else {
                            boolean isBlock = true;
                            if (Objects.nonNull(ruleManager)) {
                                isBlock = ruleManager.matchRules(tokenStream);
                            }
                            if (isBlock) {
                                if (Objects.nonNull(curBlock) && curBlock.isHeader) {
                                    curBlock = curBlock.parent;
                                }
                                curBlock = new SqlScriptBlockContext(curBlock);
                            }
                        }
                    } else if (dialect.isBlockEnd(currentTokenText)) {
                        if (Objects.nonNull(curBlock)) {
                            curBlock = curBlock.parent;
                        }
                    }


                    lastToken = token;
                    if (dialect.isKeyword(currentTokenText)) {
                        if (Objects.isNull(firstKeyword)) {
                            firstKeyword = token;
                        }
                        lastKeyword = token;
                    }
                    tokenStream.consume();
                }
            }
            if (CollectionUtils.isNotEmpty(currentTokens)) {
                Statement statement = getStatement(currentTokens);
                sqlBatchHandler.handle(statement);
                statementCount++;
                currentTokens.clear();
                progressListener.onProgress(bytesRead, statementCount);
            }
            sqlBatchHandler.flush();
            return statementCount;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean checkLastValuableToken(List<Token> currentTokens, Integer targetTokenType) {
        for (int i = currentTokens.size() - 1; i > 0; i--) {
            Token token = currentTokens.get(i);
            if (TokenUtil.hasValuableText(token)) {
                return token.getType() == targetTokenType;
            }
        }
        return false;
    }
}
