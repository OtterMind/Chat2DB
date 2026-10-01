package ai.chat2db.community.tools.util;

import java.util.Locale;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;


@Slf4j
@Component
@Lazy(value = false)
public class I18nUtils implements InitializingBean {
    public static final String DEFAULT_MESSAGE_CODE="common.systemError";
    @Resource
    private MessageSource messageSource;
    private static MessageSource messageSourceStatic;

    public static String getMessage(String messageCode) {
        return getMessage(messageCode, null);
    }

    public static String getMessage(String messageCode, @Nullable Object[] args) {
        MessageSource source = messageSourceStatic;
        if (source == null) {
            return messageCode;
        }
        try {
            return source.getMessage(messageCode, args, LocaleContextHolder.getLocale());
        } catch (NoSuchMessageException e) {
            return messageCode + " : no message.";
        }
    }

    public static String getMessageByLang(String messageCode, Locale locale) {
        MessageSource source = messageSourceStatic;
        if (source == null) {
            return messageCode;
        }
        try {
            return source.getMessage(messageCode, null, locale);
        } catch (NoSuchMessageException e) {
            // Fall back to the localized generic message rather than leaking the raw key, which
            // would reach the user as an untranslated identifier.
            return source.getMessage(DEFAULT_MESSAGE_CODE, null, locale);
        }
    }


    public static Boolean isEn() {
        return LocaleContextHolder.getLocale().equals(Locale.US);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        messageSourceStatic = messageSource;
    }
}