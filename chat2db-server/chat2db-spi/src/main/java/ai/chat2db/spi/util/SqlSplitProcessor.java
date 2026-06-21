/*
 * Copyright (c) 2023 OceanBase.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.chat2db.spi.util;

import com.alibaba.druid.DbType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 */
public class SqlSplitProcessor {

    private static final String DELIMITER_NAME = "delimiter";
    /**
     * 是否保留格式
     */
    private boolean preserveFormat = false;
    private String delimiter = ";";
    @Getter
    private boolean mlComment = false;
    private char inString = '\0';
    private DbType dialectType;
    private char escapeString = '\0';
    private boolean inNormalSql = false;
    /**
     * 是否保留单行注释
     */
    @Getter
    private boolean preserveSingleComments = false;
    /**
     * 是否保留多行注释
     */
    @Getter
    private boolean preserveMultiComments = false;

    private static Pattern pattern = Pattern.compile("\\r\\n|\\r|\\n");


    public SqlSplitProcessor(boolean preserveFormat, String delimiter) {
        this.delimiter = delimiter;
        this.preserveFormat = preserveFormat;
    }

    public SqlSplitProcessor(DbType dialectType,
                             boolean preserveSingleComments,
                             boolean preserveMultiComments) {
        this.preserveFormat = true;
        this.dialectType = dialectType;
        this.preserveSingleComments = preserveSingleComments;
        this.preserveMultiComments = preserveMultiComments;
    }

    public SqlSplitProcessor(DbType dialectType,
                             boolean preserveFormat,
                             boolean preserveSingleComments,
                             boolean preserveMultiComments) {
        this.preserveFormat = preserveFormat;
        this.dialectType = dialectType;
        this.preserveSingleComments = preserveSingleComments;
        this.preserveMultiComments = preserveMultiComments;
    }

    public SqlSplitProcessor(DbType dialectType, String delimiter) {
        this.preserveFormat = true;
        this.dialectType = dialectType;
        this.delimiter = delimiter;
    }

    public SqlSplitProcessor() {}

    public static SqlStatementIterator iterator(InputStream in, Charset charset, SqlSplitProcessor processor) {
        return new SqlCommentProcessorIterator(in, charset, processor);
    }

    public static List<SplitSqlString> removeSqlComments(String originalSql,
                                                         String delimiter, DbType dbMode, boolean preserveFormat) {
        SqlSplitProcessor sqlCommentProcessor = new SqlSplitProcessor(preserveFormat, delimiter);
        StringBuffer buffer = new StringBuffer();
        List<SplitSqlString> offsetStrings = new ArrayList<>();
        List<List<OrderChar>> lines = splitLine(originalSql);
        Holder<Integer> bufferOrder = new Holder<>(0);
        for (List<OrderChar> item : lines) {
            if (Objects.nonNull(dbMode) && DbType.mysql.equals(dbMode)) {
                sqlCommentProcessor.addLineMysql(offsetStrings, buffer, bufferOrder, item);
            } else {
                sqlCommentProcessor.addLineOracle(offsetStrings, buffer, bufferOrder, item);
            }
        }

        String bufferStr = buffer.toString();
        if (bufferStr.trim().length() != 0) {
            while (true) {
                if (bufferStr.endsWith("\n")) {
                    /**
                     * remove all <code>\n</code> from sqls
                     */
                    bufferStr = bufferStr.substring(0, bufferStr.length() - 1);
                } else {
                    break;
                }
            }
            if (offsetStrings.size() == 0) {
                offsetStrings.add(new SplitSqlString(0, bufferStr));
            } else {
                offsetStrings.add(new SplitSqlString(
                        offsetStrings.get(offsetStrings.size() - 1).getOffset()
                                + offsetStrings.get(offsetStrings.size() - 1).getStr().length(),
                        bufferStr));
            }
        }
        return offsetStrings;
    }

