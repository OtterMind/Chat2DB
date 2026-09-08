package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A physical or operator-supplied logical parent-to-child dependency. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportTableDependency {

    private String parentTable;

    private String parentColumn;

    private String childTable;

    private String childColumn;

    private boolean logical;
}
