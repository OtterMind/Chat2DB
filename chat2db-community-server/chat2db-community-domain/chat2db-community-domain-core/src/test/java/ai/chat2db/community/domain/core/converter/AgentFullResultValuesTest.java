package ai.chat2db.community.domain.core.converter;

import ai.chat2db.community.domain.api.model.request.db.DbDlExecuteRequest;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import static org.junit.jupiter.api.Assertions.*;

class AgentFullResultValuesTest {
    @Test
    void onlyInternalOptInDisablesPreviewsAndPublicJsonCannotEnableIt() throws Exception {
        var mapper = new ObjectMapper();
        var request = new DbDlExecuteRequest();
        var converter = Mappers.getMapper(CommandConverter.class);
        assertFalse(converter.param2model(request).isFullResultValues());
        request.setFullResultValues(true);
        assertTrue(converter.param2model(request).isFullResultValues());
        assertFalse(mapper.readValue("{\"fullResultValues\":true}", DbDlExecuteRequest.class).isFullResultValues());
        assertFalse(mapper.readValue("{\"fullResultValues\":true}", SqlExecuteRequest.class).isFullResultValues());
        assertFalse(mapper.writeValueAsString(request).contains("fullResultValues"));
    }
}
