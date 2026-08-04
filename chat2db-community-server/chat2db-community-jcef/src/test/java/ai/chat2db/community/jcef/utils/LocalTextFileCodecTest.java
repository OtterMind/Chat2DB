package ai.chat2db.community.jcef.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTextFileCodecTest {

    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] UTF_16_LE_BOM = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] UTF_16_BE_BOM = {(byte) 0xFE, (byte) 0xFF};

    @TempDir
    Path tempDir;

    @Test
    void shouldDetectSupportedLocalFileEncodings() throws Exception {
        String content = "select '\u4e2d\u6587';\r\n";

        assertDetected("utf8.sql", content.getBytes(StandardCharsets.UTF_8), content, "UTF-8", false);
        assertDetected(
                "utf8-bom.sql",
                withBom(UTF_8_BOM, content.getBytes(StandardCharsets.UTF_8)),
                content,
                "UTF-8",
                true
        );
        assertDetected(
                "utf16le.sql",
                withBom(UTF_16_LE_BOM, content.getBytes(StandardCharsets.UTF_16LE)),
                content,
                "UTF-16LE",
                true
        );
        assertDetected(
                "utf16be.sql",
                withBom(UTF_16_BE_BOM, content.getBytes(StandardCharsets.UTF_16BE)),
                content,
                "UTF-16BE",
                true
        );
        assertDetected("gb18030.sql", content.getBytes(GB18030), content, "GB18030", false);
    }

    @Test
    void shouldPreserveDetectedCharsetAndBomWhenUpdating() throws Exception {
        String original = "select '\u4e2d\u6587';";
        String updated = "select '\u4fee\u6539\u540e';";

        assertRoundTrip("utf8.sql", original, updated, StandardCharsets.UTF_8, new byte[0]);
        assertRoundTrip("utf8-bom.sql", original, updated, StandardCharsets.UTF_8, UTF_8_BOM);
        assertRoundTrip("utf16le.sql", original, updated, StandardCharsets.UTF_16LE, UTF_16_LE_BOM);
        assertRoundTrip("utf16be.sql", original, updated, StandardCharsets.UTF_16BE, UTF_16_BE_BOM);
        assertRoundTrip("gb18030.sql", original, updated, GB18030, new byte[0]);
    }

    @Test
    void shouldUseRequestedCharsetInsteadOfBomCharset() throws Exception {
        String content = "select 'caf\u00e9';";
        Path file = tempDir.resolve("manual-windows-1252.sql");
        Files.write(file, withBom(UTF_8_BOM, content.getBytes(WINDOWS_1252)));

        LocalTextFileCodec.DecodedText decoded = LocalTextFileCodec.read(file, WINDOWS_1252);

        assertEquals(content, decoded.content());
        assertEquals("windows-1252", decoded.charset());
        assertFalse(decoded.bom());
    }

    @Test
    void shouldPreserveBomWhenItMatchesRequestedCharset() throws Exception {
        String content = "select '\u4e2d\u6587';";
        Path file = tempDir.resolve("manual-utf8-bom.sql");
        Files.write(file, withBom(UTF_8_BOM, content.getBytes(StandardCharsets.UTF_8)));

        LocalTextFileCodec.DecodedText decoded = LocalTextFileCodec.read(file, StandardCharsets.UTF_8);

        assertEquals(content, decoded.content());
        assertEquals("UTF-8", decoded.charset());
        assertTrue(decoded.bom());
    }

    @Test
    void shouldRejectBytesThatCannotBeDecodedWithRequestedCharset() throws Exception {
        Path file = tempDir.resolve("invalid-utf8.sql");
        Files.write(file, new byte[]{(byte) 0x80});

        assertThrows(CharacterCodingException.class, () -> LocalTextFileCodec.read(file, StandardCharsets.UTF_8));
    }

    @Test
    void shouldDetectExistingEncodingForLegacyUpdateRequests() throws Exception {
        Path file = tempDir.resolve("legacy-gbk.sql");
        String updated = "select '\u5347\u7ea7\u540e\u4ecd\u7136\u6b63\u5e38';";
        Files.write(file, "select '\u539f\u5185\u5bb9';".getBytes(GB18030));

        OSOperateUtil.updateFileContent(file.toString(), updated);

        assertArrayEquals(updated.getBytes(GB18030), Files.readAllBytes(file));
    }

    private void assertDetected(
            String fileName,
            byte[] bytes,
            String expectedContent,
            String expectedCharset,
            boolean expectedBom
    ) throws Exception {
        Path file = tempDir.resolve(fileName);
        Files.write(file, bytes);

        Map<String, Object> result = OSOperateUtil.openLocalFile(file.toString(), null);

        assertEquals(expectedContent, result.get("content"));
        assertEquals(expectedCharset, result.get("charset"));
        assertEquals(expectedBom, result.get("bom"));
    }

    private void assertRoundTrip(
            String fileName,
            String original,
            String updated,
            Charset charset,
            byte[] bom
    ) throws Exception {
        Path file = tempDir.resolve(fileName);
        Files.write(file, withBom(bom, original.getBytes(charset)));
        Map<String, Object> opened = OSOperateUtil.openLocalFile(file.toString(), null);

        OSOperateUtil.updateFileContent(
                file.toString(),
                updated,
                Charset.forName((String) opened.get("charset")),
                (Boolean) opened.get("bom")
        );

        assertArrayEquals(withBom(bom, updated.getBytes(charset)), Files.readAllBytes(file));
    }

    private byte[] withBom(byte[] bom, byte[] content) {
        byte[] result = new byte[bom.length + content.length];
        System.arraycopy(bom, 0, result, 0, bom.length);
        System.arraycopy(content, 0, result, bom.length, content.length);
        return result;
    }
}
