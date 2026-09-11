package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.tools.exception.BusinessException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.PushbackInputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Strict CSV parser shared by the import preview and execution paths. */
public final class CsvParser {

    static final String DEFAULT_ENCODING = CsvOptions.DEFAULT_ENCODING;
    private static final List<String> AUTO_ENCODINGS = List.of(
            CsvOptions.DEFAULT_ENCODING, "GB18030", "windows-1252", "ISO-8859-1");
    private final CsvOptions options;
    private final char delimiter;
    private final char quote;
    private final char escape;
    private final boolean hasHeader;
    private final boolean emptyAsNull;

    CsvParser(String encoding, String delimiter, String quote, String escape, boolean hasHeader, boolean emptyAsNull) {
        this(CsvOptions.builder()
                .encoding(encoding)
                .delimiter(delimiter)
                .quote(quote)
                .escape(escape)
                .hasHeader(hasHeader)
                .emptyAsNull(emptyAsNull)
                .build());
    }

    public CsvParser(CsvOptions options) {
        this.options = (options == null ? CsvOptions.defaults() : options).validate();
        String delimiter = this.options.getDelimiter();
        String quote = this.options.getQuote();
        String escape = this.options.getEscape();
        this.delimiter = delimiter.charAt(0);
        this.quote = quote.charAt(0);
        this.escape = escape.charAt(0);
        this.hasHeader = Boolean.TRUE.equals(this.options.getHasHeader());
        this.emptyAsNull = Boolean.TRUE.equals(this.options.getEmptyAsNull());
    }

    CsvResult parse(byte[] bytes, int limit) {
        return parse(new OneByteInputStream(bytes), limit);
    }

    public CsvResult parse(Path path, int limit) {
        if (!CsvOptions.AUTO_ENCODING.equals(options.getEncoding()) || hasBom(path)) {
            return parseOnce(path, limit);
        }
        return parseWithAutoEncoding(path, limit);
    }

    /** Validates the complete file, then streams rows without retaining them in memory. */
    public void forEachRow(Path path, RowConsumer rowConsumer, Runnable cancellationChecker) {
        Objects.requireNonNull(rowConsumer, "rowConsumer");
        Runnable checker = cancellationChecker == null ? () -> { } : cancellationChecker;
        CsvParser validatedParser = validatedParser(path, checker);
        validatedParser.parseOnce(path, Integer.MAX_VALUE, rowConsumer, checker);
    }

    private CsvParser validatedParser(Path path, Runnable cancellationChecker) {
        if (!CsvOptions.AUTO_ENCODING.equals(options.getEncoding()) || hasBom(path)) {
            parseOnce(path, Integer.MAX_VALUE, ignored -> { }, cancellationChecker);
            return this;
        }
        for (String encoding : AUTO_ENCODINGS) {
            CsvParser candidate = withEncoding(encoding);
            try {
                candidate.parseOnce(path, Integer.MAX_VALUE, ignored -> { }, cancellationChecker);
                return candidate;
            } catch (BusinessException e) {
                if (!"import.preview.invalidEncodingLine".equals(e.getCode())) {
                    throw e;
                }
            }
        }
        throw new BusinessException("import.preview.invalidEncoding", new Object[]{CsvOptions.AUTO_ENCODING});
    }

    private CsvResult parseWithAutoEncoding(Path path, int limit) {
        for (String encoding : AUTO_ENCODINGS) {
            try {
                return withEncoding(encoding).parseOnce(path, limit);
            } catch (BusinessException e) {
                if (!"import.preview.invalidEncodingLine".equals(e.getCode())) {
                    throw e;
                }
            }
        }
        throw new BusinessException("import.preview.invalidEncoding", new Object[]{CsvOptions.AUTO_ENCODING});
    }

    private CsvResult parseOnce(Path path, int limit) {
        return parseOnce(path, limit, null, () -> { });
    }

