package ai.chat2db.community.web.api.config.console;

import ai.chat2db.community.tools.console.ConsoleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Collections;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConsoleHelperLocaleTest {

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private void assertLocaleFor(String acceptLanguage, Locale expected) {
        assertEquals(expected, ConsoleHelper.resolveLocale(acceptLanguage));
    }

    @Test
    void japaneseAcceptLanguageResolvesToJapanLocale() {
        assertLocaleFor("ja-JP,ja;q=0.9", Locale.JAPAN);
    }

    @Test
    void chineseAcceptLanguageResolvesToChinaLocale() {
        assertLocaleFor("zh-CN,zh;q=0.9", Locale.CHINA);
    }

    @Test
    void koreanAcceptLanguageResolvesToKoreaLocale() {
        assertLocaleFor("ko-KR,ko;q=0.9,en-US;q=0.8", Locale.KOREA);
    }

    @Test
    void spanishAcceptLanguageResolvesToSpainLocale() {
        assertLocaleFor("es-ES,es;q=0.9", Locale.forLanguageTag("es-ES"));
    }

    @Test
    void acceptLanguageIsMatchedIgnoringCaseAndSurroundingSpaces() {
        assertLocaleFor("KO-KR", Locale.KOREA);
        assertLocaleFor(" es-ES", Locale.forLanguageTag("es-ES"));
        assertLocaleFor("ZH-cn", Locale.CHINA);
        assertLocaleFor("JA", Locale.JAPAN);
        assertLocaleFor(" EN-US ", Locale.US);
    }

    @Test
    void otherAcceptLanguageFallsBackToUsLocale() {
        assertLocaleFor("en-US,en;q=0.9", Locale.US);
        assertLocaleFor("fr-FR", Locale.US);
        assertLocaleFor(null, Locale.US);
    }

    @Test
    void messageWithoutAcceptLanguageDoesNotKeepThePreviousLocale() {
        LocaleContextHolder.setLocale(Locale.KOREA);
        ConsoleMessage message = new ConsoleMessage();
        message.setHeaders(Collections.emptyMap());

        ConsoleHelper.setHeaders(message);

        assertNull(LocaleContextHolder.getLocaleContext());
    }

    @Test
    void messageWithoutHeadersDoesNotKeepThePreviousLocale() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("es-ES"));

        ConsoleHelper.setHeaders(new ConsoleMessage());

        assertNull(LocaleContextHolder.getLocaleContext());
    }
}
