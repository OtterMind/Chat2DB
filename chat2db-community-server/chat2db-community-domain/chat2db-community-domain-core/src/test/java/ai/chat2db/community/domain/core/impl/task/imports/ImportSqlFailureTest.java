package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.tools.util.I18nUtils;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import java.sql.SQLException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ImportSqlFailureTest {
    @Test
    void preservesSpecificErrorsWrappedByScriptAndSpreadsheetParsers() {
        var specific = new ai.chat2db.community.domain.api.model.task.TaskExecutionException(
                "IMPORT_FAILED", "Constraint failed", "SQLState=23000", new SQLException());
        assertSame(specific, ImportTaskErrors.from(new RuntimeException("parser wrapper", specific), "unused"));
        var cancelled = new ai.chat2db.community.domain.api.model.task.TaskCancelledException();
        assertThrows(ai.chat2db.community.domain.api.model.task.TaskCancelledException.class,
                () -> ImportTaskErrors.from(new RuntimeException(cancelled), "unused"));
    }

    @Test
    void classifiesWrappedDatabaseFailuresWithoutExposingSqlOrConnectionDetails() throws Exception {
        var field = I18nUtils.class.getDeclaredField("messageSourceStatic");
        field.setAccessible(true);
        Object previous = field.get(null);
        StaticMessageSource messages = new StaticMessageSource();
        field.set(null, messages);
        try {
            for (var entry : Map.of("23000", "constraintViolation", "22001", "invalidValue",
                    "42000", "invalidStatement", "08001", "connectionFailed", "HY000", "executionFailed").entrySet()) {
                String key = "import.sql." + entry.getValue();
                messages.addMessage(key, LocaleContextHolder.getLocale(), "Localized " + entry.getValue());
                var driver = new SQLException("secret file contents and connection password", entry.getKey(), 123);
                var error = ImportSqlExecutor.importFailure(new RuntimeException(driver));
                assertTrue(error.publicMessage().contains("Localized " + entry.getValue()));
                assertTrue(error.publicMessage().contains("SQLState=" + entry.getKey()));
                assertFalse(error.publicMessage().contains("secret"));
                assertSame(driver, error.getCause().getCause());
            }
        } finally {
            field.set(null, previous);
        }
    }
}