    private CsvResult parseOnce(Path path, int limit, RowConsumer rowConsumer, Runnable cancellationChecker) {
        try (InputStream inputStream = new BufferedInputStream(Files.newInputStream(path))) {
            return parse(inputStream, limit, rowConsumer, cancellationChecker);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException("import.preview.fileUnreadable", new Object[]{e.getMessage()}, e);
        }
    }

    private CsvParser withEncoding(String encoding) {
        return new CsvParser(CsvOptions.builder()
                .encoding(encoding)
                .delimiter(options.getDelimiter())
                .quote(options.getQuote())
                .escape(options.getEscape())
                .newline(options.getNewline())
                .hasHeader(options.getHasHeader())
                .emptyAsNull(options.getEmptyAsNull())
                .headerRow(options.getHeaderRow())
                .dataStartRow(options.getDataStartRow())
                .dataEndRow(options.getDataEndRow())
                .dateOrder(options.getDateOrder())
                .dateTimeOrder(options.getDateTimeOrder())
                .dateDelimiter(options.getDateDelimiter())
                .yearDelimiter(options.getYearDelimiter())
                .timeDelimiter(options.getTimeDelimiter())
                .decimalSymbol(options.getDecimalSymbol())
                .build());
    }

    private static boolean hasBom(Path path) {
        try (InputStream inputStream = Files.newInputStream(path)) {
            int first = inputStream.read();
            int second = inputStream.read();
            int third = inputStream.read();
            return first == 0xEF && second == 0xBB && third == 0xBF
                    || first == 0xFF && second == 0xFE
                    || first == 0xFE && second == 0xFF;
        } catch (IOException e) {
            throw new BusinessException("import.preview.fileUnreadable", new Object[]{e.getMessage()}, e);
        }
    }

    public CsvResult parse(InputStream inputStream, int limit) {
        return parse(inputStream, limit, null, () -> { });
    }

    private CsvResult parse(InputStream inputStream, int limit, RowConsumer rowConsumer, Runnable cancellationChecker) {
        try {
            BomAwareInput bomAwareInput = detectBom(new LineBoundedInputStream(inputStream));
            CharsetDecoder decoder = bomAwareInput.charset().newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return parse(new InputStreamReader(bomAwareInput.inputStream(), decoder), limit, rowConsumer,
                    cancellationChecker);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("import.preview.invalidEncoding", new Object[]{options.getEncoding()}, e);
        }
    }

    CsvResult parse(Reader reader, int limit) {
        return parse(reader, limit, null, () -> { });
    }

    private CsvResult parse(Reader reader, int limit, RowConsumer rowConsumer, Runnable cancellationChecker) {
        try {
            return parseRows(reader, limit, rowConsumer, cancellationChecker);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("import.preview.invalidEncoding", new Object[]{options.getEncoding()}, e);
        }
    }

