package ai.chat2db.community.tools.enums;

import java.util.Locale;

import lombok.Getter;

/**
 * UI languages that have a message bundle. The description is the English language name.
 */
@Getter
public enum UiLanguageEnum implements IBaseEnum<String> {

    EN_US(Locale.US, "English"),

    ZH_CN(Locale.CHINA, "Simplified Chinese"),

    JA_JP(Locale.JAPAN, "Japanese"),

    KO_KR(Locale.KOREA, "Korean"),

    ES_ES(Locale.forLanguageTag("es-ES"), "Spanish"),

    ;

    final Locale locale;

    final String description;

    UiLanguageEnum(Locale locale, String description) {
        this.locale = locale;
        this.description = description;
    }

    @Override
    public String getCode() {
        return locale.getLanguage();
    }

    /**
     * Returns the language matching the primary subtag of the given value, ignoring case and surrounding
     * spaces, so a language code, a locale or an Accept-Language header all work. Falls back to {@link #EN_US}.
     */
    public static UiLanguageEnum of(String language) {
        if (language == null) {
            return EN_US;
        }
        String primary = language.trim().toLowerCase(Locale.ROOT).split("[-_,;]", 2)[0].trim();
        for (UiLanguageEnum value : values()) {
            if (value.getCode().equals(primary)) {
                return value;
            }
        }
        return EN_US;
    }

    public static UiLanguageEnum of(Locale locale) {
        return locale == null ? EN_US : of(locale.getLanguage());
    }
}
