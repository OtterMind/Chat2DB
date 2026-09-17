package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.skill.AiAgentSkill;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentSkillResolveRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        var builtinRoot = temporaryDirectory.resolve("运行资源 with spaces");
        var service = new AiAgentSkillServiceImpl(resource, builtinRoot);
        var skill = service.prepare().get(0);
        Path entry = Path.of(skill.entryPath());
        assertEquals("chart", skill.name());
        assertTrue(entry.startsWith(builtinRoot.toRealPath()));
        assertEquals("SKILL.md", entry.getFileName().toString());
        assertTrue(Files.readString(entry).contains("name: chart"));
        assertTrue(Files.isRegularFile(entry.resolveSibling("references/bar.md")));
        assertTrue(Files.isRegularFile(entry.resolveSibling("references/combo.md")));
        assertEquals(service.prepare(), new AiAgentSkillServiceImpl(resource, entry.getParent().getParent()).prepare());
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
    void installedBuiltinsAreReplacedInPlaceWhenThePackagedContentChanges() throws Exception {
        Path sources = Files.createDirectories(temporaryDirectory.resolve("source/other"));
        Path catalog = sources.getParent().resolve("catalog.json");
        Files.writeString(catalog, "{\"skills\":[{\"name\":\"other\",\"files\":[\"SKILL.md\"]}]}");
        Path source = Files.writeString(sources.resolve("SKILL.md"), "first");
        Path builtinRoot = temporaryDirectory.resolve("builtin");
        var first = new AiAgentSkillServiceImpl(new FileSystemResource(catalog), builtinRoot).prepare().get(0);
        assertTrue(Path.of(first.entryPath()).startsWith(builtinRoot.toRealPath()));
        Files.writeString(source, "second");
        var second = new AiAgentSkillServiceImpl(new FileSystemResource(catalog), builtinRoot).prepare().get(0);
        assertNotEquals(first.digest(), second.digest());
        assertEquals(first.entryPath(), second.entryPath());
        assertEquals("second", Files.readString(Path.of(second.entryPath())));
        Files.writeString(Path.of(second.entryPath()), "tampered");
        var third = new AiAgentSkillServiceImpl(new FileSystemResource(catalog), builtinRoot).prepare().get(0);
        assertEquals("second", Files.readString(Path.of(third.entryPath())));
        try (var entries = Files.list(builtinRoot)) {
            assertEquals(List.of("other"), entries.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void missingResourcesFailWithoutPublishingPartialPackages() throws Exception {
        Path catalog = Files.writeString(temporaryDirectory.resolve("catalog.json"),
                "{\"skills\":[{\"name\":\"chart\",\"files\":[\"SKILL.md\",\"references/missing.md\"]}]}");
        Path source = Files.createDirectory(temporaryDirectory.resolve("chart"));
        Files.writeString(source.resolve("SKILL.md"), "content");
        Path builtinRoot = temporaryDirectory.resolve("builtin");
        assertThrows(IllegalStateException.class, () -> new AiAgentSkillServiceImpl(new FileSystemResource(catalog), builtinRoot).prepare());
        try (var paths = Files.list(builtinRoot)) { assertEquals(0, paths.count()); }
    }

    @Test
    void discoversUserSkillsWhereTheyLiveAndDropsUnusableSources() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("用户 skills")).toRealPath();
        Path builtinRoot = temporaryDirectory.resolve("builtin");
        Path source = Files.createDirectories(root.resolve("report"));
        Files.createDirectories(source.resolve("references"));
        Files.writeString(source.resolve("references/说明 + guide.md"), "first reference");
        Path entry = Files.writeString(source.resolve("SKILL.md"),
                "---\nname: report\ndescription: Build reports\ncompatibility: Chat2DB\ndisable-model-invocation: true\n---\n[Guide](references/说明%20+%20guide.md)\n");
        var service = new AiAgentSkillServiceImpl(new ClassPathResource("skills/catalog.json"), builtinRoot, root,
                temporaryDirectory.resolve("old/resources/skills"));
        var first = report(service.select("one"));
        assertEquals(entry.toRealPath().toString(), first.entryPath());
        Files.writeString(source.resolve("references/说明 + guide.md"), "second reference");
        var second = report(service.select("two"));
        assertNotEquals(first.digest(), second.digest());
        assertEquals(first, report(service.selected("one")));
        // User sources are read where they live, so an edit is visible to the very next read.
        assertEquals("second reference", Files.readString(Path.of(first.entryPath()).resolveSibling("references/说明 + guide.md")));
        assertEquals("report", service.resolve(new AiAgentSkillResolveRequest("/skill:report use it")).skillName());
        Files.writeString(entry, "incomplete edit");
        assertFalse(service.prepare().stream().anyMatch(skill -> skill.name().equals("report")));
        assertThrows(IllegalArgumentException.class, () -> service.resolve(new AiAgentSkillResolveRequest("/skill:report use it")));
        Files.writeString(entry, "---\nname: report\ndescription: Build reports\n---\nbody\n");
        assertTrue(service.prepare().stream().anyMatch(skill -> skill.name().equals("report")));
        Files.delete(entry);
        Files.delete(source.resolve("references/说明 + guide.md"));
        Files.delete(source.resolve("references"));
        Files.delete(source);
        assertFalse(service.prepare().stream().anyMatch(skill -> skill.name().equals("report")));
        assertFalse(Files.exists(builtinRoot.resolve("report")));
        service.release("one");
        assertFalse(service.selected("one").contains(first));
    }

    @Test
    void invalidSourcesCannotOverrideBuiltinsOrReadThroughLinks() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("skills")).toRealPath();
        var service = new AiAgentSkillServiceImpl(new ClassPathResource("skills/catalog.json"),
                temporaryDirectory.resolve("builtin"), root, null);
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
    void recordedSnapshotPathsResolveToTheSkillLoadedNow() throws Exception {
        Path root = temporaryDirectory.resolve("skills");
        Path legacy = temporaryDirectory.resolve("history/resources/skills");
        var service = new AiAgentSkillServiceImpl(new ClassPathResource("skills/catalog.json"),
                temporaryDirectory.resolve("builtin"), root, legacy);
        var chart = service.prepare().get(0);
        assertEquals(Path.of(chart.entryPath()), service.resolveLegacyPath(legacy.resolve(chart.digest()).resolve("chart/SKILL.md")));
        assertEquals(Path.of(chart.entryPath()).resolveSibling("references/pie.md"),
                service.resolveLegacyPath(legacy.resolve(chart.digest()).resolve("chart/references/pie.md")));
        assertEquals(Path.of(chart.entryPath()),
                service.resolveLegacyPath(root.resolve(".resources").resolve(chart.digest()).resolve("chart/SKILL.md")));
        Path unrelated = temporaryDirectory.resolve("notes/SKILL.md");
        assertEquals(unrelated, service.resolveLegacyPath(unrelated));
        assertFalse(Files.exists(legacy));
        assertFalse(Files.exists(root.resolve(".resources")));
    }

    @Test
    void concurrentHostsConvergeOnOneInstalledVersion() throws Exception {
        var catalog = new ClassPathResource("skills/catalog.json");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                Path builtinRoot = temporaryDirectory.resolve("shared-" + attempt);
                var one = new AiAgentSkillServiceImpl(catalog, builtinRoot);
                var two = new AiAgentSkillServiceImpl(catalog, builtinRoot);
                var barrier = new java.util.concurrent.CyclicBarrier(2);
                var first = executor.submit(() -> { barrier.await(); return one.prepare(); });
                var second = executor.submit(() -> { barrier.await(); return two.prepare(); });
                assertEquals(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
                try (var entries = Files.list(builtinRoot)) {
                    assertEquals(List.of("chart", "skill-manager"),
                            entries.map(path -> path.getFileName().toString()).sorted().toList());
                }
            }
        } finally { executor.shutdownNow(); }
    }

    private static AiAgentSkill report(List<AiAgentSkill> skills) {
        return skills.stream().filter(skill -> skill.name().equals("report")).findFirst().orElseThrow();
    }
}
