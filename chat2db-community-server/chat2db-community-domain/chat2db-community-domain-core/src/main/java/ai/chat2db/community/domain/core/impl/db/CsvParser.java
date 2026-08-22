package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.tools.exception.BusinessException;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict CSV parser shared by the import preview and execution paths. */
final class CsvParser {

    static final String DEFAULT_ENCODING = "UTF-8";
    private final Charset charset;
    private final char delimiter;
    private final char quote;
    private final boolean hasHeader;

    CsvParser(String encoding, String delimiter, String quote, String escape, boolean hasHeader, boolean emptyAsNull) {
        try {
            charset = Charset.forName(encoding == null ? DEFAULT_ENCODING : encoding);
        } catch (Exception e) {
            throw new BusinessException("import.preview.invalidEncoding", new Object[]{encoding}, e);
        }
        if (delimiter == null || delimiter.length() != 1 || quote == null || quote.length() != 1
                || escape == null || escape.length() != 1 || quote.charAt(0) != escape.charAt(0)) {
            throw new BusinessException("import.preview.invalidCsvOptions");
        }
        this.delimiter = delimiter.charAt(0);
        this.quote = quote.charAt(0);
        this.hasHeader = hasHeader;
    }

    CsvResult parse(byte[] bytes, int limit) {
        final String text;
        try {
            CharBuffer decoded = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            text = decoded.toString();
        } catch (Exception e) {
            throw new BusinessException("import.preview.invalidEncoding", new Object[]{charset.name()}, e);
        }
        return parse(new java.io.StringReader(text), limit);
    }

    CsvResult parse(Path file, int limit) {
        try (Reader reader = Files.newBufferedReader(file, charset)) {
            return parse(reader, limit);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("import.preview.invalidEncoding", new Object[]{charset.name()}, e);
        }
    }

    private CsvResult parse(Reader reader, int limit) {
        List<Map<Integer, String>> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        try {
            int next;
            while ((next = reader.read()) != -1) {
                char current = (char) next;
                if (current == quote) {
                    reader.mark(1);
                    int following = reader.read();
                    if (quoted && following == quote) {
                        field.append(quote);
                    } else {
                        if (following != -1) {
                            reader.reset();
                        }
                        quoted = !quoted;
                    }
                    continue;
                }
                if (!quoted && current == delimiter) {
                    fields.add(field.toString());
                    field.setLength(0);
                    continue;
                }
                if (!quoted && (current == '\n' || current == '\r')) {
                    if (current == '\r') {
                        reader.mark(1);
                        if (reader.read() != '\n') {
                            reader.reset();
                        }
                    }
                    fields.add(field.toString());
                    rows.add(row(fields));
                    if (rows.size() >= limit) {
                        return new CsvResult(rows, hasHeader ? 1 : 0);
                    }
                    fields = new ArrayList<>();
                    field.setLength(0);
                    line++;
                    continue;
                }
                field.append(current);
            }
        } catch (Exception e) {
            throw new BusinessException("import.preview.parseFailed", new Object[]{e.getMessage()}, e);
        }
        if (quoted) {
            throw new BusinessException("import.preview.unclosedQuote", new Object[]{line});
        }
        if (!fields.isEmpty() || field.length() > 0) {
            fields.add(field.toString());
            rows.add(row(fields));
        }
        return new CsvResult(rows, hasHeader ? 1 : 0);
    }

    private static Map<Integer, String> row(List<String> values) {
        Map<Integer, String> row = new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index++) {
            row.put(index, values.get(index));
        }
        return row;
    }

    record CsvResult(List<Map<Integer, String>> rows, int headerRowCount) {
    }
}
