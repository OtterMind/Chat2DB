package ai.chat2db.community.web.api.aspect.connection;

import ai.chat2db.community.web.api.util.ApplicationContextUtil;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionInfoHandlerLoggingTest {

    @Test
    void logsWhenCustomConnectionResolutionFails() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ConnectionInfoHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ApplicationContext original = ApplicationContextUtil.getApplicationContext();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("failingCustomConnection", ICustomConnection.class,
                    () -> (datasourceId, databaseName, schemaName, consolerId) -> {
                        throw new IllegalStateException("boom");
                    });
            context.refresh();
            new ApplicationContextUtil().setApplicationContext(context);

            Method method = ConnectionInfoHandler.class.getDeclaredMethod(
                    "customConnectionInfo", Long.class, String.class, Long.class, String.class);
            method.setAccessible(true);
            method.invoke(new ConnectionInfoHandler(), 1L, "db", null, null);

            assertTrue(appender.list.stream().anyMatch(event ->
                            event.getLevel() == Level.WARN
                                    && event.getFormattedMessage().contains("custom connection")),
                    "failures from custom connection resolution must be logged, not swallowed");
        } finally {
            logger.detachAppender(appender);
            new ApplicationContextUtil().setApplicationContext(original);
        }
    }
}
