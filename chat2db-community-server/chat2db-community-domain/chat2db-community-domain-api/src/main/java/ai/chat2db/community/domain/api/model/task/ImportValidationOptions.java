package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Optional source and post-import validation requests. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportValidationOptions {

    private Boolean sourceProfiling;

    private Boolean rowCount;

    private Boolean checksum;

    private Boolean orphanCheck;
}