    public synchronized List<SplitSqlString> split(StringBuffer buffer, String sqlScript) {
        if (StringUtils.isBlank(sqlScript)) {
            return new ArrayList<>();
        }
        try {
            List<SplitSqlString> offsetStrings = new ArrayList<>();

            List<List<OrderChar>> lines = splitLine(sqlScript);
            Holder<Integer> bufferOrder = new Holder<>(0);
            int i = 0;
            for (List<OrderChar> item : lines) {
                if (Objects.nonNull(this.dialectType) && DbType.mysql.equals(this.dialectType)) {
                    addLineMysql(offsetStrings, buffer, bufferOrder, item);
                } else if (Objects.nonNull(this.dialectType) && DbType.mariadb.equals(this.dialectType)) {
                    addLineMysql(offsetStrings, buffer, bufferOrder, item);
                } else if (Objects.nonNull(this.dialectType) && DbType.oracle.equals(this.dialectType)) {
                    addLineOracle(offsetStrings, buffer, bufferOrder, item);
                } else if (Objects.nonNull(this.dialectType) && DbType.oceanbase.equals(this.dialectType)) {
                    addLineMysql(offsetStrings, buffer, bufferOrder, item);
                } else {
                    throw new IllegalArgumentException("dialect type is illegal");
                }
                i++;
            }
            return offsetStrings;
        } finally {
            mlComment = false;
            inString = '\0';
            inNormalSql = false;
        }
    }