    private CsvResult parseRows(Reader reader, int limit, RowConsumer rowConsumer, Runnable cancellationChecker) {
        List<Map<Integer, CsvCell>> cellRows = new ArrayList<>();
        List<CsvCell> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        PushbackReader pushbackReader = new PushbackReader(reader, 1);
        boolean inQuotedField = false;
        boolean justClosedQuote = false;
        boolean atStartOfField = true;
        boolean quotedField = false;
        int line = 1;
        int quoteStartLine = 1;
        int rowCount = 0;
        int charactersUntilCancellationCheck = 8192;
        cancellationChecker.run();
        if (limit <= 0) {
            return result(cellRows);
        }
        try {
            int next;
            while ((next = pushbackReader.read()) != -1) {
                if (--charactersUntilCancellationCheck == 0) {
                    cancellationChecker.run();
                    charactersUntilCancellationCheck = 8192;
                }
                char current = (char) next;
                if (inQuotedField) {
                    if (escape != quote && current == escape) {
                        int following = pushbackReader.read();
                        if (following == -1) {
                            throw malformedCsv(line);
                        }
                        char escaped = (char) following;
                        if (escaped == quote || escaped == escape) {
                            field.append(escaped);
                        } else {
                            field.append(current);
                            field.append(escaped);
                            if (escaped == '\n') {
                                line++;
                            } else if (escaped == '\r') {
                                line++;
                                int afterReturn = pushbackReader.read();
                                if (afterReturn == '\n') {
                                    field.append('\n');
                                } else if (afterReturn != -1) {
                                    pushbackReader.unread(afterReturn);
                                }
                            }
                        }
                    } else if (current == quote) {
                        int following = pushbackReader.read();
                        if (escape == quote && following == quote) {
                            field.append(quote);
                        } else {
                            if (following != -1) {
                                pushbackReader.unread(following);
                            }
                            inQuotedField = false;
                            justClosedQuote = true;
                        }
                    } else {
                        field.append(current);
                        if (current == '\n') {
                            line++;
                        } else if (current == '\r') {
                            line++;
                            int following = pushbackReader.read();
                            if (following == '\n') {
                                field.append('\n');
                            } else if (following != -1) {
                                pushbackReader.unread(following);
                            }
                        }
                    }
                    continue;
                }
                if (justClosedQuote && current != delimiter && current != '\n' && current != '\r') {
                    throw malformedCsv(line);
                }
                if (current == quote) {
                    if (!atStartOfField) {
                        throw malformedCsv(line);
                    }
                    inQuotedField = true;
                    quoteStartLine = line;
                    atStartOfField = false;
                    quotedField = true;
                    continue;
                }
                if (current == delimiter) {
                    fields.add(fieldValue(field, quotedField));
                    field.setLength(0);
                    justClosedQuote = false;
                    atStartOfField = true;
                    quotedField = false;
                    continue;
                }
                if (current == '\n' || current == '\r') {
                    if (current == '\r') {
                        int following = pushbackReader.read();
                        if (following != '\n' && following != -1) {
                            pushbackReader.unread(following);
                        }
                    }
                    fields.add(fieldValue(field, quotedField));
                    Map<Integer, CsvCell> completedRow = row(fields);
                    emitRow(cellRows, completedRow, rowConsumer);
                    rowCount++;
                    cancellationChecker.run();
                    if (rowCount >= limit) {
                        return result(cellRows);
                    }
                    fields = new ArrayList<>();
                    field.setLength(0);
                    line++;
                    justClosedQuote = false;
                    atStartOfField = true;
                    quotedField = false;
                    continue;
                }
                field.append(current);
                atStartOfField = false;
            }
        } catch (CharacterCodingException e) {
            throw new BusinessException("import.preview.invalidEncodingLine", new Object[]{options.getEncoding(), line}, e);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException("import.preview.parseFailed", new Object[]{e.getMessage()}, e);
        } catch (Exception e) {
            throw new BusinessException("import.preview.parseFailed", new Object[]{e.getMessage()}, e);
        }
        if (inQuotedField) {
            throw new BusinessException("import.preview.unclosedQuote", new Object[]{quoteStartLine});
        }
        if (!fields.isEmpty() || field.length() > 0 || quotedField || justClosedQuote) {
            fields.add(fieldValue(field, quotedField));
            emitRow(cellRows, row(fields), rowConsumer);
        }
        cancellationChecker.run();
        return result(cellRows);
    }

    private static void emitRow(List<Map<Integer, CsvCell>> cellRows, Map<Integer, CsvCell> row,
            RowConsumer rowConsumer) {
        if (rowConsumer == null) {
            cellRows.add(row);
            return;
        }
        Map<Integer, String> values = new LinkedHashMap<>();
        row.forEach((index, cell) -> values.put(index, cell == null ? null : cell.value()));
        rowConsumer.accept(values);
    }

    private CsvCell fieldValue(StringBuilder field, boolean quoted) {
        String value = emptyAsNull && field.length() == 0 && !quoted ? null : field.toString();
        return new CsvCell(value, quoted);
    }

