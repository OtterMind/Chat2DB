package ai.chat2db.community.start.ai.subscription.controller;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.subscription.AiSubscriptionCapability;
import ai.chat2db.community.start.ai.subscription.lifecycle.LifecycleErrorCode;
import ai.chat2db.community.start.ai.subscription.lifecycle.LifecycleException;
import ai.chat2db.community.start.ai.subscription.lifecycle.LoginType;
import ai.chat2db.community.start.ai.subscription.lifecycle.SafeLoginStartResponse;
import ai.chat2db.community.start.ai.subscription.runtime.SubscriptionAiFacade;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.controller.ControllerHandler;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSubscriptionControllerTest {

    @Test
    void controllerCanBeProxiedByTheDesktopControllerAspect() {
        AiSubscriptionController controller = new AiSubscriptionController(mock(SubscriptionAiFacade.class));
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(controller);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAspect(ControllerHandler.class);

        assertDoesNotThrow(() -> {
            proxyFactory.getProxy();
        });
    }

    @Test
    void browserLoginResponseContainsOnlyOpaqueAttemptMetadata() {
        SubscriptionAiFacade facade = mock(SubscriptionAiFacade.class);
        when(facade.startConnect(AiProviderEnum.OPENAI)).thenReturn(new SafeLoginStartResponse(
                "attempt-1", LoginType.BROWSER, 123L, null, null));
        AiSubscriptionController controller = new AiSubscriptionController(facade);

        DataResult<AiSubscriptionController.StartConnectResponse> result = controller.startConnect(
                new AiSubscriptionController.ProviderRequest(AiProviderEnum.OPENAI));

        assertEquals("attempt-1", result.getData().attemptId());
        assertEquals("STARTED", result.getData().status());
        String serializedShape = result.getData().toString().toLowerCase();
        assertFalse(serializedShape.contains("authurl"));
        assertFalse(serializedShape.contains("token"));
    }

    @Test
    void superGrokCannotEnterLoginFacade() {
        SubscriptionAiFacade facade = mock(SubscriptionAiFacade.class);
        AiSubscriptionController controller = new AiSubscriptionController(facade);

        assertThrows(IllegalArgumentException.class, () -> controller.cancelConnect(
                new AiSubscriptionController.CancelConnectRequest(AiProviderEnum.XAI, "attempt-x")));
        verify(facade, never()).cancelConnect(AiProviderEnum.XAI, "attempt-x");
    }

    @Test
    void capabilityAndProvidersAreFacadeOwned() {
        SubscriptionAiFacade facade = mock(SubscriptionAiFacade.class);
        when(facade.capability()).thenReturn(AiSubscriptionCapability.enabledCapability());
        when(facade.providers()).thenReturn(List.of());
        AiSubscriptionController controller = new AiSubscriptionController(facade);

        assertEquals(Boolean.TRUE, controller.capability().getData().enabled());
        assertEquals(List.of(), controller.providers().getData());
    }

    @Test
    void loginFailurePreservesSafeLifecycleCode() {
        SubscriptionAiFacade facade = mock(SubscriptionAiFacade.class);
        when(facade.startConnect(AiProviderEnum.OPENAI))
                .thenThrow(new LifecycleException(LifecycleErrorCode.APP_SERVER_UNAVAILABLE));
        AiSubscriptionController controller = new AiSubscriptionController(facade);

        DataResult<AiSubscriptionController.StartConnectResponse> result = controller.startConnect(
                new AiSubscriptionController.ProviderRequest(AiProviderEnum.OPENAI));

        assertEquals("FAILED", result.getData().status());
        assertEquals("APP_SERVER_UNAVAILABLE", result.getData().errorCode());
    }
}
