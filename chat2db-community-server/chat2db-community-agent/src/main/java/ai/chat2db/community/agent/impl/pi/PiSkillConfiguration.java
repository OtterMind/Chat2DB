package ai.chat2db.community.agent.impl.pi;

import ai.chat2db.community.agent.exception.pi.PiRpcException;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSkill;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The only source of paths for Pi resource discovery, replaced between runs. */
final class PiSkillConfiguration {
    private final Path configuration;
    private final ObjectMapper mapper;
    private List<AgentRuntimeSkill> loaded;
    private boolean verified;

    PiSkillConfiguration(Path configuration, ObjectMapper mapper, List<AgentRuntimeSkill> initial) throws IOException {
        this.configuration = configuration;
        this.mapper = mapper;
        this.loaded = List.copyOf(initial);
        write(initial);
    }

    boolean requiresReload(List<AgentRuntimeSkill> skills) { return !loaded.equals(skills); }
    boolean requiresVerification() { return !verified; }

    void write(List<AgentRuntimeSkill> skills) throws IOException {
        for (var skill : skills) {
            Path path = Path.of(skill.entryPath());
            if (!path.isAbsolute() || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !path.toRealPath().equals(path)) throw new IOException("Invalid skill snapshot: " + skill.name());
        }
        Path temporary = Files.createTempFile(configuration, "skills-", ".json.tmp");
        try {
            mapper.writeValue(temporary.toFile(), skills);
            Files.move(temporary, configuration.resolve("skills.json"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    void verify(JsonNode response, List<AgentRuntimeSkill> expected) {
        Map<String, String> actual = new HashMap<>();
        for (JsonNode command : response.path("commands")) {
            if ("skill".equals(command.path("source").asText())) {
                actual.put(command.path("name").asText(),
                        command.path("sourceInfo").path("path").asText(command.path("path").asText()));
            }
        }
        Map<String, String> wanted = new HashMap<>();
        for (var skill : expected) wanted.put("skill:" + skill.name(), skill.entryPath());
        if (!actual.equals(wanted)) throw new PiRpcException("Pi did not load the selected skill resources");
        loaded = List.copyOf(expected);
        verified = true;
    }
}
