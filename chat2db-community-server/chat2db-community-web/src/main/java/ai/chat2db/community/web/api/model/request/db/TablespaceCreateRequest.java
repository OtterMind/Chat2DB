package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import lombok.Data;

@Data
public class TablespaceCreateRequest extends DataSourceBaseRequest {

    private String name;

    /**
     * Data-file path on the MySQL server filesystem. User-supplied and emitted verbatim; the
     * application never validates, canonicalizes, or writes this path.
     */
    private String dataFile;

    private Long fileBlockSize;
}
