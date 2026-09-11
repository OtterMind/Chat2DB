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
    public static final String DEFAULT_DATE_ORDER = "YMD";
    public static final String DEFAULT_DATE_TIME_ORDER = "DATE_TIME";
    public static final String DEFAULT_DATE_DELIMITER = "-";
    public static final String DEFAULT_TIME_DELIMITER = ":";
    public static final String DEFAULT_DECIMAL_SYMBOL = ".";

    private static final Set<String> SUPPORTED_NEWLINES = Set.of("LF", "CRLF", "CR");
    private static final Set<String> SUPPORTED_DATE_ORDERS = Set.of("YMD", "YDM", "MDY", "MYD", "DMY", "DYM");
    private static final Set<String> SUPPORTED_DATE_TIME_ORDERS = Set.of(
            "DATE_TIME", "TIME_DATE", "DATE_TIME_TIMEZONE", "TIME_DATE_TIMEZONE", "TIME_TIMEZONE_DATE");

    private String encoding;

    private String delimiter;

    private String quote;

    private String escape;

    private String newline;

    private Boolean hasHeader;

    private Boolean emptyAsNull;

    private Integer headerRow;

    private Integer dataStartRow;

    private Integer dataEndRow;

    private String dateOrder;

    private String dateTimeOrder;

    private String dateDelimiter;

    private String yearDelimiter;

    private String timeDelimiter;

    private String decimalSymbol;

    public static CsvOptions defaults() {
        return CsvOptions.builder()
                .encoding(DEFAULT_ENCODING)
                .delimiter(DEFAULT_DELIMITER)
                .quote(DEFAULT_QUOTE)
                .escape(DEFAULT_ESCAPE)
                .newline(DEFAULT_NEWLINE)
                .hasHeader(true)
                .emptyAsNull(true)
                .headerRow(1)
                .dataStartRow(2)
                .dateOrder(DEFAULT_DATE_ORDER)
                .dateTimeOrder(DEFAULT_DATE_TIME_ORDER)
                .dateDelimiter(DEFAULT_DATE_DELIMITER)
                .yearDelimiter(DEFAULT_DATE_DELIMITER)
                .timeDelimiter(DEFAULT_TIME_DELIMITER)
                .decimalSymbol(DEFAULT_DECIMAL_SYMBOL)
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
                .headerRow(integerValue(values.get("headerRow")))
                .dataStartRow(integerValue(values.get("dataStartRow")))
                .dataEndRow(integerValue(values.get("dataEndRow")))
                .dateOrder(stringValue(values.get("dateOrder"), defaults.getDateOrder()))
                .dateTimeOrder(stringValue(values.get("dateTimeOrder"), defaults.getDateTimeOrder()))
                .dateDelimiter(stringValue(values.get("dateDelimiter"), defaults.getDateDelimiter()))
                .yearDelimiter(stringValue(values.get("yearDelimiter"), defaults.getYearDelimiter()))
                .timeDelimiter(stringValue(values.get("timeDelimiter"), defaults.getTimeDelimiter()))
                .decimalSymbol(stringValue(values.get("decimalSymbol"), defaults.getDecimalSymbol()))
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
        if (!isSingleTextCharacter(options.delimiter)
                || !SUPPORTED_NEWLINES.contains(options.newline)
                || !isSingleTextCharacter(options.quote)
                || !isSingleTextCharacter(options.escape)
                || options.delimiter.equals(options.quote)
                || options.delimiter.equals(options.escape)
                || !SUPPORTED_DATE_ORDERS.contains(options.dateOrder)
                || !SUPPORTED_DATE_TIME_ORDERS.contains(options.dateTimeOrder)
                || !isSingleTextCharacter(options.dateDelimiter)
                || !isSingleTextCharacter(options.yearDelimiter)
                || !isSingleTextCharacter(options.timeDelimiter)
                || !(".".equals(options.decimalSymbol) || ",".equals(options.decimalSymbol))
                || options.headerRow < 1 || options.dataStartRow < 1
                || options.dataEndRow != null && options.dataEndRow < options.dataStartRow
                || Boolean.TRUE.equals(options.hasHeader) && options.headerRow >= options.dataStartRow) {
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
        values.put("headerRow", options.headerRow);
        values.put("dataStartRow", options.dataStartRow);
        values.put("dataEndRow", options.dataEndRow);
        values.put("dateOrder", options.dateOrder);
        values.put("dateTimeOrder", options.dateTimeOrder);
        values.put("dateDelimiter", options.dateDelimiter);
        values.put("yearDelimiter", options.yearDelimiter);
        values.put("timeDelimiter", options.timeDelimiter);
        values.put("decimalSymbol", options.decimalSymbol);
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
        boolean normalizedHasHeader = hasHeader == null ? defaults.hasHeader : hasHeader;
        int normalizedHeaderRow = headerRow == null ? defaults.headerRow : headerRow;
        int normalizedDataStartRow = dataStartRow == null
                ? (normalizedHasHeader ? normalizedHeaderRow + 1 : 1) : dataStartRow;
        return CsvOptions.builder()
                .encoding(normalizeEncoding(StringUtils.defaultIfBlank(encoding, defaults.encoding)))
                .delimiter(StringUtils.defaultIfEmpty(delimiter, defaults.delimiter))
                .quote(StringUtils.defaultIfEmpty(quote, defaults.quote))
                .escape(StringUtils.defaultIfEmpty(escape, defaults.escape))
                .newline(StringUtils.defaultIfBlank(newline, defaults.newline).trim().toUpperCase(Locale.ROOT))
                .hasHeader(normalizedHasHeader)
                .emptyAsNull(emptyAsNull == null ? defaults.emptyAsNull : emptyAsNull)
                .headerRow(normalizedHeaderRow)
                .dataStartRow(normalizedDataStartRow)
                .dataEndRow(dataEndRow)
                .dateOrder(normalizeOption(dateOrder, defaults.dateOrder))
                .dateTimeOrder(normalizeOption(dateTimeOrder, defaults.dateTimeOrder))
                .dateDelimiter(StringUtils.defaultIfEmpty(dateDelimiter, defaults.dateDelimiter))
                .yearDelimiter(StringUtils.defaultIfEmpty(yearDelimiter,
                        StringUtils.defaultIfEmpty(dateDelimiter, defaults.dateDelimiter)))
                .timeDelimiter(StringUtils.defaultIfEmpty(timeDelimiter, defaults.timeDelimiter))
                .decimalSymbol(StringUtils.defaultIfEmpty(decimalSymbol, defaults.decimalSymbol))
                .build();
    }

    private static String normalizeOption(String value, String defaultValue) {
        return StringUtils.defaultIfBlank(value, defaultValue).trim().toUpperCase(Locale.ROOT);
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

    private static Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            long longValue = number.longValue();
            if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE
                    || number.doubleValue() != longValue) {
                throw new BusinessException("import.preview.invalidCsvOptions");
            }
            return (int) longValue;
        }
        throw new BusinessException("import.preview.invalidCsvOptions");
    }
}
