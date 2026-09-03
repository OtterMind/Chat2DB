package ai.chat2db.community.web.api.adapter.file;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TaskImportUploadSizeFilter extends OncePerRequestFilter {

    static final String IMPORT_UPLOAD_PATH = "/api/tasks/import/upload";
    static final long MULTIPART_OVERHEAD_BYTES = 1024L * 1024L;

    private final long maxRequestBytes;

    @Autowired
    public TaskImportUploadSizeFilter(
            @Value("${chat2db.task.import.max-upload-bytes:536870912}") long maxFileBytes) {
        maxRequestBytes = requestLimit(maxFileBytes);
    }

    static long requestLimit(long maxFileBytes) {
        long normalizedMaxFileBytes = Math.max(1L, maxFileBytes);
        return normalizedMaxFileBytes > Long.MAX_VALUE - MULTIPART_OVERHEAD_BYTES
                ? Long.MAX_VALUE : normalizedMaxFileBytes + MULTIPART_OVERHEAD_BYTES;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isImportUploadRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!isAllowedContentLength(request.getContentLengthLong(), maxRequestBytes)) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "Task import upload exceeds the request size limit");
            return;
        }
        filterChain.doFilter(request, response);
    }

    static boolean isImportUploadRequest(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && IMPORT_UPLOAD_PATH.equals(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    }

    static boolean isAllowedContentLength(long contentLength, long maxRequestBytes) {
        return contentLength >= 0L && contentLength <= maxRequestBytes;
    }
}
