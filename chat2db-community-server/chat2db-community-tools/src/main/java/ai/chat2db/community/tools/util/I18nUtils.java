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
        MessageSource messageSource = messageSourceStatic;
        if (messageSource == null) {
            return messageCode + " : no message.";
        }
        try {
            return messageSource.getMessage(messageCode, args, LocaleContextHolder.getLocale());
        } catch (NoSuchMessageException e) {
            return messageCode + " : no message.";
        }
    }

    public static String getMessageByLang(String messageCode, Locale locale) {
        MessageSource messageSource = messageSourceStatic;
        if (messageSource == null) {
            return messageCode + " : no message.";
        }
        try {
            return messageSource.getMessage(messageCode, null, locale);
        } catch (NoSuchMessageException e) {
            return messageSource.getMessage(DEFAULT_MESSAGE_CODE, null, locale);
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
