package ai.chat2db.community.domain.core.impl.task.imports;

import cn.hutool.core.io.CharsetDetector;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Format detection for import sources. The charset detector is the same one the desktop text
 * engine uses, so the preview and the actual import always agree.
 */
public final class ImportFileProbe {

    private static final char[] DELIMITERS = {',', ';', '\t', '|'};

    private static final int SAMPLE_ROWS = 20;

    private ImportFileProbe() {
    }

    public static Charset detectCharset(File file) {
        Charset detected = CharsetDetector.detect(file, StandardCharsets.UTF_8,
                Charset.forName("GBK"), StandardCharsets.ISO_8859_1);
        return detected == null ? StandardCharsets.UTF_8 : detected;
    }

    public static Charset effectiveCharset(File file, String requested) {
        return StringUtils.isBlank(requested) ? detectCharset(file) : Charset.forName(requested.trim());
    }

    /**
     * The candidate delimiter appearing most often outside quoted sections of the first line.
     */
    public static char detectDelimiter(String firstLine, char quote) {
        if (firstLine == null) {
            return ',';
        }
        char best = ',';
        int bestCount = -1;
        boolean inQuotes = false;
        int[] counts = new int[DELIMITERS.length];
        for (int index = 0; index < firstLine.length(); index++) {
            char current = firstLine.charAt(index);
            if (current == quote) {
                inQuotes = !inQuotes;
            } else if (!inQuotes) {
                for (int candidate = 0; candidate < DELIMITERS.length; candidate++) {
                    if (current == DELIMITERS[candidate]) {
                        counts[candidate]++;
                    }
                }
            }
        }
        for (int candidate = 0; candidate < DELIMITERS.length; candidate++) {
            if (counts[candidate] > bestCount) {
                bestCount = counts[candidate];
                best = DELIMITERS[candidate];
            }
        }
        return best;
    }

    /**
     * CSV grammar settings; the charset is passed to {@link #openParser} separately because
     * commons-csv keeps encoding on the reader, not the format.
     */
    public static CSVFormat csvFormat(char delimiter, char quote) {
        return CSVFormat.Builder.create(CSVFormat.EXCEL)
                .setDelimiter(delimiter)
                .setQuote(quote)
                .setSkipHeaderRecord(false)
                .setIgnoreEmptyLines(true)
                .setAllowMissingColumnNames(true)
                .build();
    }

    public static CSVParser openParser(File file, Charset charset, CSVFormat format) throws IOException {
        return CSVParser.parse(file.toPath(), charset, format);
    }

    public static char quoteChar(String requested) {
        return StringUtils.isBlank(requested) ? '"' : requested.trim().charAt(0);
    }

    public static char delimiterChar(String requested, Charset charset, File file) throws IOException {
        if (StringUtils.isNotBlank(requested)) {
            return requested.trim().charAt(0);
        }
        try (Reader reader = new InputStreamReader(Files.newInputStream(file.toPath()), charset)) {
            StringBuilder line = new StringBuilder();
            int current;
            while ((current = reader.read()) != -1) {
                if (current == '\n' || current == '\r') {
                    if (line.length() > 0) {
                        break;
                    }
                    continue;
                }
                line.append((char) current);
            }
            return detectDelimiter(line.toString(), '"');
        }
    }

    /**
     * Reads the header and the first data rows for a preview.
     */
    public static List<List<String>> readSample(File file, Charset charset, CSVFormat format, int maxRows)
            throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (CSVParser parser = openParser(file, charset, format)) {
            int count = 0;
            for (CSVRecord record : parser) {
                if (count++ > maxRows) {
                    break;
                }
                rows.add(new ArrayList<>(record.toList()));
            }
        }
        return rows;
    }

    public static int sampleRows() {
        return SAMPLE_ROWS;
    }
}
