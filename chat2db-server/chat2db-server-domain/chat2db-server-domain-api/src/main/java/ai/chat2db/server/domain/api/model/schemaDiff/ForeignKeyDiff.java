package ai.chat2db.server.domain.api.model.schemaDiff;

import java.util.List;

import ai.chat2db.spi.enums.EditStatus;
import ai.chat2db.spi.model.ForeignKey;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ForeignKeyDiff {
    private EditStatus changeType;
    private ForeignKey sourceForeignKey;
    private ForeignKey targetForeignKey;
    private List<FieldDiff> changedFields;
}
