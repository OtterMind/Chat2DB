package ai.chat2db.server.web.api.controller.redis.request;

import jakarta.validation.constraints.NotNull;

import ai.chat2db.server.web.api.controller.data.source.request.DataSourceBaseRequest;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class KeyPartialUpdateRequest extends DataSourceBaseRequest {

    @NotNull
    private String keyName;

    @NotNull
    private String keyType;

    private Map<String, String> addedFields;

    private List<String> removedFields;

    private Object updateTtl;
}
