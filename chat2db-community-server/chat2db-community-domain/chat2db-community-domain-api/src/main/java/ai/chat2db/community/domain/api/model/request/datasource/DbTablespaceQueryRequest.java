package ai.chat2db.community.domain.api.model.request.datasource;

import java.sql.Connection;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;


@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DbTablespaceQueryRequest {


    @NotNull
    private Long dataSourceId;


    private boolean refresh;


    private Connection connection;


    private String dbType;


    private String tablespaceName;
}
