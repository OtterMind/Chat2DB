package ai.chat2db.community.domain.api.model.task;

import ai.chat2db.community.tools.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CsvOptions {

    public static final String DEFAULT_ENCODING = "UTF-8";
    public static final String AUTO_ENCODING = "AUTO";
    public static final String DEFAULT_DELIMITER = ",";
    public static final String DEFAULT_QUOTE = "\"";
    public static final String DEFAULT_ESCAPE = "\"";
    public static final String DEFAULT_NEWLINE = "LF";

    private static final Set<String> SUPPORTED_ENCODINGS = Set.of(
            AUTO_ENCODING, DEFAULT_ENCODING, "UTF-16LE", "UTF-16BE", "GB18030", "ISO-8859-1",
            "WINDOWS-1252", "SHIFT_JIS", "BIG5");
    private static final Set<String> SUPPORTED_DELIMITERS = Set.of(",", ";", "\t", "|");
    private static final Set<String> SUPPORTED_NEWLINES = Set.of("LF", "CRLF", "CR");

    private String encoding;

    private String delimiter;

    private String quote;

    private String escape;

    private String newline;

    private Boolean hasHeader;

    private Boolean emptyAsNull;

    public static CsvOptions defaults() {
        return CsvOptions.builder()
                .encoding(DEFAULT_ENCODING)
                .delimiter(DEFAULT_DELIMITER)
                .quote(DEFAULT_QUOTE)
                .escape(DEFAULT_ESCAPE)
                .newline(DEFAULT_NEWLINE)
                .hasHeader(true)
                .emptyAsNull(true)
                .build();
    }

    public static CsvOptions fromMap(Map<String, Object> values) {
        CsvOptions defaults = defaults();
        if (values == null || values.isEmpty()) {
            return defaults.validate();
        }
        CsvOptions options = CsvOptions.builder()
                .encoding(stringValue(values.get("encoding"), defaults.getEncoding()))
                .delimiter(stringValue(values.get("delimiter"), defaults.getDelimiter()))
                .quote(stringValue(values.get("quote"), defaults.getQuote()))
                .escape(stringValue(values.get("escape"), defaults.getEscape()))
                .newline(stringValue(values.get("newline"), defaults.getNewline()))
                .hasHeader(booleanValue(values.get("hasHeader"), defaults.getHasHeader()))
                .emptyAsNull(booleanValue(values.get("emptyAsNull"), defaults.getEmptyAsNull()))
                .build();
        return options.validate();
    }

    public CsvOptions validate() {
        CsvOptions options;
        try {
            options = normalized();
        } catch (Exception e) {
            throw new BusinessException("import.preview.invalidEncoding", new Object[]{encoding}, e);
        }
        if (!SUPPORTED_ENCODINGS.contains(options.encoding)
                || !SUPPORTED_DELIMITERS.contains(options.delimiter)
                || !SUPPORTED_NEWLINES.contains(options.newline)
                || !isSingleTextCharacter(options.quote)
                || !isSingleTextCharacter(options.escape)
                || options.delimiter.equals(options.quote)
                || options.delimiter.equals(options.escape)) {
            throw new BusinessException("import.preview.invalidCsvOptions");
        }
        if (!AUTO_ENCODING.equals(options.encoding)) {
            try {
                Charset.forName(options.encoding);
            } catch (Exception e) {
                throw new BusinessException("import.preview.invalidEncoding", new Object[]{options.encoding}, e);
            }
        }
        return options;
    }

    public Map<String, Object> toMap() {
        CsvOptions options = validate();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("encoding", options.encoding);
        values.put("delimiter", options.delimiter);
        values.put("quote", options.quote);
        values.put("escape", options.escape);
        values.put("newline", options.newline);
        values.put("hasHeader", options.hasHeader);
        values.put("emptyAsNull", options.emptyAsNull);
        return values;
    }

    public String rowSeparator() {
        return switch (validate().newline) {
            case "CRLF" -> "\r\n";
            case "CR" -> "\r";
            default -> "\n";
        };
    }

    private CsvOptions normalized() {
        CsvOptions defaults = defaults();
        return CsvOptions.builder()
                .encoding(normalizeEncoding(StringUtils.defaultIfBlank(encoding, defaults.encoding)))
                .delimiter(StringUtils.defaultIfEmpty(delimiter, defaults.delimiter))
                .quote(StringUtils.defaultIfEmpty(quote, defaults.quote))
                .escape(StringUtils.defaultIfEmpty(escape, defaults.escape))
                .newline(StringUtils.defaultIfBlank(newline, defaults.newline).trim().toUpperCase(Locale.ROOT))
                .hasHeader(hasHeader == null ? defaults.hasHeader : hasHeader)
                .emptyAsNull(emptyAsNull == null ? defaults.emptyAsNull : emptyAsNull)
                .build();
    }

    private static String normalizeEncoding(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (AUTO_ENCODING.equals(normalized)) {
            return AUTO_ENCODING;
        }
        return Charset.forName(value.trim()).name().toUpperCase(Locale.ROOT);
    }

    private static boolean isSingleTextCharacter(String value) {
        return value != null && value.length() == 1 && value.charAt(0) != '\n' && value.charAt(0) != '\r';
    }

    private static String stringValue(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof String stringValue) {
            return stringValue;
        }
        throw new BusinessException("import.preview.invalidCsvOptions");
    }

    private static Boolean booleanValue(Object value, Boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw new BusinessException("import.preview.invalidCsvOptions");
    }
}
