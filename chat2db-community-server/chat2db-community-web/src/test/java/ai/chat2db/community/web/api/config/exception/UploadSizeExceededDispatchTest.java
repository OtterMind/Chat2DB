package ai.chat2db.community.web.api.config.exception;

import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins which error code an oversized import upload produces. Import staging caps the multipart
 * request at 1 GB, so a user who exceeds it must not be told to "retry or refresh the page".
 */
class UploadSizeExceededDispatchTest {

    private static final String UPLOAD_URI = "/api/rdb/import_preview/upload";

    @Test
    void oversizedUploadIsReportedAsASizeLimitNotAGenericBusinessError() {
        ActionResult result = new EasyControllerExceptionHandler()
                .handleBusinessException(request(), new MaxUploadSizeExceededException(1024L * 1024L * 1024L));

        assertEquals("common.maxUploadSize", result.errorCode());
        assertNotEquals(I18nUtils.DEFAULT_MESSAGE_CODE, result.errorCode());
    }

    @Test
    void anUnrelatedFailureKeepsTheGenericBusinessError() {
        ActionResult result = new EasyControllerExceptionHandler()
                .handledException(request(), new IllegalStateException("unrelated failure"));

        assertEquals(I18nUtils.DEFAULT_MESSAGE_CODE, result.errorCode());
    }

    private static HttpServletRequest request() {
        return (HttpServletRequest) Proxy.newProxyInstance(
                UploadSizeExceededDispatchTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> "getRequestURI".equals(method.getName()) ? UPLOAD_URI : null);
    }
}
