package ai.chat2db.community.web.api.adapter.db;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JdbcDriverUploadSizeFilter extends OncePerRequestFilter {

    static final String DRIVER_UPLOAD_PATH = "/api/jdbc/driver/upload";
    static final long MAX_DRIVER_UPLOAD_REQUEST_BYTES =
            MultipartJdbcDriverUploadAdapter.MAX_DRIVER_FILE_SIZE_BYTES + 1024L * 1024L;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isDriverUploadRequest(request.getMethod(), request.getRequestURI(), request.getContextPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!isAllowedContentLength(request.getContentLengthLong())) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "JDBC driver upload exceeds the request size limit");
            return;
        }
        filterChain.doFilter(request, response);
    }

    static boolean isDriverUploadPath(String requestUri, String contextPath) {
        String normalizedContextPath = contextPath == null ? "" : contextPath;
        return requestUri != null
                && stripPathParameters(requestUri).equals(stripPathParameters(normalizedContextPath)
                + DRIVER_UPLOAD_PATH);
    }

    static boolean isDriverUploadRequest(String method, String requestUri, String contextPath) {
        return "POST".equalsIgnoreCase(method) && isDriverUploadPath(requestUri, contextPath);
    }

    static boolean isAllowedContentLength(long contentLength) {
        return contentLength >= 0 && contentLength <= MAX_DRIVER_UPLOAD_REQUEST_BYTES;
    }

    private static String stripPathParameters(String path) {
        StringBuilder stripped = new StringBuilder(path.length());
        boolean inPathParameters = false;
        for (int index = 0; index < path.length(); index++) {
            char character = path.charAt(index);
            if (character == ';') {
                inPathParameters = true;
            } else if (character == '/') {
                inPathParameters = false;
                stripped.append(character);
            } else if (!inPathParameters) {
                stripped.append(character);
            }
        }
        return stripped.toString();
    }
}
