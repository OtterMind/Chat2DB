package ai.chat2db.community.jcef.agent;

import java.util.Map;

public record PiRuntimeManifest(
        String version,
        String operatingSystem,
        String architecture,
        String protocolVersion,
        String source,
        Map<String, String> files) {
}
