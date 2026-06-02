package ai.chat2db.server.domain.api.model.schemaDiff;

import java.util.List;

import ai.chat2db.spi.enums.EditStatus;
import ai.chat2db.spi.model.TableIndex;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class IndexDiff {
    private EditStatus changeType;
    private TableIndex sourceIndex;
    private TableIndex targetIndex;
    private List<FieldDiff> changedFields;
}
