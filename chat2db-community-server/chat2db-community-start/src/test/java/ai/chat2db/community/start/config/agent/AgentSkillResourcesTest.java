package ai.chat2db.community.start.config.agent;

import ai.chat2db.community.domain.api.enums.agent.AiAgentChartType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

class AgentSkillResourcesTest {
    @org.junit.jupiter.api.io.TempDir Path temporaryDirectory;

    @Test
    void lazyDesktopInitializationUsesTheApplicationResourceLoader() throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        String userHome = System.getProperty("user.home");
        try (var desktopLoader = new java.net.URLClassLoader(new java.net.URL[0], null)) {
            System.setProperty("user.home", temporaryDirectory.toString());
            thread.setContextClassLoader(desktopLoader);
            assertFalse(new ClassPathResource("skills/catalog.json").exists());
            var skills = new AgentSkillConfiguration().agentSkillService(temporaryDirectory.resolve(".chat2db-skills").toString()).prepare();
            assertEquals(java.util.List.of("chart", "skill-manager", "mcp-manager"),
                    skills.stream().map(skill -> skill.name()).toList());
            Path builtinRoot = Path.of(ai.chat2db.community.tools.util.ConfigUtils.getEnvBasePath())
                    .resolve("storage/agent-v2/skills/builtin").toRealPath();
            for (var skill : skills) {
                assertTrue(java.nio.file.Files.readString(Path.of(skill.entryPath())).contains("name: " + skill.name()));
                assertTrue(Path.of(skill.entryPath()).startsWith(builtinRoot));
                assertFalse(Path.of(skill.entryPath()).startsWith(temporaryDirectory.resolve(".chat2db-skills").toRealPath()));
                if (skill.name().equals("chart")) assertTrue(java.nio.file.Files.isRegularFile(Path.of(skill.entryPath()).resolveSibling("references/combo.md")));
            }
        } finally {
            thread.setContextClassLoader(previous);
            System.setProperty("user.home", userHome);
        }
    }

    @Test
    void packagedCatalogContainsEntriesReferencesAndEverySupportedChartType() throws Exception {
        var catalog = new ClassPathResource("skills/catalog.json");
        var json = new ObjectMapper();
        Set<String> types = new HashSet<>();
        try (var input = catalog.getInputStream()) {
            for (var entry : json.readTree(input).path("skills")) {
                String name = entry.path("name").asText();
                Set<String> files = new HashSet<>();
                for (var file : entry.path("files")) files.add(file.asText());
                assertTrue(files.contains("SKILL.md"));
                for (String path : files) {
                    var resource = catalog.createRelative(name + "/" + path);
                    String text = resource.getContentAsString(StandardCharsets.UTF_8);
                    if (path.equals("SKILL.md")) assertTrue(text.contains("name: " + name + "\n"));
                    // Only the chart skill documents chart calls; other skills use JSON for their own shapes.
                    var examples = name.equals("chart")
                            ? Pattern.compile("```json\\n(.*?)\\n```", Pattern.DOTALL).matcher(text)
                            : Pattern.compile("(?!)").matcher(text);
                    while (examples.find()) {
                        var call = json.readTree(examples.group(1));
                        String type = call.path("chartType").asText();
                        assertNotNull(AiAgentChartType.from(type));
                        types.add(type);
                        assertTrue(call.has("resultId"));
                        if (type.equals("Combo")) {
                            assertTrue(call.has("series"));
                            assertFalse(call.has("yField"));
                        } else {
                            assertFalse(call.has("series"));
                            assertTrue(call.has("yField"));
                        }
                    }
                    var links = Pattern.compile("\\]\\(([^)]+)\\)").matcher(text);
                    while (links.find()) {
                        String target = links.group(1);
                        if (!target.startsWith("https:")) {
                            assertTrue(resource.createRelative(target).exists(), target);
                            String relative = Path.of(path).resolveSibling(target).normalize().toString().replace('\\', '/');
                            assertTrue(files.contains(relative), "Reference missing from catalog: " + relative);
                        }
                    }
                }
            }
        }
        assertEquals(Set.copyOf(AiAgentChartType.codes()), types);
    }
}
