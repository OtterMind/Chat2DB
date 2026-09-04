package ai.chat2db.community.domain.api.model.metadata;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class TableMeta {
    private List<ColumnType> columnTypes;
    private List<Charset> charsets;
    private List<Collation> collations;
    private List<IndexType> indexTypes;
    private List<DefaultValue> defaultValues;
    private List<EngineType> engineTypes;
    private Boolean generatedColumnSupported;
    private String generatedColumnMinVersion;
    private String generatedColumnUnsupportedReason;
}
