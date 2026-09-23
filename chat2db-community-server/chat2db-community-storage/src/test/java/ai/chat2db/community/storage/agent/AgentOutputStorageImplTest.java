package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentSessionStatus;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentSession;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext;
import ai.chat2db.community.storage.StorageFileUtils;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeBinding;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AgentOutputStorageImplTest {
    @TempDir Path directory;

    @Test
    void canonicalizesExistingRootAliasesBeforeCreatingTheHistoryDirectory() throws Exception {
        Path base = Files.createDirectory(directory.resolve("real-base"));
        Path alias = directory.resolve("alias-base");
        Files.createSymbolicLink(alias, base);
        var paths = new AgentV2StoragePaths(alias.resolve("storage/history"));
        assertEquals(base.toRealPath().resolve("storage/history"), paths.root());
        var files = new StorageFileUtils();
        var sessions = new LocalAgentSessionStorage(paths, files);
        sessions.create(session("session"));
        var storage = new AgentOutputStorageImpl(paths, files, sessions, 1000, 2000, 3000);
        var output = storage.save(context("session", "call"), "text", out -> out.write(new byte[]{65}));
        assertEquals(Path.of(output.path()).toRealPath(), Path.of(output.path()));
        assertTrue(Path.of(output.path()).startsWith(storage.managedRoot()));
    }

    @Test
    void readsEveryUtf8ByteOfASingleLongLineAndSurvivesRestart() throws Exception {
        Fixture fixture = fixture(1_000_000, 2_000_000, 3_000_000);
        String original = "金额9007199254740993.1200\t😀".repeat(4000);
        var reference = fixture.storage.save(context("session", "call"), "text", out -> out.write(original.getBytes(StandardCharsets.UTF_8)));
        assertEquals("file", reference.mode());
        assertTrue(reference.complete());
        var restarted = new AgentOutputStorageImpl(fixture.paths, fixture.files, fixture.sessions, 1_000_000, 2_000_000, 3_000_000);
        String cursor = null;
        StringBuilder rebuilt = new StringBuilder();
        do {
            var page = restarted.read("session", 1L, reference.artifactId(), cursor, null, 200);
            assertTrue(page.content().getBytes(StandardCharsets.UTF_8).length <= 16 * 1024);
            assertFalse(page.content().contains("�"));
            assertEquals(Boolean.TRUE, page.complete());
            assertNull(page.warning());
            rebuilt.append(page.content());
            cursor = page.nextCursor();
            assertEquals(cursor != null, page.hasMore());
        } while (cursor != null);
        assertEquals(original, rebuilt.toString());
        assertTrue(restarted.reference("session", 1L, reference.path()).complete());
    }

    @Test
    void preservesLineOffsetsSearchPaginationAndBoundaryMatches() throws Exception {
        Fixture fixture = fixture(1_000_000, 2_000_000, 3_000_000);
        String original = "x".repeat(16 * 1024 - 3) + "NeedleAcrossBoundary\n"
                + "first hello\nsecond HELLO\nthird hello\n";
        var reference = fixture.storage.save(context("session", "call"), "text", out -> out.write(original.getBytes(StandardCharsets.UTF_8)));
        var boundary = fixture.storage.search("session", 1L, reference.artifactId(), "NeedleAcrossBoundary", true, false, null, 100);
        assertEquals(1, boundary.matches().size());
        assertEquals(1, boundary.matches().get(0).line());
        var first = fixture.storage.search("session", 1L, reference.path(), "hello", true, true, null, 1);
        assertEquals(2, first.matches().get(0).line());
        assertTrue(first.hasMore());
        var second = fixture.storage.search("session", 1L, reference.path(), "hello", true, true, first.nextCursor(), 1);
        assertEquals(3, second.matches().get(0).line());
        var read = fixture.storage.read("session", 1L, reference.path(), null, 3, 1);
        assertEquals("second HELLO\n", read.content());
        assertEquals(3, read.startLine());
        var regex = fixture.storage.search("session", 1L, reference.path(), "^(first|third) hello$", false, false, null, 100);
        assertEquals(List.of(2L, 4L), regex.matches().stream().map(value -> value.line()).toList());
    }

    @Test
    void isolatesUsersSessionsAndRejectsTraversalAndSymlinks() throws Exception {
        Fixture fixture = fixture(10000, 20000, 30000);
        var output = fixture.storage.save(context("session", "call"), "text", out -> out.write("private".getBytes(StandardCharsets.UTF_8)));
        assertThrows(RuntimeException.class, () -> fixture.storage.read("session", 2L, output.artifactId(), null, null, null));
        assertThrows(RuntimeException.class, () -> fixture.storage.read("other", 1L, output.path(), null, null, null));
        assertThrows(RuntimeException.class, () -> fixture.storage.read("session", 1L,
                Path.of(output.path()).getParent() + "/../run/" + Path.of(output.path()).getFileName(), null, null, null));
        Path target = Path.of(output.path());
        Path outside = directory.resolve("outside.txt");
        Files.writeString(outside, "outside");
        Files.delete(target);
        Files.createSymbolicLink(target, outside);
        assertThrows(RuntimeException.class, () -> fixture.storage.read("session", 1L, output.artifactId(), null, null, null));
        assertEquals("outside", Files.readString(outside));
    }

    @Test
    void honorsFileSessionAndGlobalQuotasAcrossConcurrentUploads() {
        Fixture fixture = fixture(20, 30, 40);
        var first = context("session", "one");
        var second = context("session", "two");
        var third = context("other", "three");
        var one = fixture.storage.begin(first, "text");
        var two = fixture.storage.begin(second, "text");
        var three = fixture.storage.begin(third, "text");
        fixture.storage.append(first, one.uploadId(), new byte[25]);
        fixture.storage.append(second, two.uploadId(), new byte[25]);
        fixture.storage.append(third, three.uploadId(), new byte[25]);
        var a = fixture.storage.finish(first, one.uploadId(), true, null);
        var b = fixture.storage.finish(second, two.uploadId(), true, null);
        var c = fixture.storage.finish(third, three.uploadId(), true, null);
        assertEquals(20, a.sizeBytes());
        assertEquals(10, b.sizeBytes());
        assertEquals(10, c.sizeBytes());
        assertFalse(a.complete());
        assertFalse(b.complete());
        assertFalse(c.complete());
        assertNotNull(a.warning());
    }

    @Test
    void replayReturnsTheSameOutputAndInterruptedCaptureRemainsPartial() throws Exception {
        Fixture fixture = fixture(10000, 20000, 30000);
        var invocation = context("session", "one");
        var first = fixture.storage.save(invocation, "text", out -> out.write("original".getBytes(StandardCharsets.UTF_8)));
        var repeated = fixture.storage.save(invocation, "text", out -> out.write("replacement".getBytes(StandardCharsets.UTF_8)));
        assertEquals(first, repeated);
        assertEquals("original", fixture.storage.read("session", 1L, first.artifactId(), null, null, null).content());
        var partial = fixture.storage.save(context("session", "two"), "text", out -> {
            out.write("accepted".getBytes(StandardCharsets.UTF_8));
            throw new java.io.IOException("source interrupted");
        });
        assertEquals("file", partial.mode());
        assertFalse(partial.complete());
        assertTrue(partial.warning().contains("source interrupted"));
        var page = fixture.storage.read("session", 1L, partial.artifactId(), null, null, null);
        assertEquals(Boolean.FALSE, page.complete());
        assertTrue(page.warning().contains("source interrupted"));
        var matches = fixture.storage.search("session", 1L, partial.artifactId(), "accepted", true, false, null, 10);
        assertEquals(1, matches.matches().size());
        assertTrue(matches.warning().contains("source interrupted"));
        assertNull(fixture.storage.readFile(Path.of(partial.path()), null, null, null).complete());
        assertThrows(RuntimeException.class, () -> fixture.storage.append(context("other", "one"), first.artifactId(), new byte[1]));
    }

    @Test
    void partialSourceWarningIsBoundedAndSurvivesRestartAndPagination() throws Exception {
        Fixture fixture = fixture(100000, 200000, 300000);
        var invocation = context("session", "partial");
        var upload = fixture.storage.begin(invocation, "text");
        fixture.storage.append(invocation, upload.uploadId(), "\0".repeat(20000).getBytes(StandardCharsets.UTF_8));
        var reference = fixture.storage.finish(invocation, upload.uploadId(), false, "Cancelled: " + "😀\0".repeat(500));
        var restarted = new AgentOutputStorageImpl(fixture.paths, fixture.files, fixture.sessions, 100000, 200000, 300000);
        var first = restarted.read("session", 1L, reference.artifactId(), null, null, null);
        assertEquals(Boolean.FALSE, first.complete());
        assertTrue(first.warning().startsWith("Cancelled: "));
        assertFalse(first.warning().contains("�"));
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(first).length <= 16384);
        var next = restarted.read("session", 1L, reference.artifactId(), first.nextCursor(), null, null);
        assertEquals(Boolean.FALSE, next.complete());
        assertEquals(first.warning(), next.warning());
        var search = restarted.search("session", 1L, reference.artifactId(), "x", false, false, null, 10);
        assertTrue(search.warning().startsWith("Cancelled: "));
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(search).length <= 16384);
    }

    @Test
    void quotaNeverPublishesHalfAUtf8Character() {
        Fixture fixture = fixture(5, 20, 30);
        var output = fixture.storage.save(context("session", "call"), "text",
                out -> out.write("😀😀".getBytes(StandardCharsets.UTF_8)));
        assertEquals(4, output.sizeBytes());
        assertFalse(output.complete());
        assertEquals("😀", fixture.storage.read("session", 1L, output.artifactId(), null, null, null).content());
    }

    @Test
    void regularExpressionsCannotCauseBacktrackingOrUseUnsupportedLookaround() {
        Fixture fixture = fixture(1_000_000, 2_000_000, 3_000_000);
        var output = fixture.storage.save(context("session", "call"), "text",
                out -> out.write(("a".repeat(50000) + "!").getBytes(StandardCharsets.UTF_8)));
        assertTimeout(java.time.Duration.ofSeconds(2), () ->
                fixture.storage.search("session", 1L, output.artifactId(), "^(a+)+$", false, false, null, 100));
        assertThrows(IllegalArgumentException.class, () ->
                fixture.storage.search("session", 1L, output.artifactId(), "(?=a)", false, false, null, 100));
    }

    @Test
    void boundsTheEncodedResponseEvenForControlCharacters() throws Exception {
        Fixture fixture = fixture(1_000_000, 2_000_000, 3_000_000);
        var output = fixture.storage.save(context("session", "call"), "text",
                out -> out.write("\u0001".repeat(50000).getBytes(StandardCharsets.UTF_8)));
        var page = fixture.storage.read("session", 1L, output.artifactId(), null, null, null);
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(page).length <= 16384);
        assertTrue(page.hasMore());
        var next = fixture.storage.read("session", 1L, output.artifactId(), page.nextCursor(), null, null);
        assertFalse(next.content().isEmpty());
    }

    @Test
    void cleansOnlyUnpublishedTempsAndDeletesOutputsWithTheSession() throws Exception {
        Fixture fixture = fixture(10000, 20000, 30000);
        var published = fixture.storage.save(context("session", "one"), "text", out -> out.write("retained".getBytes(StandardCharsets.UTF_8)));
        var pending = fixture.storage.begin(context("session", "two"), "text");
        fixture.storage.append(context("session", "two"), pending.uploadId(), "pending".getBytes(StandardCharsets.UTF_8));
        Path temporary = fixture.paths.toolResultsDirectory("session", "run").resolve(pending.uploadId() + ".part");
        assertTrue(Files.exists(temporary));
        var restarted = new AgentOutputStorageImpl(fixture.paths, fixture.files, fixture.sessions, 10000, 20000, 30000);
        restarted.begin(context("session", "three"), "text");
        assertFalse(Files.exists(temporary));
        assertEquals("retained", restarted.read("session", 1L, published.artifactId(), null, null, null).content());
        fixture.sessions.delete("session", 1L);
        assertFalse(Files.exists(Path.of(published.path())));
    }

    private Fixture fixture(long file, long session, long total) {
        var paths = new AgentV2StoragePaths(directory.resolve("history"));
        var files = new StorageFileUtils();
        var sessions = new LocalAgentSessionStorage(paths, files);
        sessions.create(session("session"));
        sessions.create(session("other"));
        return new Fixture(paths, files, sessions, new AgentOutputStorageImpl(paths, files, sessions, file, session, total));
    }
    private AgentToolExecutionContext context(String session, String call) {
        return new AgentToolExecutionContext(session, "run", call, 1L, ignored -> { }, () -> true);
    }
    private AgentSession session(String id) {
        var now = LocalDateTime.now();
        return new AgentSession(2, id, 1L,
                new AgentDefinition("default", "Default", null, "prompt", AgentRuntimeType.PI, "model", 1),
                new AgentRuntimeBinding(AgentRuntimeType.PI, "0.85.1", "rpc", id, null, 1), AgentSessionStatus.READY,
                "Conversation", 0, now, now);
    }
    private record Fixture(AgentV2StoragePaths paths, StorageFileUtils files, LocalAgentSessionStorage sessions,
            AgentOutputStorageImpl storage) { }
}
