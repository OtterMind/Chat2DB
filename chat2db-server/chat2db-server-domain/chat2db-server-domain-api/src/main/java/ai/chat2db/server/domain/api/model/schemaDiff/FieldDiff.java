package ai.chat2db.server.domain.api.model.schemaDiff;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FieldDiff {
    private String fieldName;
    private String sourceValue;
    private String targetValue;
}