    private synchronized void addLineMysql(List<SplitSqlString> sqls, StringBuffer buffer, Holder<Integer> bufferOrder,
                                           List<OrderChar> line) {
        int pos, out;
        boolean needSpace = false;
        // 标识量，用于标识当前是否处于HINT，CONDITIONAL中
        SSC ssComment = SSC.NONE;
        boolean isSameLine = false;
        int lineLength = line.size();
        OrderChar[] lines = line.toArray(new OrderChar[lineLength + 1]);
        if ((lines.length == 0 || lines[0] == null || lines[0].getCh() == 0) && buffer.length() == 0) {
            return;
        }
        lines[lineLength] = new OrderChar((char) 0, lineLength);
        for (pos = out = 0; pos < lineLength; pos++) {
            OrderChar inOrderChar = lines[pos];
            char inChar = inOrderChar.getCh();
            // 去掉每一行SQL语句最开始的空格
            if (inChar == ' ' && out == 0 && buffer.length() == 0 && !preserveFormat) {
                continue;
            }
            int delimiterBegin = 0;
            if (preserveFormat) {
                for (; delimiterBegin < out
                        && (lines[delimiterBegin].getCh() == ' '
                                || lines[delimiterBegin].getCh() == '\t'); delimiterBegin++) {
                }
            }
            if (equalsIgnoreCase((DELIMITER_NAME + " ").toCharArray(), lines, delimiterBegin, (out - delimiterBegin))) {
                // 检测到"delimiter "字符串，且不在多行注释以及多行字符串中，说明有设定分隔符的语句
                StringBuilder newDelimiter = new StringBuilder();
                for (; pos < lineLength; pos++) {
                    char tempChar = lines[pos].getCh();
                    if (tempChar != ' ') {
                        newDelimiter.append(tempChar);
                    } else if (newDelimiter.length() != 0) {
                        break;
                    }
                }
                out = 0;
                this.delimiter = newDelimiter.toString();
                continue;
            }
            // 扫描到转义字符，可能出现指令
            if ((!mlComment && inChar == '\\')) {
                inOrderChar = lines[++pos];
                inChar = inOrderChar.getCh();
                if (inChar == 0) {
                    break;
                }
                if (inString != '\0' || inChar == 'N') {
                    lines[out++] = OrderChar.newOrderChar(lines[pos - 1]);
                    if (inChar == '`' && inString == inChar) {
                        pos--;
                    } else {
                        lines[out++] = OrderChar.newOrderChar(lines[pos]);
                    }
                    continue;
                }
                // 非mysql model或没有检索到正确的命令，直接将转义符号及转义字符放入缓冲
                lines[out++] = OrderChar.newOrderChar(lines[pos - 1]);
                lines[out++] = OrderChar.newOrderChar(lines[pos]);
            } else if (!mlComment && inString == '\0' && ssComment != SSC.HINT
                    && isPrefix(lines, pos, delimiter)) {
                // 不是多行注释，未在字符串中，不是hint且以delimiter开头，通常是扫描到了sql的末尾
                pos += delimiter.length();
                if (out != 0) {
                    if (buffer.length() == 0) {
                        bufferOrder.setValue(lines[0].getOrder());
                    }
                    append(buffer, lines, 0, out);
                    out = 0;
                }
                // buffer.append(";").append('\n');
                sqls.add(new SplitSqlString(bufferOrder.getValue(), buffer.toString()));
                bufferOrder.setValue(bufferOrder.getValue() + buffer.length());
                pos--;
                buffer.setLength(0);
                isSameLine = true;
                inNormalSql = false;
            } else if (!mlComment
                    && (inString == '\0' && (inChar == '#' || (inChar == '-' && lines[pos + 1].getCh() == '-'
                            && ((lines[pos + 2].getCh() == ' ' || lines[pos + 2].getCh() == '\0')))))) {
                // 处于单行注释中
                if (buffer.length() == 0) {
                    bufferOrder.setValue(lines[0].getOrder());
                }
                append(buffer, lines, 0, out);
                out = 0;
                if (preserveSingleComments) {
                    // 如果保留单行注释则需要将注释完整地拷贝到缓冲中不能丢弃
                    for (; pos < lineLength; pos++) {
                        lines[out++] = OrderChar.newOrderChar(lines[pos]);
                    }
                    if (isOnlyWhiteSpace(buffer)) {
                        // 缓冲中全部是空格，或者缓冲为空说明注释要么处于第一行要么处于个已经完结的sql语句之后
                        if (sqls.size() != 0) {
                            if (buffer.length() == 0) {
                                bufferOrder.setValue(lines[0].getOrder());
                            }
                            // 说明注释处于一个已经完结的sql之后，且该sql已经被加入到sql集合中，此处的注释需要追加到最后一句sql中
                            append(buffer, lines, 0, out);
                            int lastIndex = sqls.size() - 1;
                            String lastSql = sqls.get(lastIndex).getStr();
                            if (!isSameLine) {
                                lastSql += '\n';
                            }
                            lastSql += buffer + "\n";
                            sqls.set(lastIndex, new SplitSqlString(sqls.get(lastIndex).getOffset(), lastSql));
                            buffer.setLength(0);
                        } else {
                            lines[out++].setCh('\n');
                            if (buffer.length() == 0) {
                                bufferOrder.setValue(lines[0].getOrder());
                            }
                            append(buffer, lines, 0, out - 1);
                        }
                    } else {
                        lines[out++].setCh('\n');
                        if (buffer.length() == 0) {
                            bufferOrder.setValue(lines[0].getOrder());
                        }
                        append(buffer, lines, 0, out - 1);
                    }
                    out = 0;
                }
                break;
            } else if (inString == '\0' && (inChar == '/' && lines[pos + 1].getCh() == '*')
            // 此处注意，Oracle模式下没有Conditional，故这里要做规避。Mysql模式下的Conditional在Oracle模式在要识别为注释去掉
                    && lines[pos + 2].getCh() != '!'
                    && lines[pos + 2].getCh() != '+' && ssComment != SSC.HINT) {
                // 处于多行注释中，注意规避了HINT和CONDITIONAL，Oracle模式下没有conditional
                if (preserveMultiComments) {
                    lines[out++].setCh('/');
                    lines[out++].setCh('*');
                }
                pos++;
                mlComment = true;
            } else if (mlComment && ssComment == SSC.NONE && inChar == '*' && lines[pos + 1].getCh() == '/') {
                // 多行注释结束
                pos++;
                mlComment = false;
                if (buffer.length() == 0) {
                    bufferOrder.setValue(lines[0].getOrder());
                }
                append(buffer, lines, 0, out);
                out = 0;
                if (preserveMultiComments) {
                    lines[out++].setCh('*');
                    lines[out++].setCh('/');
                    if (buffer.length() == 0) {
                        bufferOrder.setValue(lines[0].getOrder());
                    }
                    append(buffer, lines, 0, out);
                    out = 0;
                    if (sqls.size() != 0 && !inNormalSql) {
                        int lastIndex = sqls.size() - 1;
                        String lastSql = sqls.get(lastIndex).getStr() + buffer;
                        sqls.set(lastIndex, new SplitSqlString(sqls.get(lastIndex).getOffset(), lastSql));
                        buffer.setLength(0);
                    }
                }
                needSpace = true;
            } else {
                if (inString == '\0' && inChar == '/' && lines[pos + 1].getCh() == '*') {
                    if (lines[pos + 2].getCh() == '!') {
                        // 处于CONDITIONAL中
                        ssComment = SSC.CONDITIONAL;
                    } else if (lines[pos + 2].getCh() == '+') {
                        // 处于HINT中
                        ssComment = SSC.HINT;
                    }
                } else if (inString == '\0' && ssComment != SSC.NONE && inChar == '*'
                        && lines[pos + 1].getCh() == '/') {
                    // HINT或CONDITIONAL结束
                    ssComment = SSC.NONE;
                }
                if (inChar == inString) {
                    // 字符指针出字符串或表达式
                    inString = '\0';
                } else if (!mlComment && inString == '\0' && ssComment != SSC.HINT
                        && (inChar == '\'' || inChar == '"' || inChar == '`')) {
                    // 字符指针进入字符串或者表达式
                    inString = inChar;
                }
                if (!mlComment) {
                    if (needSpace && inChar == ' ') {
                        lines[out++].setCh(' ');
                    }
                    needSpace = false;
                    // 正常的SQL语句，将其放入line缓冲当中，在合适的实际flush如buffer缓存
                    lines[out++] = OrderChar.newOrderChar(inOrderChar);
                    if (inChar != ' ') {
                        inNormalSql = true;
                    }
                } else if (preserveMultiComments) {
                    // 保留多行注释
                    lines[out++] = OrderChar.newOrderChar(inOrderChar);
                }
            }
        }
        // 拦截性的处理，如果out指针没有为0，说明lines中还有内容没有被刷入到buffer，在这里进行flush
        if (out != 0 || buffer.length() != 0) {
            lines[out++].setCh('\n');
            if (buffer.length() == 0) {
                bufferOrder.setValue(lines[0].getOrder());
            }
            append(buffer, lines, 0, out);
        }
    }

