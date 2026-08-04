package ai.chat2db.community.jcef.utils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

public final class LocalTextFileCodec {

    private static final Charset WINDOWS_CHINESE_CHARSET = Charset.forName("GB18030");
    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] UTF_16_LE_BOM = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] UTF_16_BE_BOM = {(byte) 0xFE, (byte) 0xFF};
    private static final int CHUNK_SIZE = 1024 * 1024;

    private LocalTextFileCodec() {
    }

    public static DecodedText read(Path path, Charset requestedCharset) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        Encoding encoding = detectEncoding(bytes, requestedCharset);
        String content = decode(bytes, encoding.bomLength(), encoding.charset());
        return new DecodedText(content, encoding.charset().name(), encoding.bomLength() > 0);
    }

    public static void write(Path path, String content, Charset charset, boolean includeBom) throws IOException {
        Path absolutePath = path.toAbsolutePath();
        Path tempPath = Files.createTempFile(absolutePath.getParent(), ".chat2db-update-", ".tmp");
        try {
            byte[] bom = includeBom ? bomFor(charset) : new byte[0];
            CharsetEncoder encoder = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try (OutputStream outputStream = Files.newOutputStream(tempPath)) {
                outputStream.write(bom);
                try (Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream, encoder))) {
                    for (int i = 0; i < content.length(); i += CHUNK_SIZE) {
                        int end = Math.min(content.length(), i + CHUNK_SIZE);
                        writer.write(content, i, end - i);
                    }
                }
            }
            Files.move(tempPath, absolutePath, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }

    private static Encoding detectEncoding(byte[] bytes, Charset requestedCharset) throws CharacterCodingException {
        if (startsWith(bytes, UTF_8_BOM)) {
            return new Encoding(StandardCharsets.UTF_8, UTF_8_BOM.length);
        }
        if (startsWith(bytes, UTF_16_LE_BOM)) {
            return new Encoding(StandardCharsets.UTF_16LE, UTF_16_LE_BOM.length);
        }
        if (startsWith(bytes, UTF_16_BE_BOM)) {
            return new Encoding(StandardCharsets.UTF_16BE, UTF_16_BE_BOM.length);
        }
        if (requestedCharset != null) {
            decode(bytes, 0, requestedCharset);
            return new Encoding(requestedCharset, 0);
        }
        if (canDecode(bytes, StandardCharsets.UTF_8)) {
            return new Encoding(StandardCharsets.UTF_8, 0);
        }
        decode(bytes, 0, WINDOWS_CHINESE_CHARSET);
        return new Encoding(WINDOWS_CHINESE_CHARSET, 0);
    }

    private static boolean canDecode(byte[] bytes, Charset charset) {
        try {
            decode(bytes, 0, charset);
            return true;
        } catch (CharacterCodingException ignored) {
            return false;
        }
    }

    private static String decode(byte[] bytes, int offset, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
    }

    private static byte[] bomFor(Charset charset) {
        if (StandardCharsets.UTF_8.equals(charset)) {
            return Arrays.copyOf(UTF_8_BOM, UTF_8_BOM.length);
        }
        if (StandardCharsets.UTF_16LE.equals(charset)) {
            return Arrays.copyOf(UTF_16_LE_BOM, UTF_16_LE_BOM.length);
        }
        if (StandardCharsets.UTF_16BE.equals(charset)) {
            return Arrays.copyOf(UTF_16_BE_BOM, UTF_16_BE_BOM.length);
        }
        throw new IllegalArgumentException("BOM is not supported for charset: " + charset.name());
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private record Encoding(Charset charset, int bomLength) {
    }

    public record DecodedText(String content, String charset, boolean bom) {
    }
}
