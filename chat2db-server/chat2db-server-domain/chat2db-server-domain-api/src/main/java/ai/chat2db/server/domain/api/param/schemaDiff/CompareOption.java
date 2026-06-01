package ai.chat2db.server.domain.api.param.schemaDiff;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class CompareOption {
    private boolean compareColumn = true;
    private boolean compareIndex = true;
    private boolean compareForeignKey = true;
    private boolean compareTableOption = true;
    private boolean caseSensitive = false;
    private boolean excludeDeprecated = true;
    private boolean ignoreCharsetAlias = true;
    private boolean ignoreIntegerDisplayWidth = true;
    private boolean ignoreAutoIncrement = true;
}