    private boolean isOnlyWhiteSpace(StringBuffer buffer) {
        if (buffer == null) {
            return false;
        }
        int length = buffer.length();
        for (int i = 0; i < length; i++) {
            if (buffer.charAt(i) != ' ') {
                return false;
            }
        }
        return true;
    }

    public synchronized void addLineOracle(List<SplitSqlString> sqls, StringBuffer buffer, Holder<Integer> bufferOrder,
                                           List<OrderChar> line) {
        int pos, out;
        boolean needSpace = false;
        // 标识量，用于标识当前是否处于HINT，CONDITIONAL中
        SSC ssComment = SSC.NONE;

        boolean isSameLine = false;
        int lineLength = line.size();
        OrderChar[] lines = line.toArray(new OrderChar[lineLength + 1]);
        if ((lines.length == 0 || lines[0] == null || lines[0].getCh() == 0) && buffer.length() == 0) {
            return;
        }
        lines[lineLength] = new OrderChar((char) 0, lineLength);
        for (pos = out = 0; pos < lineLength; pos++) {
            OrderChar inOrderChar = lines[pos];
            char inChar = inOrderChar.getCh();
            // 去掉每一行SQL语句最开始的空格
            if (inChar == ' ' && out == 0 && buffer.length() == 0 && !preserveFormat) {
                continue;
            }
            int delimiterBegin = 0;
            if (preserveFormat) {
                for (; delimiterBegin < out
                        && (lines[delimiterBegin].getCh() == ' '
                                || lines[delimiterBegin].getCh() == '\t'); delimiterBegin++) {
                }
            }
            if (equalsIgnoreCase((DELIMITER_NAME + " ").toCharArray(), lines, delimiterBegin, (out - delimiterBegin))) {
                // 检测到"delimiter "字符串，且不在多行注释以及多行字符串中，说明有设定分隔符的语句
                StringBuilder newDelimiter = new StringBuilder();
                for (; pos < lineLength; pos++) {
                    char tempChar = lines[pos].getCh();
                    if (tempChar != ' ') {
                        newDelimiter.append(tempChar);
                    } else if (newDelimiter.length() != 0) {
                        break;
                    }
                }
                out = 0;
                this.delimiter = newDelimiter.toString();
                continue;
            }
            if (!mlComment && inString == '\0' && ssComment != SSC.HINT && isPrefix(lines, pos, delimiter)) {
                // 不是多行注释，未在字符串中，不是hint且以delimiter开头，通常是扫描到了sql的末尾
                pos += delimiter.length();
                if (out != 0) {
                    if (buffer.length() == 0) {
                        bufferOrder.setValue(lines[0].getOrder());
                    }
                    append(buffer, lines, 0, out);
                    out = 0;
                }
                // buffer.append(";").append('\n');
                sqls.add(new SplitSqlString(bufferOrder.getValue(), buffer.toString()));
                bufferOrder.setValue(bufferOrder.getValue() + buffer.length());
                pos--;
                buffer.setLength(0);
                isSameLine = true;
                inNormalSql = false;
            } else if (!mlComment && (inString == '\0' && (inChar == '-' && lines[pos + 1].getCh() == '-'
                    && (lines[pos + 2].getCh() != '+' || (lines[pos + 2].getCh() == ' '
                            || lines[pos + 2].getCh() == '\0'))))) {
                // 处于单行注释中，注意规避单行HINT
                if (buffer.length() == 0) {
                    bufferOrder.setValue(lines[0].getOrder());
                }
                append(buffer, lines, 0, out);
                out = 0;
                if (preserveSingleComments) {
                    // 如果保留单行注释则需要将注释完整地拷贝到缓冲中不能丢弃
                    for (; pos < lineLength; pos++) {
                        lines[out++] = OrderChar.newOrderChar(lines[pos]);
                    }
                    if (isOnlyWhiteSpace(buffer)) {
                        // 缓冲中全部是空格，或者缓冲为空说明注释要么处于第一行要么处于个已经完结的sql语句之后
                        if (sqls.size() != 0) {
                            if (buffer.length() == 0) {
                                bufferOrder.setValue(lines[0].getOrder());
                            }
                            // 说明注释处于一个已经完结的sql之后，且该sql已经被加入到sql集合中，此处的注释需要追加到最后一句sql中
                            append(buffer, lines, 0, out);
                            int lastIndex = sqls.size() - 1;
                            String lastSql = sqls.get(lastIndex).getStr();
                            if (!isSameLine) {
                                lastSql += '\n';
                            }
                            lastSql += buffer + "\n";
                            sqls.set(lastIndex, new SplitSqlString(sqls.get(lastIndex).getOffset(), lastSql));
                            buffer.setLength(0);
                        } else {
                            lines[out++].setCh('\n');
                            if (buffer.length() == 0) {
                                bufferOrder.setValue(lines[0].getOrder());
                            }
                            append(buffer, lines, 0, out - 1);
                        }
                    } else {
                        lines[out++].setCh('\n');
                        if (buffer.length() == 0) {
                            bufferOrder.setValue(lines[0].getOrder());
                        }
                        append(buffer, lines, 0, out - 1);
                    }
                    out = 0;
                }
                break;
            } else if (inString == '\0' && (inChar == '/' && lines[pos + 1].getCh() == '*')
                    && lines[pos + 2].getCh() != '+'
                    && ssComment != SSC.HINT) {
                // 处于多行注释中，注意规避了HINT和CONDITIONAL，Oracle模式下没有conditional
                if (preserveMultiComments) {
                    lines[out++].setCh('/');
                    lines[out++].setCh('*');
                }
                pos++;
                mlComment = true;
            } else if (mlComment && ssComment == SSC.NONE && inChar == '*' && lines[pos + 1].getCh() == '/') {
                // 多行注释结束
                pos++;
                mlComment = false;
                if (buffer.length() == 0) {
                    bufferOrder.setValue(lines[0].getOrder());
                }
                append(buffer, lines, 0, out);
                out = 0;
                if (preserveMultiComments) {
                    lines[out++].setCh('*');
                    lines[out++].setCh('/');
                    if (buffer.length() == 0) {
                        bufferOrder.setValue(lines[0].getOrder());
                    }
                    append(buffer, lines, 0, out);
                    out = 0;
                    if (sqls.size() != 0 && !inNormalSql) {
                        int lastIndex = sqls.size() - 1;
                        String lastSql = sqls.get(lastIndex).getStr() + buffer;
                        sqls.set(lastIndex, new SplitSqlString(sqls.get(lastIndex).getOffset(), lastSql));
                        buffer.setLength(0);
                    }
                }
                needSpace = true;
            } else {
                if (inString == '\0' && inChar == '/' && lines[pos + 1].getCh() == '*') {
                    if (lines[pos + 2].getCh() == '+') {
                        // 处于HINT中
                        ssComment = SSC.HINT;
                    }
                } else if (inString == '\0' && ssComment != SSC.NONE && inChar == '*'
                        && lines[pos + 1].getCh() == '/') {
                    // HINT或CONDITIONAL结束
                    ssComment = SSC.NONE;
                } else if (inString == '\0' && inChar == '-' && lines[pos + 1].getCh() == '-'
                        && lines[pos + 2].getCh() == '+') {
                    // 在Oracle模式下Hint有单行Hint和多行Hint之分，这里处理Oracle模式下的单行Hint
                    ssComment = SSC.HINT;
                }
                if (inChar == inString) {
                    // 字符指针出字符串或表达式
                    if (escapeString == '\0') {
                        inString = '\0';
                    } else if (pos >= 1 && matchQEscape(lines[pos - 1].getCh())) {
                        inString = '\0';
                        escapeString = '\0';
                    }
                } else if (!mlComment && inString == '\0' && ssComment != SSC.HINT
                        && (inChar == '\'' || inChar == '"' || inChar == '`')) {
                    // 字符指针进入字符串或者表达式
                    inString = inChar;
                    if (pos >= 1 && (lines[pos - 1].getCh() == 'q' || lines[pos - 1].getCh() == 'Q')) {
                        // oracle 特有语法，Q 转义
                        escapeString = lines[pos + 1].getCh();
                    }
                }
                if (!mlComment) {
                    if (needSpace && inChar == ' ') {
                        lines[out++].setCh(' ');
                    }
                    needSpace = false;
                    // 正常的SQL语句，将其放入line缓冲当中，在合适的实际flush如buffer缓存
                    lines[out++] = new OrderChar(inOrderChar.getCh(), inOrderChar.getOrder());
                    if (inChar != ' ') {
                        inNormalSql = true;
                    }
                } else if (preserveMultiComments) {
                    // 保留多行注释
                    lines[out++] = new OrderChar(inOrderChar.getCh(), inOrderChar.getOrder());
                }
            }
        }
        // 拦截性的处理，如果out指针没有为0，说明lines中还有内容没有被刷入到buffer，在这里进行flush
        if (out != 0 || buffer.length() != 0) {
            lines[out++].setCh('\n');
            if (buffer.length() == 0) {
                bufferOrder.setValue(lines[0].getOrder());
            }
            append(buffer, lines, 0, out);
        }
    }

