package ai.chat2db.community.domain.api.model.key;

import java.util.List;

import lombok.Data;

@Data
public class KeyEntry {

    private String name;

    private Object value;

    private Long ttl;

    private String type;

    private List<KeyValueItem> listValues;

    private List<KeyValueItem> hashValues;

    private List<KeyValueItem> zsValues;

    private List<KeyValueItem> streamValues;

    private List<KeyValueItem> values;

    /**
     * Whether the type-specific value payload has been loaded.
     */
    private Boolean detailLoaded;
}
