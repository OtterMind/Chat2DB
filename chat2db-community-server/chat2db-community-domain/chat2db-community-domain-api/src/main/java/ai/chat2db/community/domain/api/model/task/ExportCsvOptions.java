package ai.chat2db.community.domain.api.model.task;

import ai.chat2db.community.tools.exception.BusinessException;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CSV output options of an export task: the character set and the field delimiter of the file.
 *
 * Both are optional and keep the historical defaults ({@code UTF-8} and {@code ,}), so an export
 * that does not name them stays byte-identical to the EasyExcel CSV path it replaced. Values are
 * validated here instead of inside the writer, so a request is rejected with a named error code
 * before a task is created.
 */
public final class ExportCsvOptions {

    public static final String DEFAULT_ENCODING = "UTF-8";
    public static final String DEFAULT_DELIMITER = ",";

    private static final char QUOTE = '"';

    /** Character sets the UI offers; anything else is rejected instead of silently accepted. */
    private static final List<String> SUPPORTED_ENCODINGS = List.of(
            "UTF-8", "GBK", "GB18030", "UTF-16LE", "UTF-16BE", "ISO-8859-1", "BIG5");

    /** Normalizes and validates the options of an export; absent values resolve to the defaults. */
    public static ExportCsvOptions of(String encoding, String delimiter) {
        String normalizedEncoding = StringUtils.isBlank(encoding)
                ? DEFAULT_ENCODING : encoding.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_ENCODINGS.contains(normalizedEncoding)) {
            throw new BusinessException("task.export.encoding.invalid",
                    new Object[]{StringUtils.defaultIfBlank(encoding, DEFAULT_ENCODING)});
        }
        String requestedDelimiter = StringUtils.isEmpty(delimiter) ? DEFAULT_DELIMITER : delimiter;
        if (requestedDelimiter.length() != 1
                || requestedDelimiter.charAt(0) == '\n'
                || requestedDelimiter.charAt(0) == '\r'
                || requestedDelimiter.charAt(0) == QUOTE) {
            throw new BusinessException("task.export.delimiter.invalid", new Object[]{delimiter});
        }
        return new ExportCsvOptions(normalizedEncoding, requestedDelimiter.charAt(0));
    }

    /** The defaults an export without explicit CSV options uses. */
    public static ExportCsvOptions defaults() {
        return of(null, null);
    }

    private final String encoding;

    private final char delimiter;

    private ExportCsvOptions(String encoding, char delimiter) {
        this.encoding = encoding;
        this.delimiter = delimiter;
    }

    public String getEncoding() {
        return encoding;
    }

    /** The delimiter as the single character the writer emits. */
    public char delimiterChar() {
        return delimiter;
    }

    /** The delimiter as it is stored in the task spec. */
    public String getDelimiter() {
        return String.valueOf(delimiter);
    }

    public Charset charset() {
        return Charset.forName(encoding);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("encoding", encoding);
        values.put("delimiter", getDelimiter());
        return values;
    }
}
