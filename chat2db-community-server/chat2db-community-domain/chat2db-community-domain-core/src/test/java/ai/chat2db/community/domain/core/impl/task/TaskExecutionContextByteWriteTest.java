package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskExecutionContextByteWriteTest {

    @TempDir
    Path tempDirectory;

    @Test
    void byteWritesKeepTheirPositionBetweenTextWrites() throws IOException {
        TaskExecutionContextImpl context = context(1L);
        ArtifactDraft draft = context.createArtifact(tempDirectory.toString(), "export.csv", "text/csv");
        byte[] binary = "二进制\r\n".getBytes(StandardCharsets.UTF_8);

        context.write("header");
        context.writeBytes(binary, 0, binary.length);
        context.write("tail");
        context.finishArtifactWrites();

        byte[] lineSeparator = System.lineSeparator().getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(join("header".getBytes(StandardCharsets.UTF_8), lineSeparator, binary,
                "tail".getBytes(StandardCharsets.UTF_8), lineSeparator),
                Files.readAllBytes(draft.getTemporaryFile().toPath()));
    }

    @Test
    void byteWritesHonorOffsetAndLength() throws IOException {
        TaskExecutionContextImpl context = context(2L);
        ArtifactDraft draft = context.createArtifact(tempDirectory.toString(), "binary.bin", "application/octet-stream");
        byte[] source = "..中文..".getBytes(StandardCharsets.UTF_8);

        context.writeBytes(source, 2, 6);
        context.finishArtifactWrites();

        assertEquals("中文", Files.readString(draft.getTemporaryFile().toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void byteWritesBeforeArtifactCreationFail() {
        TaskExecutionContextImpl context = context(3L);

        assertThrows(IllegalStateException.class, () -> context.writeBytes(new byte[] {1, 2}, 0, 2));
    }

    @Test
    void contextsWithoutByteSupportRejectByteWrites() {
        TaskExecutionContext context = new TextOnlyContext();

        assertThrows(UnsupportedOperationException.class,
                () -> context.writeBytes(new byte[] {1}, 0, 1));
    }

    private TaskExecutionContextImpl context(Long taskId) {
        TaskStorage storage = (TaskStorage) Proxy.newProxyInstance(
                TaskExecutionContextByteWriteTest.class.getClassLoader(),
                new Class<?>[] {TaskStorage.class},
                (proxy, method, args) -> null);
        return new TaskExecutionContextImpl(taskId, new RunningTask(taskId), storage, new ArtifactServiceImpl());
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            output.write(part, 0, part.length);
        }
        return output.toByteArray();
    }

    private static final class TextOnlyContext implements TaskExecutionContext {

        @Override
        public void reportProgress(int progress, String stage, String message) {
        }

        @Override
        public void logInfo(String code, String message) {
        }

        @Override
        public void logInfo(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void logWarn(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void logError(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void checkCancelled() {
        }

        @Override
        public void registerCancelable(TaskCancelable resource) {
        }

        @Override
        public ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) {
            return null;
        }

        @Override
        public void write(String content) {
        }

        @Override
        public void onStatementCreated(Statement statement) {
        }

        @Override
        public void onStatementClosed(Statement statement) {
        }
    }
}
