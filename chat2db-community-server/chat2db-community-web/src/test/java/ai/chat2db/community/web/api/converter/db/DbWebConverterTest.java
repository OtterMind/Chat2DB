package ai.chat2db.community.web.api.converter.db;

import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.account.AccountGrant;
import ai.chat2db.community.domain.api.model.account.AccountGrantSummary;
import ai.chat2db.community.domain.api.model.account.AccountOperationRequest;
import ai.chat2db.community.web.api.model.request.db.AccountCommandRequest;
import ai.chat2db.community.web.api.model.response.db.AccountGrantSummaryResponse;
import ai.chat2db.community.web.api.model.response.db.ExecuteResultResponse;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DbWebConverterTest {

    private final DbWebConverter converter = Mappers.getMapper(DbWebConverter.class);

    @Test
    void completionResponseOmitsRowsAndPreservesFinalPagingState() {
        ExecuteResponse result = ExecuteResponse.builder()
                .success(Boolean.TRUE)
                .dataList(List.of(
                        List.of(ResultCell.of("1")),
                        List.of(ResultCell.of("2"))))
                .pageNo(1)
                .pageSize(50_000)
                .fuzzyTotal("50000+")
                .hasNextPage(Boolean.TRUE)
                .resultSetId(1)
                .build();

        ExecuteResultResponse response = converter.dto2completionResponse(result);

        assertNull(response.getDataList());
        assertEquals(2, result.getDataList().size());
        assertEquals(50_000, response.getPageSize());
        assertEquals("50000+", response.getFuzzyTotal());
        assertEquals(Boolean.TRUE, response.getHasNextPage());
        assertEquals(1, response.getResultSetId());
    }

    @Test
    void accountCommandRequestPreservesObjectNameForRoutineGrants() throws Exception {
        Field objectName = AccountCommandRequest.class.getDeclaredField("objectName");
        objectName.setAccessible(true);
        AccountCommandRequest request = new AccountCommandRequest();
        request.setActionType(ai.chat2db.community.domain.api.enums.plugin.AccountActionTypeEnum.GRANT_PRIVILEGE);
        request.setUser("runner");
        request.setHost("%");
        request.setScope(ai.chat2db.community.domain.api.enums.plugin.PrivilegeScopeEnum.FUNCTION);
        request.setDatabaseName("app");
        request.setPrivileges(List.of("EXECUTE"));
        objectName.set(request, "calculate_total");

        AccountOperationRequest command = converter.request2command(request);

        assertEquals("calculate_total", command.getObjectName());
    }

    @Test
    void accountGrantSummaryPreservesRoutineGrantSourceLabels() {
        AccountGrant grant = new AccountGrant();
        grant.setSource("DIRECT_ROUTINE");
        grant.setScope("FUNCTION");
        grant.setDatabaseName("app");
        grant.setObjectName("calculate_total");
        grant.setPrivileges(List.of("EXECUTE"));
        grant.setGrantOption(Boolean.TRUE);
        grant.setDirect(Boolean.TRUE);
        grant.setRevocable(Boolean.TRUE);
        grant.setRawStatement("GRANT EXECUTE ON FUNCTION `app`.`calculate_total` TO 'runner'@'%'");

        AccountGrantSummary summary = new AccountGrantSummary();
        summary.setReadable(Boolean.TRUE);
        summary.setRawStatements(List.of(grant.getRawStatement()));
        summary.setGrants(List.of(grant));

        AccountGrantSummaryResponse response = converter.accountGrantSummary2response(summary);

        assertEquals(Boolean.TRUE, response.getReadable());
        assertEquals(List.of(grant.getRawStatement()), response.getRawStatements());
        assertEquals("DIRECT_ROUTINE", response.getGrants().get(0).getSource());
        assertEquals("FUNCTION", response.getGrants().get(0).getScope());
        assertEquals("calculate_total", response.getGrants().get(0).getObjectName());
        assertEquals(Boolean.TRUE, response.getGrants().get(0).getRevocable());
    }
}