    private boolean equalsIgnoreCase(char[] src, OrderChar[] dest, int begin, int count) {
        if (src == null && dest == null) {
            return true;
        } else if (src != null && dest != null) {
            if (src.length != count) {
                return false;
            }
            for (int i = 0; i < count; i++) {
                char c1 = src[i];
                char c2 = dest[begin + i].getCh();
                if (c1 == c2) {
                    continue;
                }
                char u1 = Character.toUpperCase(c1);
                char u2 = Character.toUpperCase(c2);
                if (u1 == u2) {
                    continue;
                }
                if (Character.toLowerCase(u1) == Character.toLowerCase(u2)) {
                    continue;
                }
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * 当前SQL是否是以分隔符开头
     */
    private boolean isPrefix(OrderChar[] line, int pos, String delim) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < line.length - pos; i++) {
            builder.append(line[pos + i].getCh());
        }
        boolean res = builder.toString().startsWith(delim);
        if (!res || !"/".equals(delim) || line.length <= 1) {
            return res;
        }
        // 匹配到分隔符，分隔符为正斜杠且当前行的大小大于 1，需要注意规避多行注释
        if (pos == 0) {
            return !(line[pos + 1].getCh() == '*');
        } else if (line.length - 1 == pos) {
            return !(line[pos - 1].getCh() == '*');
        }
        return !(line[pos + 1].getCh() == '*' || line[pos - 1].getCh() == '*');
    }

    private boolean matchQEscape(char escapeChar) {
        if (this.escapeString == '\0') {
            return false;
        }
        switch (this.escapeString) {
            case '<':
                return escapeChar == '>';
            case '{':
                return escapeChar == '}';
            case '[':
                return escapeChar == ']';
            case '(':
                return escapeChar == ')';
            default:
                return this.escapeString == escapeChar;
        }
    }

    private void append(StringBuffer buffer, OrderChar[] chars, int begin, int count) {
        for (int i = begin; i < count; i++) {
            buffer.append(chars[i].getCh());
        }
    }

    private static List<List<OrderChar>> splitLine(String sqlScript) {
        List<List<OrderChar>> lines = new ArrayList<>();
        List<OrderChar> currentList = new ArrayList<>();
        Matcher matcher = pattern.matcher(sqlScript);
        int start = 0;
        while (matcher.find()) {
            int end = matcher.start();
            for (int i = start; i < end; i++) {
                OrderChar orderChar = new OrderChar(sqlScript.charAt(i), i);
                currentList.add(orderChar);
            }
            lines.add(currentList);
            currentList = new ArrayList<>();
            start = matcher.end();
        }
        if (start < sqlScript.length()) {
            for (int i = start; i < sqlScript.length(); i++) {
                OrderChar orderChar = new OrderChar(sqlScript.charAt(i), i);
                currentList.add(orderChar);
            }
        }
        if (!currentList.isEmpty()) {
            lines.add(currentList);
        }
        return lines;
    }

    public String getDelimiter() {
        return delimiter;
    }

    public void setDelimiter(String delimiter) {
        this.delimiter = delimiter;
    }

    private enum SSC {
        /**
         * 不处于HINT或CONDITIONAL中
         */
        NONE(0),
        /**
         * 当前SQL字符指针处于CONDITIONAL中
         */
        CONDITIONAL(1),
        /**
         * 当前处于HINT中
         */
        HINT(2);

        private final int value;

        SSC(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    private static class SqlCommentProcessorIterator implements SqlStatementIterator {

        private final BufferedReader reader;
        private final StringBuffer buffer = new StringBuffer();
        private final LinkedList<SplitSqlString> holder = new LinkedList<>();
        private final Holder<Integer> bufferOrder = new Holder<>(0);
        private final SqlSplitProcessor processor;

        private SplitSqlString current;
        private int lastLineOrder = 0;
        private long iteratedBytes = 0;

        public SqlCommentProcessorIterator(InputStream input, Charset charset, SqlSplitProcessor processor) {
            this.reader = new BufferedReader(new InputStreamReader(input, charset));
            this.processor = processor;
        }

        @Override
        public boolean hasNext() {
            if (current == null) {
                current = parseNext();
            }
            return current != null;
        }

        @Override
        public SplitSqlString next() {
            SplitSqlString next = current;
            current = null;
            if (next == null) {
                next = parseNext();
                if (next == null) {
                    throw new NoSuchElementException("No more available sql.");
                }
            }
            return next;
        }

        @Override
        public long iteratedBytes() {
            return iteratedBytes;
        }

        private SplitSqlString parseNext() {
            try {
                if (!holder.isEmpty()) {
                    return holder.poll();
                }
                String line;
                while (holder.isEmpty() && (line = reader.readLine()) != null) {
                    if ( DbType.mysql.equals(processor.dialectType)) {
                        processor.addLineMysql(holder, buffer, bufferOrder, line.chars()
                                .mapToObj(c -> new OrderChar((char) c, lastLineOrder++))
                                .collect(Collectors.toList()));
                    } else if (DbType.oracle.equals(processor.dialectType)) {
                        processor.addLineOracle(holder, buffer, bufferOrder, line.chars()
                                .mapToObj(c -> new OrderChar((char) c, lastLineOrder++))
                                .collect(Collectors.toList()));
                    } else if (DbType.oceanbase.equals(processor.dialectType)) {
                        processor.addLineMysql(holder, buffer, bufferOrder, line.chars()
                                .mapToObj(c -> new OrderChar((char) c, lastLineOrder++))
                                .collect(Collectors.toList()));
                    }
                    // consider \n in the end of each line
                    lastLineOrder++;
                    iteratedBytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
                }
                if (!holder.isEmpty()) {
                    return holder.poll();
                }
                if (buffer.toString().trim().isEmpty()) {
                    return null;
                }
                String sql = buffer.toString();
                buffer.setLength(0);
                return new SplitSqlString(0, sql);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse input. reason: " + e.getMessage(), e);
            }
        }

    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class OrderChar {
        private char ch;
        private int order;

        static OrderChar newOrderChar(OrderChar orderChar) {
            return new OrderChar(orderChar.getCh(), orderChar.getOrder());
        }
    }

}
