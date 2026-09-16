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
    @Test
    void discoversSharedUserSkillsAndFreezesEachSessionsSelectedVersion() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("用户 skills")).toRealPath();
        Path source = Files.createDirectories(root.resolve("report"));
        Files.createDirectories(source.resolve("references"));
        Files.writeString(source.resolve("references/说明 + guide.md"), "first reference");
        Path entry = Files.writeString(source.resolve("SKILL.md"), "---\nname: report\ndescription: Build reports\ncompatibility: Chat2DB\ndisable-model-invocation: true\n---\n[Guide](references/说明%20+%20guide.md)\n");
        var service = new AiAgentSkillServiceImpl(new ClassPathResource("skills/catalog.json"), root.resolve(".resources"), root,
                temporaryDirectory.resolve("old/resources/skills"));
        var first = service.select("one").stream().filter(skill -> skill.name().equals("report")).findFirst().orElseThrow();
        assertTrue(Path.of(first.entryPath()).startsWith(root.resolve(".resources")));
        Files.writeString(source.resolve("references/说明 + guide.md"), "second reference");
        var second = service.select("two").stream().filter(skill -> skill.name().equals("report")).findFirst().orElseThrow();
        assertNotEquals(first.digest(), second.digest());
        assertEquals(first, service.selected("one").stream().filter(skill -> skill.name().equals("report")).findFirst().orElseThrow());
        assertEquals("first reference", Files.readString(Path.of(first.entryPath()).resolveSibling("references/说明 + guide.md")));
        Files.writeString(entry, "incomplete edit");
        assertTrue(service.prepare().contains(second));
        assertEquals("report", service.resolve(new AiAgentSkillResolveRequest("/skill:report use it")).skillName());
        Files.delete(entry);
        Files.delete(source.resolve("references/说明 + guide.md"));
        Files.delete(source.resolve("references"));
        Files.delete(source);
        assertFalse(service.prepare().stream().anyMatch(skill -> skill.name().equals("report")));
        assertTrue(Files.isRegularFile(Path.of(first.entryPath())));
        service.release("one");
        assertFalse(service.selected("one").contains(first));
    }

    @Test
    void invalidSourcesCannotOverrideBuiltinsOrReadThroughLinks() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("skills")).toRealPath();
        var service = new AiAgentSkillServiceImpl(new ClassPathResource("skills/catalog.json"), root.resolve(".resources"), root, null);
        var builtins = service.prepare();
        Path bad = Files.createDirectory(root.resolve("bad"));
        Files.writeString(bad.resolve("SKILL.md"), "---\nname: chart\ndescription: Override\n---\nwrong");
        assertEquals(builtins, service.prepare());
        Files.writeString(bad.resolve("SKILL.md"), "---\nname: linked\ndescription: Linked\n---\n[Missing](missing.md)");
        assertEquals(builtins, service.prepare());
        Files.writeString(bad.resolve("SKILL.md"), "---\nname: linked\ndescription: Linked\n---\nbody");
        Files.createSymbolicLink(bad.resolve("outside"), temporaryDirectory);
        assertEquals(builtins, service.prepare());
        Files.delete(bad.resolve("outside"));
        assertTrue(service.prepare().stream().anyMatch(skill -> skill.name().equals("linked")));
    }

    @Test
    void legacyPathsResolveToTheNewSharedResourceRootWithoutWritingTheOldLocation() throws Exception {
        Path root = temporaryDirectory.resolve("skills");
        Path legacy = temporaryDirectory.resolve("history/resources/skills");
        var service = new AiAgentSkillServiceImpl(new ClassPathResource("skills/catalog.json"), root.resolve(".resources"), root, legacy);
        var chart = service.prepare().get(0);
        Path oldPath = legacy.resolve(chart.digest()).resolve("chart/SKILL.md");
        assertEquals(Path.of(chart.entryPath()), service.resolveLegacyPath(oldPath));
        assertFalse(Files.exists(legacy));
    }

    @Test
    void concurrentHostsShareOneCompleteResourceVersion() throws Exception {
        var catalog = new ClassPathResource("skills/catalog.json");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                Path root = temporaryDirectory.resolve("shared-" + attempt);
                var one = new AiAgentSkillServiceImpl(catalog, root.resolve(".resources"), root, null);
                var two = new AiAgentSkillServiceImpl(catalog, root.resolve(".resources"), root, null);
                var barrier = new java.util.concurrent.CyclicBarrier(2);
                var first = executor.submit(() -> { barrier.await(); return one.prepare(); });
                var second = executor.submit(() -> { barrier.await(); return two.prepare(); });
                assertEquals(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
                try (var entries = Files.list(root.resolve(".resources"))) {
                    assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith(".preparing-")));
                }
            }
        } finally { executor.shutdownNow(); }
    }

}
