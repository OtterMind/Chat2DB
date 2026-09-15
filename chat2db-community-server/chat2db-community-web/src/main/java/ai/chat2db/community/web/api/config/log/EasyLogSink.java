package ai.chat2db.community.web.api.config.log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;

import ai.chat2db.community.tools.util.LogUtils;
import ai.chat2db.community.web.api.util.DataSourceSslRedactionUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.thymeleaf.util.ContentTypeUtils;
import org.zalando.logbook.Correlation;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.HttpResponse;
import org.zalando.logbook.Precorrelation;
import org.zalando.logbook.Sink;


@Slf4j
@Component
public class EasyLogSink implements Sink {

    @Override
    public void write(final Precorrelation precorrelation, final HttpRequest request) {
    }

    @Override
    public void write(final Correlation correlation, final HttpRequest request, final HttpResponse response) {
        try {
            printLog(correlation, request, response);
        } catch (Exception e) {
            log.error("Failed to record web log", e);
        }
    }

    public void printLog(final Correlation correlation, final HttpRequest request, final HttpResponse response)
        throws IOException {
        WebLog webLog = new WebLog();

        String method = request.getMethod();
        String path = request.getPath();

        webLog.setMethod(method);
        webLog.setPath(LogUtils.cutLog(path));
        webLog.setQuery(LogUtils.cutLog(request.getQuery()));
        webLog.setDuration(correlation.getDuration().toMillis());
        webLog.setStartTime(LocalDateTime.ofInstant(correlation.getStart(), ZoneId.systemDefault()));
        webLog.setEndTime(LocalDateTime.ofInstant(correlation.getEnd(), ZoneId.systemDefault()));
        try {
            String requestBody = DataSourceSslRedactionUtils.redactJsonBody(
                    new String(request.getBody(), StandardCharsets.UTF_8));
            webLog.setRequest(LogUtils.maskString(LogUtils.cutLog(requestBody)));
            if (ContentTypeUtils.isContentTypeJSON(response.getContentType()) || ContentTypeUtils.isContentTypeHTML(
                response.getContentType())) {
                String responseBody = DataSourceSslRedactionUtils.redactJsonBody(
                        new String(response.getBody(), StandardCharsets.UTF_8));
                webLog.setResponse(LogUtils.maskString(LogUtils.cutLog(responseBody)));
            } else {
                webLog.setResponse(response.getContentType() + ":[" + response.getBody().length + "]");
            }
        } catch (IOException e) {
            log.warn("Failed to read log request or response body, probably because the client closed the stream", e);
        }
        webLog.setIp(LogUtils.getClientIp(request));

        String pathAndQuery = path;
        if (StringUtils.isNotBlank(webLog.getQuery())) {
            pathAndQuery += "?" + webLog.getQuery();
        }
        log.info("http : {}|{}|{}|{}|{}", webLog.getMethod(), pathAndQuery, webLog.getDuration(),
            webLog.getRequest(), webLog.getResponse());
    }

}
