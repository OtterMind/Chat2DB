package ai.chat2db.community.web.api.model.request.db.cell;

import lombok.Data;

import java.util.List;

/**
 * Releases large-value handles a client no longer needs.
 */
@Data
public class CellValueReleaseRequest {

    /**
     * Handles to release; unknown values are ignored so a retry stays harmless.
     */
    private List<String> largeValueIds;
}
