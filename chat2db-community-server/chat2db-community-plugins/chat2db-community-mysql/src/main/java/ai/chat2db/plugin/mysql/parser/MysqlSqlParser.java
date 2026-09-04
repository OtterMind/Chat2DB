package ai.chat2db.plugin.mysql.parser;

import ai.chat2db.spi.DefaultSQLFileSplitter;
import ai.chat2db.spi.constant.SQLConstants;
import ai.chat2db.community.domain.api.enums.parser.FileSizeUnitEnum;
import ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum;
import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.model.parser.statement.StatementContext;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.mysql.parser.base.MySqlLexer;
import ai.chat2db.mysql.parser.base.MySqlParser;
import ai.chat2db.mysql.parser.base.MySqlParserBaseVisitor;
import ai.chat2db.plugin.mysql.parser.error.strategy.MysqlErrorStrategy;
import ai.chat2db.plugin.mysql.parser.visitor.MysqlCreateTableVisitor;
import ai.chat2db.plugin.mysql.parser.visitor.MysqlParserVisitor;
import ai.chat2db.plugin.mysql.parser.visitor.MysqlSimpleParserVisitor;
import ai.chat2db.plugin.mysql.parser.visitor.MysqlValidTableVisitor;
import ai.chat2db.spi.parser.AbstractSqlParser;
import ai.chat2db.community.domain.api.service.task.ITaskProgressListener;
import ai.chat2db.spi.IRuleManager;
import ai.chat2db.community.domain.api.service.db.ISqlBatchHandler;
import ai.chat2db.spi.util.TokenUtil;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.mysql.constant.MysqlSqlParserConstants.*;
public class MysqlSqlParser extends AbstractSqlParser<MySqlParser, MysqlDialect> {


    public MysqlSqlParser() {
        super(new MySqlParser(null), new MysqlDialect());
    }

    @Override
    protected Lexer createLexer(CharStream charStream) {
        return new MySqlLexer(charStream);
    }


    @Override
    protected ParseTree parserRoot(MySqlParser parser) {
        return parser.root();
    }


    @Override
    protected StatementContext createStatementContext(CommonTokenStream tokenStream, SqlTypeEnum sqlTypeEnum) {
        StatementContext statementContext = new StatementContext(tokenStream);
        statementContext.setRecoverSet(MysqlErrorStrategy.recoverSet);
        MySqlParserBaseVisitor visitor = createVisitor(statementContext, sqlTypeEnum);
        statementContext.setVisitor(visitor);
        return statementContext;
    }


