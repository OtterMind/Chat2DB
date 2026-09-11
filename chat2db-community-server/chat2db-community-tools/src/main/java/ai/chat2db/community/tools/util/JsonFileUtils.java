package ai.chat2db.community.tools.util;

import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson2.JSON;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * UTF-8 JSON file operations. Callers serialize writes and read-modify-write operations for each file.
 */
public final class JsonFileUtils {

    private JsonFileUtils() {
    }

    /** Returns an empty list for a missing file; malformed content is never treated as an empty list. */
    public static <T> List<T> readArray(File file, Class<T> elementType) {
        if (!file.exists()) {
            return new ArrayList<>();
        }
        List<T> values = JSON.parseArray(FileUtil.readUtf8String(file), elementType);
        if (values == null) {
            throw new IllegalStateException("Expected a JSON array in " + file);
        }
        return values;
    }

    /** Replaces the target after writing a complete JSON document, using an atomic move when supported. */
    public static void writeAtomically(File file, Object value) throws IOException {
        FileUtil.mkParentDirs(file);
        Path temporary = file.toPath().resolveSibling(file.getName() + ".part");
        Files.writeString(temporary, JSON.toJSONString(value));
        try {
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
