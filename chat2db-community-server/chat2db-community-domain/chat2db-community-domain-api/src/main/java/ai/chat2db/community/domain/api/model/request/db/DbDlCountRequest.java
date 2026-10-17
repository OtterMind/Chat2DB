package ai.chat2db.community.domain.api.model.request.db;

import jakarta.validation.constraints.NotNull;

import ai.chat2db.community.domain.api.model.sql.SqlParameterValue;
import lombok.Data;

import java.util.List;
import java.util.Map;


@Data
public class DbDlCountRequest {


    @NotNull
    private String sql;


    @NotNull
    private Long consoleId;


    @NotNull
    private Long dataSourceId;


    @NotNull
    private String databaseName;


    @NotNull
    private String tableName;

    /**
     * Values for {@code :name} placeholders in {@link #sql}, keyed by name.
     */
    private Map<String, SqlParameterValue> parameters;

    /**
     * Values for {@code ?} placeholders in {@link #sql}, in appearance order.
     */
    private List<SqlParameterValue> positionalParameters;
}