    private MySqlParserBaseVisitor createVisitor(StatementContext statementContext, SqlTypeEnum sqlTypeEnum) {
        return switch (sqlTypeEnum) {
            case SIMPLE -> new MysqlSimpleParserVisitor(statementContext);
            case CREATE_TABLE -> new MysqlCreateTableVisitor(statementContext);
            case VALID_TABLE -> new MysqlValidTableVisitor(statementContext);
            default -> new MysqlParserVisitor(statementContext);
        };
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
        String udfDelimiter = null;
        boolean encounteredBlockHeaderPrefix = false;
        SqlScriptBlockContext curBlock = null;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            int tokenType = token.getType();
            boolean combinedUdfDelimiter = isCombinedUdfDelimiter(token, udfDelimiter);
            if (tokenType == Token.EOF) {
                break;
            }
            String currentTokenText = token.getText().trim();
            if (!TokenUtil.hasValuableText(token)) {
                continue;
            } else if (dialect.isComment(tokenType)) {
                continue;
            } else if (tokenType == MySqlLexer.DELIMITER_STATEMENT) {
                String delimiterSymbol = extractDelimiterSymbol(currentTokenText);
                udfDelimiter = delimiterSymbol == null ? null : delimiterSymbol.trim();
                continue;
            }
            if ((dialect.isStatementDelimiter(currentTokenText)
                    || currentTokenText.trim().equalsIgnoreCase(udfDelimiter))
                    && Objects.isNull(curBlock)) {
                if (Objects.isNull(firstToken)) {
                    continue;
                }
                Statement statement = getStatement(tokenStream, firstToken, lastToken);
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
            }
            if (dialect.isBlockBegin(currentTokenText)) {
                if (Objects.nonNull(lastToken) && dialect.isBlockEnd(lastToken.getText().trim())) {
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
            if (combinedUdfDelimiter && Objects.isNull(curBlock)) {
                statements.add(getStatement(tokenStream, firstToken, lastToken));
                firstToken = null;
                lastToken = null;
                firstKeyword = null;
                lastKeyword = null;
                encounteredBlockHeaderPrefix = false;
            }

        }

        return statements;
    }


    @Override
    public int parserSqlScript(File file,
                               ITaskProgressListener progressListener,
                               ISqlBatchHandler sqlBatchHandler) {

        return parserSqlScript(file, progressListener, sqlBatchHandler, StandardCharsets.UTF_8);
    }

    public int parserSqlScript(File file,
                               ITaskProgressListener progressListener,
                               ISqlBatchHandler sqlBatchHandler,
                               Charset charset) {

        try (DefaultSQLFileSplitter safeSQLFileSplitter = new DefaultSQLFileSplitter(10, FileSizeUnitEnum.MB, file, charset)) {
            String content;
            long bytesRead = 0L;
            int statementCount = 0;
            CharStream charStream = null;
            Lexer lexer = new MySqlLexer(null);
            IRuleManager ruleManager = dialect.getRuleManager();
            List<Token> currentTokens = new ArrayList<>(50);
            while (StringUtils.isNotBlank(content = safeSQLFileSplitter.nextContent())) {
                bytesRead += content.getBytes(charset).length;
                if (CollectionUtils.isNotEmpty(currentTokens)) {
                    charStream = CharStreams.fromString(currentTokens.stream().map(Token::getText).collect(Collectors.joining()) + content);
                    currentTokens.clear();
                } else {
                    charStream = CharStreams.fromString(content);
                }
                lexer.setInputStream(charStream);
                UnbufferedTokenStream<Token> tokenStream = createUnbufferedTokenStream(lexer);
                Token firstToken = null;
                Token lastToken = null;
                Token firstKeyword = null;
                Token lastKeyword = null;
                String udfDelimiter = null;
                boolean encounteredBlockHeaderPrefix = false;
                SqlScriptBlockContext curBlock = null;
                while (tokenStream.LA(1) != Token.EOF) {
                    Token token = tokenStream.LT(1);
                    int tokenType = token.getType();
                    boolean combinedUdfDelimiter = isCombinedUdfDelimiter(token, udfDelimiter);
                    String text = token.getText();
                    String currentTokenText = text.trim();
                    if (!TokenUtil.hasValuableText(token) || dialect.isComment(tokenType)) {
                        if (Objects.nonNull(firstToken)) {
                            currentTokens.add(token);
                        }
                        tokenStream.consume();
                        continue;
                    } else if (tokenType == MySqlLexer.DELIMITER_STATEMENT) {
                        String delimiterSymbol = extractDelimiterSymbol(currentTokenText);
                        udfDelimiter = delimiterSymbol == null ? null : delimiterSymbol.trim();
                        tokenStream.consume();
                        continue;
                    }
                    if ((dialect.isStatementDelimiter(currentTokenText)
                            || currentTokenText.trim().equalsIgnoreCase(udfDelimiter))
                            && Objects.isNull(curBlock)) {
                        if (Objects.isNull(firstToken)) {
                            tokenStream.consume();
                            continue;
                        }
                        Statement statement = getStatement(currentTokens);
                        sqlBatchHandler.handle(statement);
                        firstToken = null;
                        lastToken = null;
                        firstKeyword = null;
                        lastKeyword = null;
                        curBlock = null;
                        encounteredBlockHeaderPrefix = false;
                        statementCount++;
                        currentTokens.clear();
                        if (statementCount % 1000 == 0) {
                            progressListener.onProgress(bytesRead, statementCount);
                        }
                        tokenStream.consume();
                        continue;
                    }
                    if (Objects.isNull(firstToken)) {
                        firstToken = token;
                    }
                    currentTokens.add(token);
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
                    }
                    if (dialect.isBlockBegin(currentTokenText)) {
                        if (Objects.nonNull(lastToken) && dialect.isBlockEnd(lastToken.getText().trim())) {
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

                    if (combinedUdfDelimiter && Objects.isNull(curBlock)) {
                        Statement statement = getStatement(currentTokens);
                        sqlBatchHandler.handle(statement);
                        firstToken = null;
                        lastToken = null;
                        firstKeyword = null;
                        lastKeyword = null;
                        encounteredBlockHeaderPrefix = false;
                        statementCount++;
                        currentTokens.clear();
                        if (statementCount % 1000 == 0) {
                            progressListener.onProgress(bytesRead, statementCount);
                        }
                        tokenStream.consume();
                        continue;
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
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error parsing SQL statement: " + e.getMessage(), e);
        }
    }


    private static String extractDelimiterSymbol(String text) {
        Matcher matcher = DELIMITER_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static boolean isCombinedUdfDelimiter(Token token, String udfDelimiter) {
        if (token.getType() != MySqlLexer.END_SYMBOLE || StringUtils.isBlank(udfDelimiter)
                || token.getInputStream() == null) {
            return false;
        }
        String originalText = token.getInputStream()
                .getText(Interval.of(token.getStartIndex(), token.getStopIndex()))
                .trim();
        return originalText.endsWith(udfDelimiter);
    }
}
