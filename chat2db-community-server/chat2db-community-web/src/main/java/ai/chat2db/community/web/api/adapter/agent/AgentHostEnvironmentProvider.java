package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AgentHostEnvironmentProvider {

    private final String applicationVersion;

    public AgentHostEnvironmentProvider(@Value("${chat2db.version}") String applicationVersion) {
        this.applicationVersion = applicationVersion;
    }

    public AgentRuntimeEnvironmentRequest current() {
        return new AgentRuntimeEnvironmentRequest(
                applicationVersion,
                normalizeOperatingSystem(System.getProperty("os.name", "unknown")),
                System.getProperty("os.arch", "unknown").toLowerCase(Locale.ROOT));
    }

    private String normalizeOperatingSystem(String value) {
        String operatingSystem = value.toLowerCase(Locale.ROOT);
        if (operatingSystem.contains("win")) {
            return "windows";
        }
        if (operatingSystem.contains("mac") || operatingSystem.contains("darwin")) {
            return "macos";
        }
        if (operatingSystem.contains("linux")) {
            return "linux";
        }
        return operatingSystem;
    }
}
