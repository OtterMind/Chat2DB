package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DbActiveTransactionControllerTest {

    @Test
    void listRequestExposesDatasourceContextToConnectionAspect() throws Exception {
        Method method = DbActiveTransactionController.class.getMethod("list", DataSourceBaseRequest.class);

        assertEquals(DataSourceBaseRequest.class, method.getParameterTypes()[0]);
    }
}
