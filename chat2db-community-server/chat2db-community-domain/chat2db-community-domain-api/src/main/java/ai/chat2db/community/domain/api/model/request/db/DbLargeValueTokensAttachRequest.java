package ai.chat2db.community.domain.api.model.request.db;

import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Asks the token service to capture every large value of one result response into a snapshot and to hand the client a
 * readable id per cell.
 */
@Data
public class DbLargeValueTokensAttachRequest {

    /**
     * Name of the result table, used to build a readable download file name.
     */
    private String tableName;

    @NotEmpty
    private List<Header> headers;

    @NotEmpty
    private List<List<ResultCell>> dataList;

    /**
     * Result snapshot that receives the complete content of every large value in {@link #dataList}.
     */
    private String snapshotId;

    /**
     * Index of the first row of {@link #dataList} inside the result set, so captured cells keep absolute positions.
     */
    private int rowOffset;

}
