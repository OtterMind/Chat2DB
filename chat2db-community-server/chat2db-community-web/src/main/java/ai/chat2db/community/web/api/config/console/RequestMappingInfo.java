package ai.chat2db.community.web.api.config.console;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RequestMappingInfo {
    private String url;
    private Class controller;
    private String method;
    private Class[] params;
    private List<String> requestMethods;
    private Map<Integer, String> pathVariables;
}
