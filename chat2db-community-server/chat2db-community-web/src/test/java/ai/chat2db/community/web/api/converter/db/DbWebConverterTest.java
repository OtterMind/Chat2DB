package ai.chat2db.community.web.api.converter.db;

import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.web.api.model.response.db.ExecuteResultResponse;
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
}
