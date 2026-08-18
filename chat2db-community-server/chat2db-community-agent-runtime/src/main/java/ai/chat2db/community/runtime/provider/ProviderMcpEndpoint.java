package ai.chat2db.community.runtime.provider;

import lombok.Data;

import java.net.URI;

@Data
public class ProviderMcpEndpoint {

    private String name;
    private URI url;
    private String bearerTokenEnvironmentVariable;
}
