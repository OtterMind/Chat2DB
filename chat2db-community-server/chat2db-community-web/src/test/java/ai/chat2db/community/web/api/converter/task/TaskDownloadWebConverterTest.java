package ai.chat2db.community.web.api.converter.task;

import ai.chat2db.community.domain.api.model.task.TaskDownload;
import ai.chat2db.community.domain.api.service.task.TaskService;
import ai.chat2db.community.web.api.controller.TaskController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Locks the download contract of the artifact endpoint. Range support comes from Spring MVC's own
 * {@code ResponseEntity<Resource>} handling (verified: 206 with Content-Range, 416 with a
 * Content-Range header stating the full length, Accept-Ranges and Content-Length on every
 * response); the
 * converter only adds the attachment headers. Re-implementing Range handling in the converter
 * would fight that built-in behaviour, so these tests stay at the HTTP layer.
 */
class TaskDownloadWebConverterTest {

    private static final long TASK_ID = 7L;

    private static final int CONTENT_LENGTH = 4096;

    @TempDir
    Path tempDirectory;

    private byte[] artifactContent;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws IOException {
        artifactContent = new byte[CONTENT_LENGTH];
        for (int index = 0; index < artifactContent.length; index++) {
            artifactContent[index] = (byte) (index % 251);
        }
        Path artifact = tempDirectory.resolve("export.csv");
        Files.write(artifact, artifactContent);

        TaskService taskService = mock(TaskService.class);
        when(taskService.resolveArtifact(TASK_ID)).thenReturn(TaskDownload.builder()
                .fileName("export.csv")
                .fileUri(artifact.toUri().toString())
                .build());
        TaskController controller = new TaskController(taskService, null, new TaskDownloadWebConverter(), null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void fullDownloadReturnsOkWithContentLength() throws Exception {
        mockMvc.perform(get("/api/tasks/artifact").param("taskId", String.valueOf(TASK_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"export.csv\""))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, CONTENT_LENGTH))
                .andExpect(content().bytes(artifactContent));
    }

    @Test
    void rangeRequestReturnsPartialContentWithTheRequestedBytes() throws Exception {
        mockMvc.perform(get("/api/tasks/artifact").param("taskId", String.valueOf(TASK_ID))
                        .header(HttpHeaders.RANGE, "bytes=0-1023"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-1023/4096"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 1024))
                .andExpect(content().bytes(Arrays.copyOfRange(artifactContent, 0, 1024)));
    }

    @Test
    void rangeRequestSkipsToTheStartOffset() throws Exception {
        mockMvc.perform(get("/api/tasks/artifact").param("taskId", String.valueOf(TASK_ID))
                        .header(HttpHeaders.RANGE, "bytes=100-199"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 100-199/4096"))
                .andExpect(content().bytes(Arrays.copyOfRange(artifactContent, 100, 200)));
    }

    @Test
    void suffixRangeReturnsTheLastBytes() throws Exception {
        mockMvc.perform(get("/api/tasks/artifact").param("taskId", String.valueOf(TASK_ID))
                        .header(HttpHeaders.RANGE, "bytes=-100"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 3996-4095/4096"))
                .andExpect(content().bytes(Arrays.copyOfRange(artifactContent, 3996, CONTENT_LENGTH)));
    }

    @Test
    void unsatisfiableRangeReturnsRequestedRangeNotSatisfiable() throws Exception {
        mockMvc.perform(get("/api/tasks/artifact").param("taskId", String.valueOf(TASK_ID))
                        .header(HttpHeaders.RANGE, "bytes=999999999-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */4096"));
    }

    @Test
    void multipleRangesUseTheFrameworkMultipartResponse() throws Exception {
        // The desktop and web download clients only send a single range; Spring MVC additionally
        // answers multi range requests correctly, which is asserted here so a framework change is
        // noticed instead of silently breaking the endpoint.
        mockMvc.perform(get("/api/tasks/artifact").param("taskId", String.valueOf(TASK_ID))
                        .header(HttpHeaders.RANGE, "bytes=0-9,20-29"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("multipart/byteranges")));
    }
}
