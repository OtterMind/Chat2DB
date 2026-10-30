package ai.chat2db.community.domain.core.converter;

import ai.chat2db.community.domain.api.model.request.db.DbDlExecuteRequest;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.model.sql.SqlParameterValue;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommandConverterTest {

    private final CommandConverter converter = Mappers.getMapper(CommandConverter.class);

    @Test
    void leavesParametersToNormalisationSoUncheckedValuesNeverReachTheExecutor() {
        DbDlExecuteRequest request = new DbDlExecuteRequest();
        request.setSql("SELECT name FROM users WHERE id = :id");
        request.setParameters(Map.of("id", SqlParameterValue.number("123")));

        SqlExecuteRequest command = converter.toSqlExecuteRequest(request);

        assertEquals(request.getSql(), command.getScript());
        assertNull(command.getParameters());
        assertFalse(command.isPositionalParameterStyle());
    }

    @Test
    void plainSqlHasNoParameters() {
        DbDlExecuteRequest request = new DbDlExecuteRequest();
        request.setSql("SELECT 1");

        assertNull(converter.toSqlExecuteRequest(request).getParameters());
    }
}
