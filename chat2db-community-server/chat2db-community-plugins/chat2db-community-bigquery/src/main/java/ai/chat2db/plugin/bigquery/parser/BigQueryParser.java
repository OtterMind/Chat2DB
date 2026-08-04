package ai.chat2db.plugin.bigquery.parser;

import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.service.db.ISqlBatchHandler;
import ai.chat2db.community.domain.api.service.task.ITaskProgressListener;
import ai.chat2db.plugin.postgresql.parser.PgsqlSqlParser;

import java.io.File;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * BigQuery SQL parser.
 *
 * <p>The existing PostgreSQL parser still provides the shared statement-analysis behavior. BigQuery
 * script splitting is handled here because PostgreSQL's lexer does not understand BigQuery
 * backtick identifiers or triple-quoted strings.</p>
 */
public class BigQueryParser extends PgsqlSqlParser {

    @Override
    public List<Statement> parserSqlScript(String sql) {
        List<Statement> statements = new ArrayList<>();
        try {
            splitScript(new StringReader(sql), (statementSql, bytesRead) ->
                    statements.add(createStatement(statementSql)));
            return statements;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read SQL string", e);
        }
    }

    @Override
    public int parserSqlScript(File file, ITaskProgressListener progressListener,
                               ISqlBatchHandler sqlBatchHandler) {
        int[] statementCount = {0};
        try {
            splitScript(Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8),
                    (statementSql, bytesRead) -> {
                        sqlBatchHandler.handle(createStatement(statementSql));
                        statementCount[0]++;
                        progressListener.onProgress(bytesRead, statementCount[0]);
                    });
            sqlBatchHandler.flush();
            return statementCount[0];
        } catch (IOException e) {
            throw new RuntimeException("Unable to parse SQL file " + file, e);
        }
    }

    private static void splitScript(Reader source, StatementConsumer consumer) throws IOException {
        try (PushbackReader reader = new PushbackReader(source, 3)) {
            StringBuilder statement = new StringBuilder();
            Deque<Character> closingBrackets = new ArrayDeque<>();
            LexicalState state = LexicalState.DEFAULT;
            boolean hasExecutableContent = false;
            long bytesRead = 0L;

            int value;
            while ((value = reader.read()) != -1) {
                char current = (char) value;
                statement.append(current);
                bytesRead += utf8Length(current);

                switch (state) {
                    case SINGLE_QUOTE, DOUBLE_QUOTE -> {
                        char quote = state == LexicalState.SINGLE_QUOTE ? '\'' : '"';
                        if (current == '\\') {
                            bytesRead += appendNext(reader, statement);
                        } else if (current == quote) {
                            if (nextCharactersAre(reader, quote, 1)) {
                                bytesRead += appendNext(reader, statement);
                            } else {
                                state = LexicalState.DEFAULT;
                            }
                        }
                    }
                    case TRIPLE_SINGLE_QUOTE, TRIPLE_DOUBLE_QUOTE -> {
                        char quote = state == LexicalState.TRIPLE_SINGLE_QUOTE ? '\'' : '"';
                        if (current == '\\') {
                            bytesRead += appendNext(reader, statement);
                        } else if (current == quote && nextCharactersAre(reader, quote, 2)) {
                            bytesRead += appendNext(reader, statement);
                            bytesRead += appendNext(reader, statement);
                            state = LexicalState.DEFAULT;
                        }
                    }
                    case BACKTICK -> {
                        if (current == '\\') {
                            bytesRead += appendNext(reader, statement);
                        } else if (current == '`') {
                            if (nextCharactersAre(reader, '`', 1)) {
                                bytesRead += appendNext(reader, statement);
                            } else {
                                state = LexicalState.DEFAULT;
                            }
                        }
                    }
                    case LINE_COMMENT -> {
                        if (current == '\n' || current == '\r') {
                            state = LexicalState.DEFAULT;
                        }
                    }
                    case BLOCK_COMMENT -> {
                        if (current == '*' && nextCharactersAre(reader, '/', 1)) {
                            bytesRead += appendNext(reader, statement);
                            state = LexicalState.DEFAULT;
                        }
                    }
                    case DEFAULT -> {
                        if (current == '-' && nextCharactersAre(reader, '-', 1)) {
                            bytesRead += appendNext(reader, statement);
                            state = LexicalState.LINE_COMMENT;
                        } else if (current == '#') {
                            state = LexicalState.LINE_COMMENT;
                        } else if (current == '/' && nextCharactersAre(reader, '*', 1)) {
                            bytesRead += appendNext(reader, statement);
                            state = LexicalState.BLOCK_COMMENT;
                        } else if (current == '\'' || current == '"') {
                            hasExecutableContent = true;
                            if (nextCharactersAre(reader, current, 2)) {
                                bytesRead += appendNext(reader, statement);
                                bytesRead += appendNext(reader, statement);
                                state = current == '\''
                                        ? LexicalState.TRIPLE_SINGLE_QUOTE
                                        : LexicalState.TRIPLE_DOUBLE_QUOTE;
                            } else {
                                state = current == '\''
                                        ? LexicalState.SINGLE_QUOTE
                                        : LexicalState.DOUBLE_QUOTE;
                            }
                        } else if (current == '`') {
                            hasExecutableContent = true;
                            state = LexicalState.BACKTICK;
                        } else if (isOpeningBracket(current)) {
                            hasExecutableContent = true;
                            closingBrackets.push(matchingCloseBracket(current));
                        } else if (!closingBrackets.isEmpty() && current == closingBrackets.peek()) {
                            hasExecutableContent = true;
                            closingBrackets.pop();
                        } else if (current == ';' && closingBrackets.isEmpty()) {
                            statement.setLength(statement.length() - 1);
                            if (hasExecutableContent) {
                                consumer.accept(statement.toString().strip(), bytesRead);
                            }
                            statement.setLength(0);
                            hasExecutableContent = false;
                        } else if (!Character.isWhitespace(current)) {
                            hasExecutableContent = true;
                        }
                    }
                }
            }

            if (hasExecutableContent) {
                consumer.accept(statement.toString().strip(), bytesRead);
            }
        }
    }

    private static Statement createStatement(String sql) {
        Statement statement = new Statement();
        statement.setSql(sql);
        statement.setOriginalSql(sql);
        return statement;
    }

    private static boolean nextCharactersAre(PushbackReader reader, char expected, int count)
            throws IOException {
        char[] characters = new char[count];
        int readCount = 0;
        while (readCount < count) {
            int value = reader.read();
            if (value == -1) {
                break;
            }
            characters[readCount++] = (char) value;
        }
        for (int i = readCount - 1; i >= 0; i--) {
            reader.unread(characters[i]);
        }
        if (readCount != count) {
            return false;
        }
        for (char character : characters) {
            if (character != expected) {
                return false;
            }
        }
        return true;
    }

    private static int appendNext(PushbackReader reader, StringBuilder target) throws IOException {
        int value = reader.read();
        if (value == -1) {
            return 0;
        }
        char character = (char) value;
        target.append(character);
        return utf8Length(character);
    }

    private static int utf8Length(char character) {
        if (character <= 0x7F) {
            return 1;
        }
        if (character <= 0x7FF) {
            return 2;
        }
        // A valid supplementary code point is decoded as a surrogate pair: two bytes per code unit.
        return Character.isSurrogate(character) ? 2 : 3;
    }

    private static boolean isOpeningBracket(char character) {
        return character == '(' || character == '[' || character == '{';
    }

    private static char matchingCloseBracket(char character) {
        return switch (character) {
            case '(' -> ')';
            case '[' -> ']';
            case '{' -> '}';
            default -> throw new IllegalArgumentException("Unsupported bracket: " + character);
        };
    }

    private enum LexicalState {
        DEFAULT,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        TRIPLE_SINGLE_QUOTE,
        TRIPLE_DOUBLE_QUOTE,
        BACKTICK,
        LINE_COMMENT,
        BLOCK_COMMENT
    }

    @FunctionalInterface
    private interface StatementConsumer {
        void accept(String sql, long bytesRead);
    }
}
