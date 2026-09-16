package ai.chat2db.community.bootstrap;

import java.util.List;
import java.util.Map;

public record LaunchConfiguration(
    String mainJar,
    String mainClass,
    List<String> loaderPath,
    List<String> requiredPaths,
    Map<String, String> systemProperties
) {
}
