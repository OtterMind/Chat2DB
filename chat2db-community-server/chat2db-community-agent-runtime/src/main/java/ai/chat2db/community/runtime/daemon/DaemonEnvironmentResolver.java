package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

public class DaemonEnvironmentResolver {

    private final Function<String, String> environmentLookup;

    public DaemonEnvironmentResolver() {
        this(System::getenv);
    }

    DaemonEnvironmentResolver(Function<String, String> environmentLookup) {
        this.environmentLookup = environmentLookup;
    }

    public Map<String, String> resolve(AgentRuntimeProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("Runtime Profile is required");
        }
        LinkedHashMap<String, String> resolved = new LinkedHashMap<>();
        String path = environmentLookup.apply("PATH");
        if (StringUtils.isNotBlank(path)) {
            resolved.put("PATH", path);
        }
        Map<String, String> references = profile.getEnvironmentReferences() == null
                ? Map.of() : profile.getEnvironmentReferences();
        for (Map.Entry<String, String> entry : references.entrySet()) {
            validateEnvironmentName(entry.getKey());
            validateEnvironmentName(entry.getValue());
            String value = environmentLookup.apply(entry.getValue());
            if (value == null) {
                throw new IllegalStateException("Runtime environment reference is unavailable: " + entry.getValue());
            }
            resolved.put(entry.getKey(), value);
        }
        return Map.copyOf(resolved);
    }

    public Path resolveExecutable(AgentRuntimeProfile profile, Map<String, String> environment) {
        String executable = StringUtils.trimToNull(profile == null ? null : profile.getExecutable());
        if (executable == null) {
            throw new IllegalArgumentException("Runtime executable is required");
        }
        Path direct = Path.of(executable);
        if (direct.isAbsolute()) {
            return requireExecutable(direct);
        }
        if (direct.getNameCount() != 1) {
            throw new IllegalArgumentException("Relative Runtime executable may not contain directories");
        }
        String pathValue = environment.get("PATH");
        if (StringUtils.isBlank(pathValue)) {
            throw new IllegalStateException("PATH is required to resolve Runtime executable: " + executable);
        }
        for (String entry : pathValue.split(java.io.File.pathSeparator)) {
            if (StringUtils.isBlank(entry)) {
                continue;
            }
            Path candidate = Path.of(entry).resolve(executable).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Runtime executable was not found on configured PATH: " + executable);
    }

    private Path requireExecutable(Path path) {
        Path normalized = path.normalize();
        if (!Files.isRegularFile(normalized) || !Files.isExecutable(normalized)) {
            throw new IllegalStateException("Runtime executable is not an executable file: " + normalized);
        }
        return normalized;
    }

    private void validateEnvironmentName(String name) {
        if (StringUtils.isBlank(name) || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Runtime environment reference name is invalid");
        }
    }
}
