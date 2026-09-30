package ai.chat2db.community.tools.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Contract coverage for the non-Spring degradation path of {@link I18nUtils}.
 *
 * <p>{@code messageSourceStatic} is only assigned by Spring through {@code afterPropertiesSet}.
 * Callers outside a Spring context — task executors, connection bootstrap, and integration tests
 * that talk to a real database — reach {@link I18nUtils#getMessage} before that happens. It must
 * return the code instead of throwing, otherwise the resulting {@link NullPointerException} masks
 * the real error and makes the test JVM order-dependent.
 */
class I18nUtilsTest {

    @AfterEach
    void clearMessageSource() throws Exception {
        setMessageSource(null);
    }

    @Test
    void getMessageFallsBackToTheCodeWhenTheMessageSourceIsUnavailable() throws Exception {
        setMessageSource(null);

        assertEquals("sqlResult.success", I18nUtils.getMessage("sqlResult.success"));
        assertEquals("sqlResult.success", I18nUtils.getMessage("sqlResult.success", new Object[]{"a"}));
    }

    @Test
    void getMessageByLangFallsBackToTheCodeWhenTheMessageSourceIsUnavailable() throws Exception {
        setMessageSource(null);

        assertEquals("sqlResult.success", I18nUtils.getMessageByLang("sqlResult.success", Locale.US));
    }

    @Test
    void aRegisteredMessageSourceStillWins() throws Exception {
        setMessageSource(new StubMessageSource(Set.of()));

        assertEquals("localized:sqlResult.success", I18nUtils.getMessage("sqlResult.success"));
        assertEquals("localized:sqlResult.success", I18nUtils.getMessageByLang("sqlResult.success", Locale.US));
    }

    @Test
    void anUnknownCodeKeepsTheExistingMissingMessageMarker() throws Exception {
        setMessageSource(new StubMessageSource(Set.of("missing.code")));

        assertEquals("missing.code : no message.", I18nUtils.getMessage("missing.code"));
    }

    private static void setMessageSource(MessageSource source) throws Exception {
        Field field = I18nUtils.class.getDeclaredField("messageSourceStatic");
        field.setAccessible(true);
        field.set(null, source);
    }

    private static final class StubMessageSource implements MessageSource {

        private final Set<String> unknownCodes;

        private StubMessageSource(Set<String> unknownCodes) {
            this.unknownCodes = unknownCodes;
        }

        @Override
        public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
            return defaultMessage == null ? "localized:" + code : defaultMessage;
        }

        @Override
        public String getMessage(String code, Object[] args, Locale locale) {
            if (unknownCodes.contains(code)) {
                throw new NoSuchMessageException(code);
            }
            return "localized:" + code;
        }

        @Override
        public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
            String[] codes = resolvable.getCodes();
            return codes == null || codes.length == 0 ? resolvable.getDefaultMessage() : codes[0];
        }
    }
}
