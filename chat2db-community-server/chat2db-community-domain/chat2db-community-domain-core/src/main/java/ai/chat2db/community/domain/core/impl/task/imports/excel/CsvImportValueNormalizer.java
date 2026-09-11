package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.tools.exception.BusinessException;

import java.sql.Types;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CsvImportValueNormalizer {

    private static final Map<String, Integer> MONTHS = monthNames();
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "BIGINT", "DECIMAL", "DOUBLE", "DOUBLE PRECISION", "FLOAT", "INT", "INTEGER",
            "MEDIUMINT", "NUMBER", "NUMERIC", "REAL", "SMALLINT", "TINYINT");

    private CsvImportValueNormalizer() {
    }

    static String normalize(String value, TableColumn column, CsvOptions options, long sourceRow) {
        if (value == null || column == null) {
            return value;
        }
        try {
            return switch (valueKind(column)) {
                case DATE -> parseDate(value, options).toString();
                case TIME -> parseTime(value, options).toString();
                case TIMESTAMP -> parseDateTime(value, options).dateTime().toString().replace('T', ' ');
                case TIMESTAMP_WITH_TIMEZONE -> formatOffsetDateTime(parseDateTime(value, options));
                case NUMBER -> normalizeNumber(value, options);
                case TEXT -> value;
            };
        } catch (RuntimeException e) {
            String displayValue = value.length() > 80 ? value.substring(0, 80) + "..." : value;
            throw new BusinessException("import.csv.invalidValue",
                    new Object[]{sourceRow, column.getName(), displayValue}, e);
        }
    }

    private static ValueKind valueKind(TableColumn column) {
        Integer dataType = column.getDataType();
        if (dataType != null) {
            switch (dataType) {
                case Types.DATE:
                    return ValueKind.DATE;
                case Types.TIME, Types.TIME_WITH_TIMEZONE:
                    return ValueKind.TIME;
                case Types.TIMESTAMP:
                    return ValueKind.TIMESTAMP;
                case Types.TIMESTAMP_WITH_TIMEZONE:
                    return ValueKind.TIMESTAMP_WITH_TIMEZONE;
                case Types.BIGINT, Types.DECIMAL, Types.DOUBLE, Types.FLOAT, Types.INTEGER,
                        Types.NUMERIC, Types.REAL, Types.SMALLINT, Types.TINYINT:
                    return ValueKind.NUMBER;
                default:
                    break;
            }
        }
        String columnType = column.getColumnType();
        if (columnType == null) {
            return ValueKind.TEXT;
        }
        String type = columnType.trim().toUpperCase(Locale.ROOT);
        int parameterStart = type.indexOf('(');
        if (parameterStart >= 0) {
            type = type.substring(0, parameterStart).trim();
        }
        if (type.endsWith(" UNSIGNED")) {
            type = type.substring(0, type.length() - " UNSIGNED".length()).trim();
        }
        if (type.contains("TIMESTAMP") || "DATETIME".equals(type)) {
            return type.contains("TIME ZONE") ? ValueKind.TIMESTAMP_WITH_TIMEZONE : ValueKind.TIMESTAMP;
        }
        if (type.startsWith("TIME")) {
            return ValueKind.TIME;
        }
        if ("DATE".equals(type)) {
            return ValueKind.DATE;
        }
        if (NUMERIC_TYPES.contains(type)) {
            return ValueKind.NUMBER;
        }
        return ValueKind.TEXT;
    }

    private static String normalizeNumber(String value, CsvOptions options) {
        if (!",".equals(options.getDecimalSymbol())) {
            return value;
        }
        if (value.indexOf('.') >= 0 && value.indexOf(',') >= 0) {
            throw new IllegalArgumentException("ambiguous decimal value");
        }
        return value.replace(',', '.');
    }

    private static ParsedDateTime parseDateTime(String value, CsvOptions options) {
        List<String> tokens = new ArrayList<>(Arrays.asList(value.trim().split("\\s+", -1)));
        if (options.getDateTimeOrder().startsWith("DATE_TIME") && !tokens.isEmpty()) {
            int separator = tokens.get(0).indexOf('T');
            if (separator > 0) {
                String date = tokens.get(0).substring(0, separator);
                String time = tokens.get(0).substring(separator + 1);
                tokens.set(0, date);
                tokens.add(1, time);
            } else if (tokens.size() == 1 && "DATE_TIME".equals(options.getDateTimeOrder())) {
                return new ParsedDateTime(LocalDateTime.of(parseDate(tokens.get(0), options), LocalTime.MIDNIGHT),
                        null);
            }
        }
        String[] parts = tokens.toArray(String[]::new);
        return switch (options.getDateTimeOrder()) {
            case "TIME_DATE" -> parsedDateTime(parts, 1, 0, -1, options);
            case "DATE_TIME_TIMEZONE" -> parsedDateTime(parts, 0, 1, 2, options);
            case "TIME_DATE_TIMEZONE" -> parsedDateTime(parts, 1, 0, 2, options);
            case "TIME_TIMEZONE_DATE" -> parsedDateTime(parts, 2, 0, 1, options);
            default -> parsedDateTime(parts, 0, 1, -1, options);
        };
    }

    private static ParsedDateTime parsedDateTime(String[] parts, int dateIndex, int timeIndex, int zoneIndex,
            CsvOptions options) {
        int expectedParts = zoneIndex < 0 ? 2 : 3;
        if (parts.length != expectedParts) {
            throw new IllegalArgumentException("date-time value does not match configured order");
        }
        ZoneOffset offset = zoneIndex < 0 ? null : ZoneOffset.of(parts[zoneIndex]);
        return new ParsedDateTime(LocalDateTime.of(parseDate(parts[dateIndex], options),
                parseTime(parts[timeIndex], options)), offset);
    }

    private static String formatOffsetDateTime(ParsedDateTime parsed) {
        if (parsed.offset() == null) {
            return parsed.dateTime().toString().replace('T', ' ');
        }
        return OffsetDateTime.of(parsed.dateTime(), parsed.offset()).toString().replace('T', ' ');
    }

    private static LocalDate parseDate(String value, CsvOptions options) {
        String order = options.getDateOrder();
        String[] components = splitDate(value.trim(), order, options);
        Map<Character, String> values = new HashMap<>();
        for (int index = 0; index < order.length(); index++) {
            values.put(order.charAt(index), components[index]);
        }
        int year = parseYear(values.get('Y'));
        int month = parseMonth(values.get('M'));
        int day = Integer.parseInt(values.get('D'));
        return LocalDate.of(year, month, day);
    }

    private static String[] splitDate(String value, String order, CsvOptions options) {
        String firstSeparator = separator(order.charAt(0), order.charAt(1), options);
        String secondSeparator = separator(order.charAt(1), order.charAt(2), options);
        Pattern pattern = Pattern.compile("^(.+?)" + Pattern.quote(firstSeparator) + "(.+?)"
                + Pattern.quote(secondSeparator) + "(.+?)$");
        Matcher matcher = pattern.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("date does not match configured delimiters");
        }
        return new String[]{matcher.group(1), matcher.group(2), matcher.group(3)};
    }

    private static String separator(char left, char right, CsvOptions options) {
        return left == 'Y' || right == 'Y' ? options.getYearDelimiter() : options.getDateDelimiter();
    }

    private static LocalTime parseTime(String value, CsvOptions options) {
        String[] parts = value.trim().split(Pattern.quote(options.getTimeDelimiter()), -1);
        if (parts.length < 2 || parts.length > 3) {
            throw new IllegalArgumentException("time does not match configured delimiter");
        }
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        if (parts.length == 2) {
            return LocalTime.of(hour, minute);
        }
        String[] seconds = parts[2].split("\\.", 2);
        int second = Integer.parseInt(seconds[0]);
        int nanos = seconds.length == 1 ? 0 : Integer.parseInt((seconds[1] + "000000000").substring(0, 9));
        return LocalTime.of(hour, minute, second, nanos);
    }

    private static int parseYear(String value) {
        int year = Integer.parseInt(value);
        return value.length() == 2 ? 2000 + year : year;
    }

    private static int parseMonth(String value) {
        if (value.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(value);
        }
        Integer month = MONTHS.get(value.toLowerCase(Locale.ROOT));
        if (month == null) {
            throw new DateTimeException("unknown month");
        }
        return month;
    }

    private static Map<String, Integer> monthNames() {
        Map<String, Integer> names = new HashMap<>();
        for (Month month : Month.values()) {
            names.put(month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toLowerCase(Locale.ROOT),
                    month.getValue());
            names.put(month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT),
                    month.getValue());
        }
        return names;
    }

    private record ParsedDateTime(LocalDateTime dateTime, ZoneOffset offset) {
    }

    private enum ValueKind {
        DATE,
        TIME,
        TIMESTAMP,
        TIMESTAMP_WITH_TIMEZONE,
        NUMBER,
        TEXT
    }
}
