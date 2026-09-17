package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.output.AgentOutputRead;
import ai.chat2db.community.domain.api.model.agent.output.AgentOutputSearch;
import ai.chat2db.community.domain.api.model.agent.output.AgentOutputUpload;
import ai.chat2db.community.tools.model.agent.tool.AgentOutputReference;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import ai.chat2db.community.domain.api.model.agent.skill.AiAgentSkill;
import ai.chat2db.community.domain.api.service.agent.IAiAgentOutputService;
import ai.chat2db.community.domain.api.service.agent.IAiAgentSkillService;
import ai.chat2db.community.domain.api.service.agent.IAiAgentWorkspaceService;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AiAgentFileAccessServiceImplTest {
    @TempDir Path temporary;

    @Test
    void systemResultsAndLoadedSkillsRemainReadableWhenUserToolsAreDisabledOrDirectoryChanges() throws Exception {
        Path root = temporary.toRealPath();
        Path managed = Files.createDirectories(root.resolve("history/sessions"));
        Path skill = Files.createDirectories(root.resolve("history/resources/skills/chart"));
        Files.writeString(skill.resolve("SKILL.md"), "chart instructions");
        Path result = managed.resolve("session/tool-results/run/out.txt");
        AtomicReference<String> cwd = new AtomicReference<>(root.resolve("deleted-user-directory").toString());
        Set<String> enabled = new HashSet<>();
        var access = service(managed, skill, cwd, enabled);
        assertEquals("artifact", ((AgentOutputRead) access.execute(context(), "read", Map.of("path", result.toString())).data()).content());
        assertEquals("chart instructions", ((AgentOutputRead) access.execute(context(), "read",
                Map.of("path", skill.resolve("SKILL.md").toString())).data()).content());
        cwd.set(root.toString());
        assertEquals("artifact", ((AgentOutputRead) access.execute(context(), "read", Map.of("path", result.toString())).data()).content());
        assertThrows(SecurityException.class, () -> access.execute(context(), "read",
                Map.of("path", managed.resolve("other/tool-results/run/out.txt").toString())));
    }

    @Test
    void directorySearchBudgetsEscapedJsonAndContinuesWithoutLosingMatches() throws Exception {
        Path root = temporary.toRealPath();
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path managed = Files.createDirectories(root.resolve("history/sessions"));
        Path skill = Files.createDirectories(root.resolve("history/resources/skills/chart"));
        for (int i = 0; i < 3; i++) Files.writeString(workspace.resolve("control-" + i + ".txt"), "match" + "\0".repeat(1900));
        var access = service(managed, skill, new AtomicReference<>(workspace.toString()), Set.of("grep"));
        Set<String> matched = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            var arguments = new java.util.HashMap<String, Object>();
            arguments.put("pattern", "match");
            if (cursor != null) arguments.put("cursor", cursor);
            var page = (AiAgentFileAccessServiceImpl.DirectorySearch) access.execute(context(), "grep", arguments).data();
            assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(page).length <= 16 * 1024);
            for (var match : page.matches()) assertTrue(matched.add((String) match.get("path")));
            cursor = page.nextCursor();
            assertTrue(++pages < 5);
        } while (cursor != null);
        assertEquals(3, matched.size());
        assertEquals(3, pages);
    }

    @Test
    void aliasesOfScopeRootsCannotBypassProtectionAndHistoricalResultPathsRemainReadable() throws Exception {
        Path root = temporary.toRealPath();
        Path managed = Files.createDirectories(root.resolve("history/sessions"));
        Path skill = Files.createDirectories(root.resolve("history/resources/skills/chart"));
        Files.writeString(skill.resolve("SKILL.md"), "skill");
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Files.writeString(workspace.resolve("user.txt"), "user");
        Path alias = root.resolve("root-alias"); Files.createSymbolicLink(alias, root);
        AtomicReference<String> cwd = new AtomicReference<>(root.toString());
        var access = service(managed, skill, cwd, Set.of("read", "write", "edit"));
        assertEquals("artifact", ((AgentOutputRead) access.execute(context(), "read",
                Map.of("path", alias.resolve("history/sessions/session/tool-results/result.txt").toString())).data()).content());
        assertEquals("artifact", ((AgentOutputRead) access.execute(context(), "read",
                Map.of("path", temporary.resolve("history/sessions/session/tool-results/result.txt").toString())).data()).content());
        assertThrows(SecurityException.class, () -> access.execute(context(), "read",
                Map.of("path", alias.resolve("history/sessions/other/tool-results/result.txt").toString())));
        assertThrows(SecurityException.class, () -> access.authorizeNative("session", "write", cwd.get(),
                Map.of("path", alias.resolve("history/sessions/session/tool-results/new.txt").toString())));
        assertThrows(SecurityException.class, () -> access.authorizeNative("session", "edit", cwd.get(),
                Map.of("path", alias.resolve("history/resources/skills/chart/SKILL.md").toString())));
        cwd.set(workspace.toString());
        assertEquals("user", ((AgentOutputRead) access.execute(context(), "read",
                Map.of("path", alias.resolve("workspace/user.txt").toString())).data()).content());
    }

    @Test
    void listingsSaveEveryObtainedEntryAndExplicitLimitsRemainVisible() throws Exception {
        Path root = temporary.toRealPath();
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path managed = Files.createDirectories(root.resolve("history/sessions"));
        Path skill = Files.createDirectories(root.resolve("history/resources/skills/chart"));
        for (int i = 0; i < 1400; i++) Files.createFile(workspace.resolve("entry-" + i + "-" + "x".repeat(40) + ".txt"));
        var captured = new ByteArrayOutputStream();
        var access = service(managed, skill, new AtomicReference<>(workspace.toString()), Set.of("ls", "find"), captured);
        var listed = (AiAgentFileAccessServiceImpl.FileResult) access.execute(context(), "ls", Map.of());
        assertTrue(listed.ok());
        assertEquals(1400L, ((Map<?, ?>) listed.data()).get("count"));
        assertEquals(false, ((Map<?, ?>) listed.data()).get("hasMore"));
        assertTrue(listed.output().complete());
        assertEquals(1400, captured.toString(java.nio.charset.StandardCharsets.UTF_8).lines().count());
        assertTrue(((List<?>) ((Map<?, ?>) listed.data()).get("entries")).size() < 100);
        var limited = (AiAgentFileAccessServiceImpl.FileResult) access.execute(context(), "find", Map.of("pattern", "*.txt", "limit", 1200));
        assertEquals(1200L, ((Map<?, ?>) limited.data()).get("count"));
        assertEquals(true, ((Map<?, ?>) limited.data()).get("hasMore"));
        assertTrue(limited.output().complete());
        assertEquals(1200, captured.toString(java.nio.charset.StandardCharsets.UTF_8).lines().count());
        var all = (AiAgentFileAccessServiceImpl.FileResult) access.execute(context(), "find", Map.of("pattern", "*.txt"));
        assertEquals(1400L, ((Map<?, ?>) all.data()).get("count"));
        assertEquals(false, ((Map<?, ?>) all.data()).get("hasMore"));
        assertEquals(1400, captured.toString(java.nio.charset.StandardCharsets.UTF_8).lines().count());
    }

    @Test
    void defaultWorkspaceRemainsWritableAndDirectorySearchPrunesOtherManagedData() throws Exception {
        Path root = temporary.toRealPath();
        Path managed = Files.createDirectories(root.resolve("history/sessions"));
        Path skill = Files.createDirectories(root.resolve("history/resources/skills/chart"));
        Files.writeString(skill.resolve("SKILL.md"), "managed secret");
        Path ownWorkspace = Files.createDirectories(root.resolve("history/workspaces/session"));
        Files.writeString(ownWorkspace.resolve("user.txt"), "match own workspace");
        Path otherWorkspace = Files.createDirectories(root.resolve("history/workspaces/other"));
        Files.writeString(otherWorkspace.resolve("user.txt"), "match secret");
        Files.writeString(root.resolve("public.txt"), "match public");
        AtomicReference<String> cwd = new AtomicReference<>(ownWorkspace.toString());
        var access = service(managed, skill, cwd, Set.of("read", "write", "grep"));
        assertDoesNotThrow(() -> access.authorizeNative("session", "write", cwd.get(), Map.of("path", "new.txt")));
        assertEquals("match own workspace", ((AgentOutputRead) access.execute(context(), "read", Map.of("path", "user.txt")).data()).content());
        assertThrows(SecurityException.class, () -> access.execute(context(), "read", Map.of("path", otherWorkspace.resolve("user.txt").toString())));
        cwd.set(root.toString());
        var search = (AiAgentFileAccessServiceImpl.DirectorySearch) access.execute(context(), "grep", Map.of("pattern", "match")).data();
        assertEquals(1, search.matches().size());
        assertEquals("match public", search.matches().get(0).get("content"));
    }

    @Test
    void userDirectoryPermissionsRejectTraversalSymlinksAndWritesToSystemFilesEvenFromAParentDirectory() throws Exception {
        Path root = temporary.toRealPath();
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path managed = Files.createDirectories(root.resolve("history/sessions"));
        Path skill = Files.createDirectories(root.resolve("history/resources/skills/chart"));
        Files.writeString(skill.resolve("SKILL.md"), "chart instructions");
        Files.writeString(workspace.resolve("user.txt"), "user content");
        Path outside = Files.writeString(root.resolve("outside.txt"), "private");
        Files.createSymbolicLink(workspace.resolve("link"), outside);
        AtomicReference<String> cwd = new AtomicReference<>(workspace.toString());
        Set<String> enabled = new HashSet<>();
        var access = service(managed, skill, cwd, enabled);
        assertThrows(SecurityException.class, () -> access.execute(context(), "read", Map.of("path", "user.txt")));
        enabled.addAll(List.of("read", "write", "edit", "grep"));
        assertEquals("user content", ((AgentOutputRead) access.execute(context(), "read", Map.of("path", "user.txt")).data()).content());
        assertThrows(SecurityException.class, () -> access.execute(context(), "read", Map.of("path", "../outside.txt")));
        assertThrows(SecurityException.class, () -> access.execute(context(), "read", Map.of("path", outside.toString())));
        assertThrows(SecurityException.class, () -> access.execute(context(), "read", Map.of("path", "link")));
        Files.createSymbolicLink(workspace.resolve("linked-directory"), root);
        assertThrows(SecurityException.class, () -> access.authorizeNative("session", "write", workspace.toString(),
                Map.of("path", "linked-directory/new.txt")));
        cwd.set(root.toString());
        assertThrows(SecurityException.class, () -> access.authorizeNative("session", "write", cwd.get(),
                Map.of("path", managed.resolve("session/tool-results/new.txt").toString())));
        assertThrows(SecurityException.class, () -> access.authorizeNative("session", "edit", cwd.get(),
                Map.of("path", skill.resolve("SKILL.md").toString())));
    }

    @Test
    void fixedSkillDirectoryAllowsFilesWithoutWorkspaceAccessButNeverShellOrResources() throws Exception {
        Path root = temporary.toRealPath();
        Path sources = Files.createDirectories(root.resolve("用户 skills"));
        Path builtin = Files.createDirectories(sources.resolveSibling("builtin"));
        Path skill = Files.createDirectories(builtin.resolve("chart"));
        Files.writeString(skill.resolve("SKILL.md"), "bundled");
        Path managed = Files.createDirectories(root.resolve("history/sessions"));
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path source = Files.createDirectories(sources.resolve("my-skill"));
        Path entry = Files.writeString(source.resolve("SKILL.md"), "user skill");
        var access = service(managed, skill, new AtomicReference<>(workspace.toString()), Set.of(), new ByteArrayOutputStream(), sources);
        assertEquals("user skill", ((AgentOutputRead) access.execute(context(), "read", Map.of("path", entry.toString())).data()).content());
        var wrongSnapshot = assertThrows(SecurityException.class, () -> access.execute(context(), "read",
                Map.of("path", builtin.resolve("missing/chart/SKILL.md").toString())));
        assertTrue(wrongSnapshot.getMessage().contains(skill.resolve("SKILL.md").toString()));
        assertEquals(sources.toString(), access.authorizedDirectory("session", "write", workspace.toString(),
                Map.of("path", source.resolve("references/新文件.md").toString())));
        assertDoesNotThrow(() -> access.authorizeNative("session", "edit", workspace.toString(), Map.of("path", entry.toString())));
        var listing = (Map<?, ?>) access.execute(context(), "ls", Map.of("path", sources.toString())).data();
        assertEquals(1L, listing.get("count"));
        assertEquals(1L, ((Map<?, ?>) access.execute(context(), "find", Map.of("path", sources.toString(), "pattern", "**/SKILL.md")).data()).get("count"));
        assertThrows(SecurityException.class, () -> access.authorizeNative("session", "write", workspace.toString(), Map.of("path", "normal.txt")));
        assertThrows(SecurityException.class, () -> access.authorizeNative("session", "edit", workspace.toString(), Map.of("path", skill.resolve("SKILL.md").toString())));
        assertThrows(SecurityException.class, () -> access.authorizeNative("session", "write", workspace.toString(), Map.of("path", sources.resolveSibling("用户 skills-outside/x").toString())));
        assertThrows(SecurityException.class, () -> access.authorizeNative("session", "bash", workspace.toString(), Map.of("command", "pwd")));
        Files.createSymbolicLink(sources.resolve("escape"), workspace);
        assertThrows(SecurityException.class, () -> access.authorizeNative("session", "write", workspace.toString(), Map.of("path", sources.resolve("escape/out.txt").toString())));
    }

    private AiAgentFileAccessServiceImpl service(Path managed, Path skill, AtomicReference<String> cwd, Set<String> enabled) {
        return service(managed, skill, cwd, enabled, new ByteArrayOutputStream());
    }

    private AiAgentFileAccessServiceImpl service(Path managed, Path skill, AtomicReference<String> cwd, Set<String> enabled,
            ByteArrayOutputStream captured) {
        return service(managed, skill, cwd, enabled, captured, null);
    }

    private AiAgentFileAccessServiceImpl service(Path managed, Path skill, AtomicReference<String> cwd, Set<String> enabled,
            ByteArrayOutputStream captured, Path userRoot) {
        IAiAgentWorkspaceService workspace = proxy(IAiAgentWorkspaceService.class, (method, args) -> switch (method) {
            case "isToolEnabled" -> enabled.contains(args[0]);
            case "resolveWorkingDirectory" -> cwd.get();
            default -> null;
        });
        IAiAgentSkillService skills = proxy(IAiAgentSkillService.class, (method, args) -> switch (method) {
            case "prepare", "selected" -> List.of(new AiAgentSkill("chart", skill.resolve("SKILL.md").toString(), "fixture"));
            case "resolveLegacyPath" -> args[0];
            case "userDirectory" -> userRoot;
            case "resourceDirectory" -> userRoot == null ? null : userRoot.resolveSibling("builtin");
            default -> null;
        });
        IAiAgentOutputService outputs = proxy(IAiAgentOutputService.class, (method, args) -> switch (method) {
            case "managedRoot" -> managed;
            case "begin" -> { captured.reset(); assertEquals("jsonl", args[1]); yield new AgentOutputUpload("upload"); }
            case "append" -> { byte[] chunk = Base64.getDecoder().decode((String) args[2]); assertTrue(chunk.length <= 48 * 1024); captured.write(chunk); yield null; }
            case "finish" -> new AgentOutputReference("file", "artifact", managed.resolve("session/tool-results/output.jsonl").toString(),
                    "jsonl", captured.size(), (Boolean) args[2], true, (String) args[3]);
            case "read" -> {
                if (!Path.of((String) args[2]).startsWith(managed.resolve((String) args[0]))) throw new SecurityException("Wrong session");
                yield new AgentOutputRead("artifact", null, false, 1, 1, false);
            }
            case "readFile" -> new AgentOutputRead(Files.readString((Path) args[0]), null, false, 1, 1, false);
            case "searchFile" -> {
                String text = Files.readString((Path) args[0]);
                yield new AgentOutputSearch(text.contains((String) args[1])
                        ? List.of(new AgentOutputSearch.Match(1, text, 0)) : List.of(), null, false, null);
            }
            default -> throw new AssertionError(method);
        });
        return new AiAgentFileAccessServiceImpl(List.of(workspace), skills, outputs);
    }

    private static ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext context() {
        return new ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext("session", "run", "call", 1L, event -> {}, () -> true);
    }

    private interface Invocation { Object call(String method, Object[] args) throws Exception; }
    private static <T> T proxy(Class<T> type, Invocation call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> call.call(method.getName(), args)));
    }
}
