package ai.chat2db.community.domain.api.model.metadata;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Collation {

    private String collationName;

    /**
     * Character set the collation belongs to (from SHOW COLLATION), used to filter
     * collation choices by the selected character set.
     */
    private String charset;

    public Collation(String collationName) {
        this(collationName, null);
    }
}
