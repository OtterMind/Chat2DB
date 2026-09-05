package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.lock.LockView;
import ai.chat2db.community.domain.api.model.lock.LockView.ErrorCode;
import ai.chat2db.community.domain.api.model.lock.LockView.ErrorSection;
import ai.chat2db.community.domain.api.model.lock.LockView.LockKind;
import ai.chat2db.community.domain.api.model.lock.LockView.MetadataLock;
import ai.chat2db.community.domain.api.model.lock.LockView.Source;
import ai.chat2db.community.domain.api.model.lock.LockView.ViewError;
import ai.chat2db.community.domain.api.model.lock.LockView.WaitChain;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DbLockControllerTest {

    @Test
    void viewRequiresOneDatasourceRequestObject() throws Exception {
        Method method = DbLockController.class.getDeclaredMethod("view", DataSourceBaseRequest.class);

        GetMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, GetMapping.class);

        assertNotNull(mapping);
        assertArrayEquals(new String[] {"/view"}, mapping.value());
        assertEquals(DataSourceBaseRequest.class, method.getParameterTypes()[0]);
    }

    @Test
    void serializesTypedLockViewContract() {
        MetadataLock metadataLock = new MetadataLock();
        metadataLock.setObjectType("TABLE");
        metadataLock.setObjectInstanceId("1001");
        metadataLock.setOwnerSessionAvailable(true);
        ViewError error = new ViewError();
        error.setSection(ErrorSection.DATA_LOCKS);
        error.setCode(ErrorCode.PRIVILEGE_REQUIRED);
        WaitChain metadataWait = new WaitChain();
        metadataWait.setLockKind(LockKind.METADATA);
        metadataWait.setLockObject("app.orders");
        LockView view = new LockView();
        view.setDataSourceId(42L);
        view.setSource(Source.PERFORMANCE_SCHEMA);
        view.setDataLocks(List.of());
        view.setWaits(List.of());
        view.setMetaLocks(List.of(metadataLock));
        view.setSessions(List.of());
        view.setWaitChains(List.of());
        view.setMetadataWaitChains(List.of(metadataWait));
        view.setErrors(List.of(error));

        JsonNode json = new ObjectMapper().valueToTree(view);

        assertEquals(42L, json.path("dataSourceId").asLong());
        assertEquals("PERFORMANCE_SCHEMA", json.path("source").asText());
        assertEquals("1001", json.path("metaLocks").get(0).path("objectInstanceId").asText());
        assertEquals(true, json.path("metaLocks").get(0).path("ownerSessionAvailable").asBoolean());
        assertEquals("METADATA", json.path("metadataWaitChains").get(0).path("lockKind").asText());
        assertEquals("app.orders", json.path("metadataWaitChains").get(0).path("lockObject").asText());
        assertEquals("DATA_LOCKS", json.path("errors").get(0).path("section").asText());
        assertEquals("PRIVILEGE_REQUIRED", json.path("errors").get(0).path("code").asText());
        assertEquals("INFORMATION_SCHEMA", new ObjectMapper().valueToTree(Source.INFORMATION_SCHEMA).asText());
    }
}