    private static BusinessException malformedCsv(int line) {
        return new BusinessException("import.preview.malformedCsv", new Object[]{line});
    }

    private static Map<Integer, CsvCell> row(List<CsvCell> values) {
        Map<Integer, CsvCell> row = new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index++) {
            row.put(index, values.get(index));
        }
        return row;
    }

    private CsvResult result(List<Map<Integer, CsvCell>> cellRows) {
        List<Map<Integer, String>> rows = new ArrayList<>(cellRows.size());
        for (Map<Integer, CsvCell> cellRow : cellRows) {
            Map<Integer, String> row = new LinkedHashMap<>();
            cellRow.forEach((index, cell) -> row.put(index, cell == null ? null : cell.value()));
            rows.add(row);
        }
        return new CsvResult(rows, cellRows, hasHeader ? 1 : 0);
    }

    private BomAwareInput detectBom(InputStream inputStream) throws IOException {
        PushbackInputStream pushbackInputStream = new PushbackInputStream(inputStream, 4);
        byte[] bom = new byte[4];
        int read = 0;
        while (read < bom.length) {
            int next = pushbackInputStream.read();
            if (next == -1) {
                break;
            }
            bom[read++] = (byte) next;
        }
        int unread = read;
        Charset charset = defaultCharset();
        if (read >= 3 && (bom[0] & 0xff) == 0xEF && (bom[1] & 0xff) == 0xBB && (bom[2] & 0xff) == 0xBF) {
            charset = Charset.forName("UTF-8");
            unread = read - 3;
            if (unread > 0) {
                pushbackInputStream.unread(bom, 3, unread);
            }
        } else if (read >= 2 && (bom[0] & 0xff) == 0xFF && (bom[1] & 0xff) == 0xFE) {
            charset = Charset.forName("UTF-16LE");
            unread = read - 2;
            if (unread > 0) {
                pushbackInputStream.unread(bom, 2, unread);
            }
        } else if (read >= 2 && (bom[0] & 0xff) == 0xFE && (bom[1] & 0xff) == 0xFF) {
            charset = Charset.forName("UTF-16BE");
            unread = read - 2;
            if (unread > 0) {
                pushbackInputStream.unread(bom, 2, unread);
            }
        } else if (read > 0) {
            pushbackInputStream.unread(bom, 0, read);
        }
        return new BomAwareInput(pushbackInputStream, charset);
    }

    private Charset defaultCharset() {
        if (CsvOptions.AUTO_ENCODING.equals(options.getEncoding())) {
            return Charset.forName(DEFAULT_ENCODING);
        }
        return Charset.forName(options.getEncoding());
    }

    public record CsvCell(String value, boolean quoted) {
    }

    public record CsvResult(List<Map<Integer, String>> rows, List<Map<Integer, CsvCell>> cells, int headerRowCount) {
    }

    @FunctionalInterface
    public interface RowConsumer {
        void accept(Map<Integer, String> row);
    }

    private record BomAwareInput(InputStream inputStream, Charset charset) {
    }

    private static final class OneByteInputStream extends InputStream {
        private final byte[] bytes;
        private int position;

        private OneByteInputStream(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read() throws IOException {
            return position >= bytes.length ? -1 : bytes[position++] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (position >= bytes.length) {
                return -1;
            }
            buffer[offset] = bytes[position++];
            return 1;
        }
    }

    /** Lets the decoder read in batches without prefetching bytes from a later source line. */
    private static final class LineBoundedInputStream extends InputStream {
        private final InputStream inputStream;

        private LineBoundedInputStream(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        @Override
        public int read() throws IOException {
            return inputStream.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            int count = 0;
            while (count < length) {
                int next = inputStream.read();
                if (next == -1) {
                    return count == 0 ? -1 : count;
                }
                buffer[offset + count++] = (byte) next;
                if (next == '\n' || next == '\r') {
                    break;
                }
            }
            return count;
        }
    }
}
