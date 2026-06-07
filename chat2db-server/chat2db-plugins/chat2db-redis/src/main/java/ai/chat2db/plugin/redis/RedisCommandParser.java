package ai.chat2db.plugin.redis;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public final class RedisCommandParser {

    private RedisCommandParser() {
    }

    public static List<String> splitStatements(String script) {
        return splitStatementPositions(script).stream()
                .map(StatementPosition::statement)
                .toList();
    }

    public static List<StatementPosition> splitStatementPositions(String script) {
        List<StatementPosition> positions = new ArrayList<>();
        if (StringUtils.isBlank(script)) {
            return positions;
        }
        StringBuilder current = new StringBuilder();
        Character quote = null;
        boolean escaped = false;
        int statementStartIndex = 0;
        for (int i = 0; i < script.length(); i++) {
            char ch = script.charAt(i);
            if (quote != null) {
                current.append(ch);
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == quote) {
                    quote = null;
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                current.append(ch);
                continue;
            }
            if (ch == ';' || ch == '\n' || ch == '\r') {
                addStatementPosition(positions, script, current, statementStartIndex, i);
                current.setLength(0);
                statementStartIndex = i + 1;
                continue;
            }
            current.append(ch);
        }
        addStatementPosition(positions, script, current, statementStartIndex, script.length());
        return positions;
    }

    public static List<String> tokenize(String statement) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Character quote = null;
        boolean escaped = false;
        for (int i = 0; i < statement.length(); i++) {
            char ch = statement.charAt(i);
            if (quote != null) {
                if (escaped) {
                    current.append(ch);
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == quote) {
                    quote = null;
                } else {
                    current.append(ch);
                }
                continue;
            }
            if (Character.isWhitespace(ch)) {
                addToken(tokens, current);
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                continue;
            }
            current.append(ch);
        }
        if (quote != null) {
            throw new IllegalArgumentException("Unclosed quoted string");
        }
        addToken(tokens, current);
        return tokens;
    }

    private static void addStatementPosition(List<StatementPosition> positions, String script, StringBuilder current,
                                             int startIndex, int endExclusive) {
        String statement = current.toString().trim();
        if (StringUtils.isBlank(statement)) {
            return;
        }
        int realStart = startIndex;
        int realEndExclusive = Math.max(startIndex, endExclusive);
        while (realStart < realEndExclusive && Character.isWhitespace(script.charAt(realStart))) {
            realStart++;
        }
        while (realEndExclusive > realStart && Character.isWhitespace(script.charAt(realEndExclusive - 1))) {
            realEndExclusive--;
        }
        positions.add(new StatementPosition(
                statement,
                getLineNumber(script, realStart),
                getLineNumber(script, realEndExclusive - 1)
        ));
    }

    private static int getLineNumber(String script, int indexInclusive) {
        int line = 1;
        int max = Math.min(Math.max(indexInclusive, 0), script.length());
        for (int i = 0; i < max; i++) {
            char ch = script.charAt(i);
            if (ch == '\n') {
                line++;
            } else if (ch == '\r') {
                line++;
                if (i + 1 < max && script.charAt(i + 1) == '\n') {
                    i++;
                }
            }
        }
        return line;
    }

    private static void addToken(List<String> tokens, StringBuilder current) {
        if (current.length() > 0) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    public record StatementPosition(String statement, int startLine, int endLine) {
    }
}
