package ai.chat2db.community.web.api.config.core;

import ai.chat2db.community.web.api.util.DataSourceSslRedactionUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.BodyFilter;


@Configuration
public class WebLogConfiguration {

    @Bean
    public BodyFilter bodyFilter() {
        return (contentType, body) -> DataSourceSslRedactionUtils.redactJsonBody(body);
    }
}
