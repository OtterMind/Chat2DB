package ai.chat2db.community.domain.api.model.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceToolData {

    private List<Item> datasources = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long id;
        private String name;
        private String type;
        private String env;
    }
}
