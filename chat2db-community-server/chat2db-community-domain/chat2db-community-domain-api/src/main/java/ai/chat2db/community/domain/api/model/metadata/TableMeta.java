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
    /**
     * InnoDB General Tablespaces available on the server (instance-level). Populated for dialects
     * that support tablespaces (MySQL); empty otherwise. Feeds the tablespace option dropdown in
     * the create/edit-table form.
     */
    private List<Tablespace> tablespaces;
}
