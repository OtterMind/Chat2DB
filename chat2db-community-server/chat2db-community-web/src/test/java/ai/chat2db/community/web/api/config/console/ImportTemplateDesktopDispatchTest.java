package ai.chat2db.community.web.api.config.console;

import ai.chat2db.community.domain.api.model.imports.ImportTemplate;
import ai.chat2db.community.domain.api.service.imports.IImportTemplateService;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.web.api.controller.DbImportTemplateController;
import ai.chat2db.community.web.api.converter.db.DbImportTemplateWebConverter;
import ai.chat2db.community.web.api.util.ApplicationContextUtil;
import ai.chat2db.community.web.api.util.RequestMappingUtils;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class ImportTemplateDesktopDispatchTest {
    @Test
    void desktopCanSaveListApplyOptionsAndDeleteThroughTheRealDispatcher() throws Exception {
        ApplicationContext previous = ApplicationContextUtil.getApplicationContext();
        resetMappings();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            IImportTemplateService service = mock(IImportTemplateService.class);
            List<ImportTemplate> saved = new ArrayList<>();
            when(service.upsert(any())).thenAnswer(call -> {
                saved.clear();
                saved.add(call.getArgument(0));
                return List.copyOf(saved);
            });
            when(service.list(isNull())).thenAnswer(call -> List.copyOf(saved));
            when(service.list("CSV")).thenAnswer(call -> List.copyOf(saved));
            when(service.delete(anyString(), anyString())).thenAnswer(call -> {
                saved.clear();
                return List.of();
            });
            context.registerBean(IImportTemplateService.class, () -> service);
            context.registerBean(DbImportTemplateWebConverter.class);
            context.registerBean(DbImportTemplateController.class);
            context.refresh();
            new ApplicationContextUtil().setApplicationContext(context);

            Map<String, Object> csv = Map.of("encoding", "GBK", "delimiter", ";", "newline", "CRLF");
            Map<String, Object> payload = Map.of("name", "manual-gbk-semicolon", "createdAt", 123L,
                    "fileType", "CSV", "formatOptions", Map.of("csvOptions", csv));
            Map<String, Object> result = dispatch("POST", payload);
            assertEquals(true, result.get("success"), result.toString());
            assertEquals(1, ((List<?>) result.get("data")).size());
            assertEquals("manual-gbk-semicolon", saved.get(0).getName());
            assertEquals(Map.of("csvOptions", csv), saved.get(0).getFormatOptions());

            assertEquals(true, dispatch("GET", Map.of()).get("success"));
            Map<String, Object> listed = dispatch("GET", Map.of("fileType", "CSV"));
            assertEquals(true, listed.get("success"), listed.toString());
            assertEquals(1, ((List<?>) listed.get("data")).size());
            Map<String, Object> deleted = dispatch("DELETE", Map.of("name", "manual-gbk-semicolon", "fileType", "CSV"));
            assertEquals(true, deleted.get("success"), deleted.toString());
            assertTrue(((List<?>) deleted.get("data")).isEmpty());
            assertTrue(saved.isEmpty());
        } finally {
            new ApplicationContextUtil().setApplicationContext(previous);
            resetMappings();
        }
    }

    private Map<String, Object> dispatch(String method, Map<String, Object> payload) {
        ConsoleMessage message = new ConsoleMessage();
        message.setRequestUrl("/api/rdb/import/templates");
        message.setMethod(method);
        message.setMessage(JSON.toJSONString(payload));
        return ConsoleHelper.doController(message).getMessage();
    }

    private static void resetMappings() throws Exception {
        Field initialized = RequestMappingUtils.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        initialized.setBoolean(null, false);
        Field mappings = RequestMappingUtils.class.getDeclaredField("requestMappingInfoMap");
        mappings.setAccessible(true);
        mappings.set(null, Collections.emptyMap());
    }
}
