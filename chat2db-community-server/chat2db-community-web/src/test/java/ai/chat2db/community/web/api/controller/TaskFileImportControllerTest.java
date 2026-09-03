package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskService;
import ai.chat2db.community.web.api.adapter.file.TaskImportUploadService;
import ai.chat2db.community.web.api.converter.task.TaskWebConverter;
import ai.chat2db.community.web.api.model.request.task.TaskImportRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskFileImportControllerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void multipartJsonRequestAndUploadedFileReachTaskSubmission() throws Exception {
        byte[] contents = "id,name\n1,Ada\n".getBytes();
        AtomicReference<ImportTaskSpec> submittedSpec = new AtomicReference<>();
        AtomicReference<Path> stagedSource = new AtomicReference<>();
        TaskService taskService = taskService(spec -> {
            submittedSpec.set(spec);
            return 73L;
        });
        TaskFileImportController controller = controller(taskService, stagedSource);
        TaskImportRequest request = request();
        request.setSourceFile("C:\\browser-fake-path\\people.csv");
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        MockMultipartFile requestPart = new MockMultipartFile("request", "request.json",
                MediaType.APPLICATION_JSON_VALUE, new ObjectMapper().writeValueAsBytes(request));
        MockMultipartFile filePart = new MockMultipartFile("file", "people.csv", "text/csv", contents);

        mockMvc.perform(multipart("/api/tasks/import/upload").file(requestPart).file(filePart))
                .andExpect(status().isOk());

        ImportTaskSpec spec = submittedSpec.get();
        assertTrue(spec.isTemporarySourceFile());
        assertEquals("cleanup-token", spec.getTemporarySourceToken());
        assertEquals("people.csv", spec.getDisplayFileName());
        assertNotEquals(request.getSourceFile(), spec.getSourceFile());
        assertEquals(stagedSource.get().toAbsolutePath().toString(), spec.getSourceFile());
        assertArrayEquals(contents, Files.readAllBytes(Path.of(spec.getSourceFile())));

        Files.deleteIfExists(Path.of(spec.getSourceFile()));
    }

    @Test
    void rejectedTaskSubmissionDeletesTheStagedUpload() {
        AtomicReference<Path> stagedSource = new AtomicReference<>();
        TaskService taskService = taskService(spec -> {
            throw new IllegalStateException("rejected");
        });
        TaskFileImportController controller = controller(taskService, stagedSource);

        assertThrows(IllegalStateException.class, () -> controller.submitImport(request(),
                multipartFile("people.csv", "text/csv", "id\n1\n".getBytes())));
        assertFalse(Files.exists(stagedSource.get()));
    }

    @Test
    void rejectedSubmissionDelegatesFailedCleanupToTheDurableUploadService() throws IOException {
        Path source = Files.writeString(temporaryDirectory.resolve("task-import-retry.tmp"), "data");
        AtomicBoolean cleanupCalled = new AtomicBoolean();
        TaskImportUploadService uploadService = new TaskImportUploadService() {
            @Override
            public StagedTaskInput stage(MultipartFile file) {
                return new StagedTaskInput(source.toString(), "durable-token");
            }

            @Override
            public boolean cleanup(StagedTaskInput input) {
                cleanupCalled.set(true);
                return false;
            }
        };
        TaskService taskService = taskService(spec -> {
            throw new IllegalStateException("rejected");
        });
        TaskFileImportController controller =
                new TaskFileImportController(taskService, new TaskWebConverter(), uploadService);

        assertThrows(IllegalStateException.class, () -> controller.submitImport(request(),
                multipartFile("people.csv", "text/csv", "id\n1\n".getBytes())));

        assertTrue(cleanupCalled.get());
        assertTrue(Files.exists(source));
    }

    private TaskFileImportController controller(TaskService taskService, AtomicReference<Path> stagedSource) {
        TaskImportUploadService uploadFileService = new TaskImportUploadService() {
            @Override
            public StagedTaskInput stage(MultipartFile file) {
                try {
                    Path target = Files.createTempFile(temporaryDirectory, "task-import-", ".tmp");
                    Files.write(target, file.getBytes());
                    stagedSource.set(target);
                    return new StagedTaskInput(target.toString(), "cleanup-token");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public boolean cleanup(StagedTaskInput input) {
                try {
                    return Files.deleteIfExists(Path.of(input.sourceFile()));
                } catch (IOException e) {
                    return false;
                }
            }
        };
        return new TaskFileImportController(taskService, new TaskWebConverter(), uploadFileService);
    }

    private TaskImportRequest request() {
        TaskImportRequest request = new TaskImportRequest();
        request.setDataSourceId(42L);
        request.setDatabaseName("app");
        request.setSchemaName("public");
        request.setTableName("people");
        request.setTaskType("DATA_FILE_IMPORT");
        request.setFormat("CSV");
        return request;
    }

    private TaskService taskService(ImportSubmission submission) {
        return (TaskService) Proxy.newProxyInstance(TaskService.class.getClassLoader(),
                new Class<?>[]{TaskService.class}, (proxy, method, args) -> {
                    if ("submitImport".equals(method.getName())) {
                        return submission.submit((ImportTaskSpec) args[0]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private MultipartFile multipartFile(String fileName, String contentType, byte[] contents) {
        return new MultipartFile() {
            @Override
            public String getName() {
                return "file";
            }

            @Override
            public String getOriginalFilename() {
                return fileName;
            }

            @Override
            public String getContentType() {
                return contentType;
            }

            @Override
            public boolean isEmpty() {
                return contents.length == 0;
            }

            @Override
            public long getSize() {
                return contents.length;
            }

            @Override
            public byte[] getBytes() {
                return contents.clone();
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(contents);
            }

            @Override
            public void transferTo(File destination) throws IOException {
                Files.write(destination.toPath(), contents);
            }
        };
    }

    @FunctionalInterface
    private interface ImportSubmission {
        Long submit(ImportTaskSpec spec);
    }
}
