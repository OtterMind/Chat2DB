package ai.chat2db.server.domain.api.model.schemaDiff;

import java.util.List;

import ai.chat2db.spi.enums.EditStatus;
import ai.chat2db.spi.model.TableColumn;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnDiff {
    private EditStatus changeType;
    private TableColumn sourceColumn;
    private TableColumn targetColumn;
    private List<FieldDiff> changedFields;
}
