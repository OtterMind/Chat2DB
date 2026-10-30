package ai.chat2db.community.web.api.converter.task;

import ai.chat2db.community.domain.api.model.task.TaskDownload;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;

/**
 * Serves a published task artifact as a file download.
 *
 * <p>Resumable downloads are provided by Spring MVC itself for {@code ResponseEntity<Resource>}
 * bodies: a request with {@code Range} is answered with 206 plus {@code Content-Range} and the
 * requested bytes (multiple ranges become {@code multipart/byteranges}), an unsatisfiable range
 * with 416 plus a {@code Content-Range} header stating the full length, and every response carries
 * {@code Accept-Ranges: bytes}. A request without {@code Range} is answered with 200 and the
 * resource's content length. {@code TaskDownloadWebConverterTest} locks that contract in place,
 * so it must not be re-implemented here.
 */
@Component
public class TaskDownloadWebConverter {

    public ResponseEntity<Resource> toResponse(TaskDownload download) {
        try {
            Resource resource = new UrlResource(download.getFileUri());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.getFileName() + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Invalid task download URI", e);
        }
    }
}
