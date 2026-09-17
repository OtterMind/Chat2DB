package ai.chat2db.community.domain.core.impl.task.imports.reader;

import ai.chat2db.community.tools.exception.BusinessException;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Validates decoding before SQL execution and provides a BOM-free UTF-8 input. */
public final class ImportTextFile {
    public static Path utf8Copy(File file, String encoding, Runnable checkCancelled) throws IOException {
        Charset charset = charset(file.toPath(), encoding, checkCancelled);
        Path result = Files.createTempFile("chat2db-import-", ".sql");
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), charset);
             var writer = Files.newBufferedWriter(result, StandardCharsets.UTF_8)) {
            int first = reader.read();
            if (first != -1 && first != '\uFEFF') writer.write(first);
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                checkCancelled.run();
                writer.write(buffer, 0, count);
            }
        } catch (java.nio.charset.CharacterCodingException e) {
            Files.deleteIfExists(result);
            throw new BusinessException("import.sql.encodingMismatch", new Object[]{encoding}, e);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(result);
            throw e;
        }
        return result;
    }

    private static Charset charset(Path file, String encoding, Runnable checkCancelled) throws IOException {
        if (!"AUTO".equals(encoding)) return Charset.forName(encoding);
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] bom = stream.readNBytes(3);
            if (bom.length >= 2 && (bom[0] & 255) == 255 && (bom[1] & 255) == 254) return StandardCharsets.UTF_16LE;
            if (bom.length >= 2 && (bom[0] & 255) == 254 && (bom[1] & 255) == 255) return StandardCharsets.UTF_16BE;
            if (bom.length == 3 && (bom[0] & 255) == 239 && (bom[1] & 255) == 187 && (bom[2] & 255) == 191) return StandardCharsets.UTF_8;
        }
        for (String candidate : List.of("UTF-8", "GB18030", "windows-1252", "ISO-8859-1")) {
            Charset charset = Charset.forName(candidate);
            try (var reader = Files.newBufferedReader(file, charset)) {
                char[] buffer = new char[8192];
                while (reader.read(buffer) != -1) checkCancelled.run();
                return charset;
            } catch (java.nio.charset.CharacterCodingException ignored) {
                // Try the next supported encoding only for a decoding error.
            }
        }
        throw new BusinessException("import.preview.invalidEncoding", new Object[]{encoding});
    }
}
