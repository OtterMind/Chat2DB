package ai.chat2db.community.tools.enums;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UiLanguageEnumTest {

    @Test
    void primarySubtagSelectsTheLanguageIgnoringCaseAndSpaces() {
        assertEquals(UiLanguageEnum.KO_KR, UiLanguageEnum.of("KO-KR"));
        assertEquals(UiLanguageEnum.ES_ES, UiLanguageEnum.of(" es_ES"));
        assertEquals(UiLanguageEnum.ZH_CN, UiLanguageEnum.of("zh-TW"));
        assertEquals(UiLanguageEnum.ZH_CN, UiLanguageEnum.of("zh ,en;q=0.8"));
        assertEquals(UiLanguageEnum.JA_JP, UiLanguageEnum.of("ja,en-US;q=0.9"));
        assertEquals(UiLanguageEnum.EN_US, UiLanguageEnum.of(" EN-us "));
    }

    @Test
    void unsupportedOrMissingLanguageFallsBackToEnglish() {
        assertEquals(UiLanguageEnum.EN_US, UiLanguageEnum.of("kok-IN"));
        assertEquals(UiLanguageEnum.EN_US, UiLanguageEnum.of("fr-FR"));
        assertEquals(UiLanguageEnum.EN_US, UiLanguageEnum.of(""));
        assertEquals(UiLanguageEnum.EN_US, UiLanguageEnum.of((String) null));
    }

    @Test
    void localeLookupUsesTheLanguageOnly() {
        assertEquals(UiLanguageEnum.KO_KR, UiLanguageEnum.of(Locale.KOREAN));
        assertEquals(UiLanguageEnum.ZH_CN, UiLanguageEnum.of(Locale.TRADITIONAL_CHINESE));
        assertEquals(UiLanguageEnum.EN_US, UiLanguageEnum.of(Locale.ROOT));
        assertEquals(UiLanguageEnum.EN_US, UiLanguageEnum.of((Locale) null));
    }

    @Test
    void eachLanguageMapsToItsBundleLocale() {
        assertEquals(Locale.US, UiLanguageEnum.EN_US.getLocale());
        assertEquals(Locale.CHINA, UiLanguageEnum.ZH_CN.getLocale());
        assertEquals(Locale.JAPAN, UiLanguageEnum.JA_JP.getLocale());
        assertEquals(Locale.KOREA, UiLanguageEnum.KO_KR.getLocale());
        assertEquals(Locale.forLanguageTag("es-ES"), UiLanguageEnum.ES_ES.getLocale());
    }
}
