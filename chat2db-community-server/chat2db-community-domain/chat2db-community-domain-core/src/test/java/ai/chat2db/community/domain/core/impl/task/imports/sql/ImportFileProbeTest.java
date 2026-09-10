package ai.chat2db.community.domain.core.impl.task.imports.sql;

import ai.chat2db.community.domain.core.impl.task.imports.ImportFileProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportFileProbeTest {

    @Test
    void detectsUtfBomBeforeHeuristicCharsetDetection(@TempDir Path directory) throws Exception {
        assertEquals(StandardCharsets.UTF_8,
                ImportFileProbe.detectCharset(encoded(directory, "utf8.sql", StandardCharsets.UTF_8,
                        0xEF, 0xBB, 0xBF)));
        assertEquals(StandardCharsets.UTF_16LE,
                ImportFileProbe.detectCharset(encoded(directory, "utf16le.sql", StandardCharsets.UTF_16LE,
                        0xFF, 0xFE)));
        assertEquals(StandardCharsets.UTF_16BE,
                ImportFileProbe.detectCharset(encoded(directory, "utf16be.sql", StandardCharsets.UTF_16BE,
                        0xFE, 0xFF)));
        assertEquals(Charset.forName("UTF-32LE"),
                ImportFileProbe.detectCharset(encoded(directory, "utf32le.sql", Charset.forName("UTF-32LE"),
                        0xFF, 0xFE, 0x00, 0x00)));
        assertEquals(Charset.forName("UTF-32BE"),
                ImportFileProbe.detectCharset(encoded(directory, "utf32be.sql", Charset.forName("UTF-32BE"),
                        0x00, 0x00, 0xFE, 0xFF)));
    }

    @Test
    void detectedUtf16AndUtf32SqlCanBeParsedWithoutReplacement(@TempDir Path directory) throws Exception {
        for (EncodedCase encodedCase : new EncodedCase[]{
                new EncodedCase("utf16le.sql", StandardCharsets.UTF_16LE, new int[]{0xFF, 0xFE}),
                new EncodedCase("utf16be.sql", StandardCharsets.UTF_16BE, new int[]{0xFE, 0xFF}),
                new EncodedCase("utf32le.sql", Charset.forName("UTF-32LE"),
                        new int[]{0xFF, 0xFE, 0x00, 0x00}),
                new EncodedCase("utf32be.sql", Charset.forName("UTF-32BE"),
                        new int[]{0x00, 0x00, 0xFE, 0xFF})}) {
            File source = encoded(directory, encodedCase.name(), encodedCase.charset(), encodedCase.bom());
            ExportedSqlStatementReader.Inspection inspection = ExportedSqlStatementReader.inspect(
                    source, ImportFileProbe.detectCharset(source),
                    ExportedSqlStatementReader.ExporterProfile.NAVICAT);
            assertTrue(inspection.supported(), () -> String.valueOf(inspection.unsupported()));
            assertEquals(1, inspection.dataStatementCount());
        }
    }

    private File encoded(Path directory, String name, Charset charset, int... bom) throws Exception {
        byte[] body = "INSERT INTO item VALUES (1, 'data');\n".getBytes(charset);
        byte[] bytes = new byte[bom.length + body.length];
        for (int index = 0; index < bom.length; index++) {
            bytes[index] = (byte) bom[index];
        }
        System.arraycopy(body, 0, bytes, bom.length, body.length);
        return Files.write(directory.resolve(name), bytes).toFile();
    }

    private record EncodedCase(String name, Charset charset, int[] bom) {
    }
}
