package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.request.agent.AiAgentSkillResolveRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import static org.junit.jupiter.api.Assertions.*;

class AiAgentSkillServiceImplTest {
    @TempDir Path temporaryDirectory;

    @Test
    void preparesStableCompleteResourcesAndParsesOnlyLeadingSkillCommands() throws Exception {
        var resource = new ClassPathResource("skills/catalog.json");
        var service = new AiAgentSkillServiceImpl(resource, temporaryDirectory.resolve("运行资源 with spaces"));
        var skill = service.prepare().get(0);
        Path entry = Path.of(skill.entryPath());
        assertEquals("chart", skill.name());
        assertTrue(Files.readString(entry).contains("name: chart"));
        assertTrue(Files.isRegularFile(entry.resolveSibling("references/bar.md")));
        assertTrue(Files.isRegularFile(entry.resolveSibling("references/combo.md")));
        assertEquals(service.prepare(), new AiAgentSkillServiceImpl(resource, entry.getParent().getParent().getParent()).prepare());
        var explicit = service.resolve(new AiAgentSkillResolveRequest("  /skill:chart\nShow this table"));
        assertEquals("chart", explicit.skillName());
        assertEquals("Show this table", explicit.message());
        assertEquals("", service.resolve(new AiAgentSkillResolveRequest("/skill:chart")).message());
        String literal = "Explain /skill:chart";
        assertEquals(literal, service.resolve(new AiAgentSkillResolveRequest(literal)).message());
        assertNull(service.resolve(new AiAgentSkillResolveRequest(literal)).skillName());
        assertThrows(IllegalArgumentException.class, () -> service.resolve(new AiAgentSkillResolveRequest("/skill:missing go")));
    }

    @Test
    void contentChangesGetANewDirectoryWithoutOverwritingPriorVersion() throws Exception {
        Path sources = Files.createDirectories(temporaryDirectory.resolve("source/other"));
        Path catalog = sources.getParent().resolve("catalog.json");
        Files.writeString(catalog, "{\"skills\":[{\"name\":\"other\",\"files\":[\"SKILL.md\"]}]}");
        Path entry = Files.writeString(sources.resolve("SKILL.md"), "first");
        Path output = temporaryDirectory.resolve("output");
        var first = new AiAgentSkillServiceImpl(new FileSystemResource(catalog), output).prepare().get(0);
        Files.writeString(entry, "second");
        var second = new AiAgentSkillServiceImpl(new FileSystemResource(catalog), output).prepare().get(0);
        assertNotEquals(first.digest(), second.digest());
        assertEquals("first", Files.readString(Path.of(first.entryPath())));
        assertEquals("second", Files.readString(Path.of(second.entryPath())));
    }

    @Test
    void missingOrChangedResourcesFailWithoutPublishingPartialPackages() throws Exception {
        Path catalog = Files.writeString(temporaryDirectory.resolve("catalog.json"),
                "{\"skills\":[{\"name\":\"chart\",\"files\":[\"SKILL.md\",\"references/missing.md\"]}]}");
        Path source = Files.createDirectory(temporaryDirectory.resolve("chart"));
        Files.writeString(source.resolve("SKILL.md"), "content");
        Path output = temporaryDirectory.resolve("output");
        assertThrows(IllegalStateException.class, () -> new AiAgentSkillServiceImpl(new FileSystemResource(catalog), output).prepare());
        try (var paths = Files.list(output)) { assertEquals(0, paths.count()); }
        Files.writeString(catalog, "{\"skills\":[{\"name\":\"chart\",\"files\":[\"SKILL.md\"]}]}");
        var skill = new AiAgentSkillServiceImpl(new FileSystemResource(catalog), output).prepare().get(0);
        Files.writeString(Path.of(skill.entryPath()), "changed");
        assertThrows(IllegalStateException.class, () -> new AiAgentSkillServiceImpl(new FileSystemResource(catalog), output).prepare());
    }
}
