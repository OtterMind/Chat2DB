package ai.chat2db.community.web.api.converter.db;

import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.account.AccountInfo;
import ai.chat2db.community.domain.api.model.account.AccountOperationRequest;
import ai.chat2db.community.web.api.model.request.db.AccountCommandRequest;
import ai.chat2db.community.web.api.model.response.db.AccountResponse;
import ai.chat2db.community.web.api.model.response.db.ExecuteResultResponse;
import ai.chat2db.community.domain.api.enums.plugin.AccountActionTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.PasswordExpirePolicyEnum;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

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
    void accountCommandRoundTripsPasswordAndResourceSettings() {
        AccountCommandRequest request = new AccountCommandRequest();
        request.setDataSourceId(42L);
        request.setUser("app");
        request.setHost("%");
        request.setActionType(AccountActionTypeEnum.ALTER_RESOURCE_LIMITS);
        request.setPasswordExpirePolicy(PasswordExpirePolicyEnum.INTERVAL);
        request.setPasswordExpireDays(90);
        request.setMaxQueriesPerHour(100);
        request.setMaxUpdatesPerHour(20);
        request.setMaxConnectionsPerHour(10);
        request.setMaxUserConnections(3);

        AccountOperationRequest command = converter.request2command(request);

        assertEquals(request.getPasswordExpirePolicy().name(), command.getPasswordExpirePolicy());
        assertEquals(90, command.getPasswordExpireDays());
        assertEquals(100, command.getMaxQueriesPerHour());
        assertEquals(20, command.getMaxUpdatesPerHour());
        assertEquals(10, command.getMaxConnectionsPerHour());
        assertEquals(3, command.getMaxUserConnections());
    }

    @Test
    void accountResponseRoundTripsPasswordAndResourceSettings() {
        AccountInfo account = new AccountInfo();
        account.setUser("app");
        account.setHost("%");
        account.setPasswordExpired(Boolean.TRUE);
        account.setPasswordExpirePolicy(PasswordExpirePolicyEnum.IMMEDIATE.name());
        account.setPasswordLastChanged("2026-08-30 10:15:00");
        account.setPasswordLifetime(90);
        account.setMaxQueriesPerHour(100);
        account.setMaxUpdatesPerHour(20);
        account.setMaxConnectionsPerHour(10);
        account.setMaxUserConnections(3);

        AccountResponse response = converter.account2response(account);

        assertEquals(Boolean.TRUE, response.getPasswordExpired());
        assertEquals(PasswordExpirePolicyEnum.IMMEDIATE.name(), response.getPasswordExpirePolicy());
        assertEquals("2026-08-30 10:15:00", response.getPasswordLastChanged());
        assertEquals(90, response.getPasswordLifetime());
        assertEquals(100, response.getMaxQueriesPerHour());
        assertEquals(20, response.getMaxUpdatesPerHour());
        assertEquals(10, response.getMaxConnectionsPerHour());
        assertEquals(3, response.getMaxUserConnections());
    }
}
