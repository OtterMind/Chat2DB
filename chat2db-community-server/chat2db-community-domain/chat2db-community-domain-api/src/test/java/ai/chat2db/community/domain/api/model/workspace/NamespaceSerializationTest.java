package ai.chat2db.community.domain.api.model.workspace;

import ai.chat2db.community.domain.api.model.datasource.DataSource;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class NamespaceSerializationTest {

    @Test
    void transientDataSourcesAreIgnoredByBothSerializers() throws Exception {
        Namespace namespace = new Namespace();
        namespace.setId(1L);
        namespace.setDataSources(List.of(new DataSource()));

        assertFalse(new ObjectMapper().writeValueAsString(namespace).contains("dataSources"));
        assertFalse(JSON.toJSONString(namespace).contains("dataSources"));
    }

    @Test
    void staleDataSourcesAreIgnoredDuringDeserialization() throws Exception {
        String json = "{\"id\":1,\"dataSources\":[{}]}";

        assertNull(new ObjectMapper().readValue(json, Namespace.class).getDataSources());
        assertNull(JSON.parseObject(json, Namespace.class).getDataSources());
    }
}
