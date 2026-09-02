package ai.chat2db.community.domain.core.impl.task.export;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Applies the export flow-control configuration once at startup.
 */
@Component
public class ExportRateLimiterConfiguration {

    public ExportRateLimiterConfiguration(
            @Value("${chat2db.export.rate.rows-per-second:0}") long rowsPerSecond,
            @Value("${chat2db.export.rate.bytes-per-second:0}") long bytesPerSecond) {
        ExportRateLimiter.configure(rowsPerSecond, bytesPerSecond);
    }
}
