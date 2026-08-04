package ai.chat2db.community.web.api.config.console;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsoleHelperLocaleTest {

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
    void otherAcceptLanguageFallsBackToUsLocale() {
        assertLocaleFor("en-US,en;q=0.9", Locale.US);
    }
}
