package ai.chat2db.spi;


import ai.chat2db.community.domain.api.enums.parser.FileSizeUnitEnum;
import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class DefaultSQLFileSplitter implements ISQLFileSplitter {
    private static final Set<Character> STRING_LITERAL_SYMBOL;
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
    private static final Set<Character> SAFE_SPLITTER_SYMBOL;
    private static final Map<Character, Character> BRACKET_PAIRS;
    private static final Set<Character> SINGLE_LINE_COMMENT_PREFIX;
    private static final Set<Character> BLOCK_COMMENT_PREFIX;
    private static final Set<Character> STRING_PREFIX;
    private final long maxSize;
    private final PushbackReader reader;
    private final Charset charset;
    private final StringBuilder builder = new StringBuilder(5_242_880);
    private int safeSplitterPosition = -1;
    private long currentSize = 0;


    public DefaultSQLFileSplitter(File file) {
        this(10, FileSizeUnitEnum.MB, file, DEFAULT_CHARSET);
    }

    public DefaultSQLFileSplitter(long size, FileSizeUnitEnum unit, File file, Charset charSet) {
        this.maxSize = size * unit.toBytes();
        FileReader fileReader;
        try {
            fileReader = new FileReader(file);
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Unable to open file " + file, e);
        }
        // FileReader would use the platform default charset; honor the requested one.
        this.reader = new PushbackReader(new BufferedReader(new InputStreamReader(
                new FileInputStream(file), charSet)), 256);
        this.charset = charSet;
    }

    static {
        STRING_LITERAL_SYMBOL = new HashSet<>();
        STRING_LITERAL_SYMBOL.add('"');
        STRING_LITERAL_SYMBOL.add('\'');
        SAFE_SPLITTER_SYMBOL = new HashSet<>();
        SAFE_SPLITTER_SYMBOL.add(' ');
        SAFE_SPLITTER_SYMBOL.add('\r');
        SAFE_SPLITTER_SYMBOL.add('\n');
        BRACKET_PAIRS = new HashMap<>();
        BRACKET_PAIRS.put('(', ')');
        SINGLE_LINE_COMMENT_PREFIX = new HashSet<>();
        SINGLE_LINE_COMMENT_PREFIX.add('-');
        BLOCK_COMMENT_PREFIX = new HashSet<>();
        BLOCK_COMMENT_PREFIX.add('/');
        STRING_PREFIX = new HashSet<>();
        STRING_PREFIX.add('b');
        STRING_PREFIX.add('x');
        STRING_PREFIX.add('B');
        STRING_PREFIX.add('X');
    }

    @Override
    public String nextContent() {
        String content;
        try {
            while (currentSize < maxSize) {
                int ch = reader.read();
                if (ch == -1) {
                    content = builder.toString();
                    builder.setLength(0);
                    resetCurrentSize();
                    resetSafeSplitterPosition();
                    return content;
                }
                char currentChar = (char) ch;
                if (isSafeSplitterSymbol(currentChar)) {
                    int charSizeInBytes = String.valueOf(currentChar).getBytes(charset).length;
                    currentSize += charSizeInBytes;
                    builder.append(currentChar);
                    safeSplitterPosition = builder.length();
                } else if (isStringLiteralSymbol(currentChar)) {
                    String stringLiteral = processStringLiteral(currentChar);
                    currentSize += stringLiteral.getBytes(charset).length;
                    builder.append(stringLiteral);
                } else if (isBracketPairSymbol(currentChar)) {
                    String bracketLiteral = processBracketLiteral(currentChar, BRACKET_PAIRS.get(currentChar));
                    currentSize += bracketLiteral.getBytes(charset).length;
                    builder.append(bracketLiteral);
                } else if (isSingleLineCommentPrefix(currentChar) || isBlockCommentPrefix(currentChar)) {
                    reader.unread(ch);
                    String comment = processComment();
                    if (StringUtils.isNotBlank(comment)) {
                        currentSize += comment.getBytes(charset).length;
                        builder.append(comment);
                    } else {
                        ch = reader.read();
                        currentChar = (char) ch;
                        currentSize += String.valueOf(currentChar).getBytes(charset).length;
                        builder.append(currentChar);
                    }
                } else {
                    currentSize += String.valueOf(currentChar).getBytes(charset).length;
                    builder.append(currentChar);
                }
            }
            if (safeSplitterPosition > 0) {
                content = builder.substring(0, safeSplitterPosition);
                builder.delete(0, safeSplitterPosition);
                resetSafeSplitterPosition();
            } else {
                content = builder.toString();
                builder.setLength(0);
            }
            resetCurrentSize();
            return content;
        } catch (IOException e) {
            throw new RuntimeException("Unable to read content", e);
        }
    }


    private void resetSafeSplitterPosition() {
        this.safeSplitterPosition = -1;
    }

    private void resetCurrentSize() {
        this.currentSize = 0;
    }

    private boolean isStringLiteralSymbol(Character symbol) {
        return STRING_LITERAL_SYMBOL.contains(symbol);
    }

    public void addStringLiteralSymbol(Character symbol) {
        STRING_LITERAL_SYMBOL.add(symbol);
    }

    private boolean isSafeSplitterSymbol(Character symbol) {
        return SAFE_SPLITTER_SYMBOL.contains(symbol);
    }

    public void addSafeSplitterSymbol(Character symbol) {
        SAFE_SPLITTER_SYMBOL.add(symbol);
    }

    public boolean isBracketPairSymbol(Character symbol) {
        return BRACKET_PAIRS.containsKey(symbol);
    }

    public void addBracketPairSymbol(Character openSymbol, Character closeSymbol) {
        BRACKET_PAIRS.put(openSymbol, closeSymbol);
    }

    private boolean isSingleLineCommentPrefix(Character symbol) {
        return SINGLE_LINE_COMMENT_PREFIX.contains(symbol);
    }

    public void addSingleLineCommentPrefix(Character symbol) {
        SINGLE_LINE_COMMENT_PREFIX.add(symbol);
    }

    private boolean isBlockCommentPrefix(Character symbol) {
        return BLOCK_COMMENT_PREFIX.contains(symbol);
    }

    public void addBlockCommentPrefix(Character symbol) {
        BLOCK_COMMENT_PREFIX.add(symbol);
    }

    private boolean isStringPrefix(char c) {
        return STRING_PREFIX.contains(Character.toLowerCase(c));
    }

    public void addStringPrefix(char prefix) {
        STRING_PREFIX.add(prefix);
    }


    private boolean shouldProcessAsString(char currentChar) throws IOException {
        if (isStringLiteralSymbol(currentChar)) {
            return true;
        }
        if (isStringPrefix(currentChar)) {
            int next = reader.read();
            if (next != -1) {
                reader.unread(next);
                return isStringLiteralSymbol((char) next);
            }
        }
        return false;
    }

    private String processStringLiteral(char quoteChar) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(quoteChar);
        boolean escape = false;

        while (true) {
            int ch = reader.read();
            if (ch == -1) break;

            char currentChar = (char) ch;

            if (escape) {
                sb.append(currentChar);
                escape = false;
            } else if (currentChar == '\\') {
                escape = true;
                sb.append(currentChar);
            } else if (currentChar == quoteChar) {
                sb.append(currentChar);
                int next = reader.read();
                if (next == quoteChar) {
                    sb.append((char) next);
                } else {
                    if (next != -1) reader.unread(next);
                    break;
                }
            } else {
                sb.append(currentChar);
            }
        }
        return sb.toString();
    }


    private String processBracketLiteral(char openBracket, char closeBracket) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(openBracket);
        int bracketCount = 1;

        while (bracketCount > 0) {
            int ch = reader.read();
            char currenChar = (char) ch;
            if (isStringLiteralSymbol(currenChar)) {
                String stringLiteral = processStringLiteral(currenChar);
                sb.append(stringLiteral);
                currentSize += stringLiteral.getBytes(charset).length;
                continue;
            }
            if (ch == -1) break;

            if (ch == openBracket) {
                bracketCount++;
                sb.append(currenChar);
            } else if (ch == closeBracket) {
                bracketCount--;
                sb.append(currenChar);
            } else {
                sb.append(currenChar);
            }
        }

        return sb.toString();
    }

    private String processComment() throws IOException {
        StringBuilder sb = new StringBuilder();
        int first = reader.read();
        int second = reader.read();

        if (first == '-' && second == '-') {
            sb.append("--");

            while (true) {
                int ch = reader.read();
                if (ch == -1 || ch == '\n') {
                    break;
                }
                sb.append((char) ch);
            }
            sb.append("\n");
            return sb.toString();
        } else if (first == '/' && second == '*') {
            sb.append("/*");

            int prev = 0;
            while (true) {
                int ch = reader.read();
                if (ch == -1) {
                    break;
                }
                sb.append((char) ch);

                if (prev == '*' && ch == '/') {
                    break;
                }
                prev = ch;

            }
            return sb.toString();
        } else {
            safeUnread(second);
            safeUnread(first);
            return null;
        }
    }


    private void safeUnread(int ch) throws IOException {
        if (ch != -1) {
            reader.unread(ch);
        }
    }

    @Override
    public void close() throws Exception {
        reader.close();
    }


}
