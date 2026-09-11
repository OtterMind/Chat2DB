package ai.chat2db.community.jcef.utils;

import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.frame.MainJFrame;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cef.browser.CefBrowser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.junit.jupiter.api.Assertions.*;

class SingleInstanceUtilTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path temporary;
    private final List<Child> children = new ArrayList<>();

    @AfterEach
    void stopChildren() throws Exception {
        for (Child child : children) {
            if (child.process.isAlive()) {
                try {
                    child.command("STOP");
                } catch (IOException ignored) {
                    child.process.destroy();
                }
            }
        }
        for (Child child : children) {
            if (!child.process.waitFor(10, TimeUnit.SECONDS)) {
                child.process.destroyForcibly();
            }
            assertTrue(child.process.waitFor(10, TimeUnit.SECONDS), "Test process did not exit");
            child.outputReader.join(10000);
            assertFalse(child.outputReader.isAlive(), "Process output reader did not finish");
            assertNull(child.outputFailure, "Process output collection failed");
            child.process.getOutputStream().close();
            child.process.getInputStream().close();
            child.process.getErrorStream().close();
        }
    }

    @Test
    void secondaryNeverStartsServicesWithMcpEnabledOrDisabled() throws Exception {
        for (boolean mcp : List.of(false, true)) {
            Path state = temporary.resolve("state-" + mcp);
            Child primary = start(state, mcp ? 0 : -1);
            primary.awaitPrimary();
            Child secondary = start(state, primary.port());
            secondary.awaitSecondary();
            assertFalse(Files.exists(secondary.directory.resolve("initialized")));
            assertTrue(primary.process.isAlive());
        }
    }

    @Test
    void simultaneousLaunchesInitializeOnlyOneProcess() throws Exception {
        Path state = temporary.resolve("state");
        Child first = start(state, -1);
        Child second = start(state, -1);
        await(() -> Files.exists(first.status()) && Files.exists(second.status()));
        List<String> statuses = List.of(Files.readString(first.status()), Files.readString(second.status()));
        assertEquals(1, statuses.stream().filter("PRIMARY"::equals).count());
        assertEquals(1, statuses.stream().filter("SECONDARY"::equals).count());
    }

    @Test
    void receivedLaunchRequestsWaitForWindowReadiness() throws Exception {
        Path state = temporary.resolve("state");
        Path firstFile = Files.writeString(temporary.resolve("first file.sql"), "select 1");
        Path secondFile = Files.writeString(temporary.resolve("second file.sql"), "select 2");
        Child primary = start(state, -1, firstFile.toString());
        primary.awaitPrimary();
        start(state, -1, secondFile.toString()).awaitSecondary();
        assertFalse(Files.exists(primary.received()));

        primary.command("READY");
        await(() -> lineCount(primary.received()) == 2);
        List<String> received = readRequests(primary.received());
        assertEquals(firstFile.toString(), received.get(0));
        assertEquals(secondFile.toString(), received.get(1));
    }

    @Test
    void subsequentFileAndProtocolRequestsKeepWorking() throws Exception {
        Path state = temporary.resolve("state");
        Path sql = Files.writeString(temporary.resolve("query.sql"), "select 1");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        List<String> arguments = List.of(sql.toString(), "chat2db-community://open?console=test", sql.toString());
        for (int index = 0; index < arguments.size(); index++) {
            start(state, -1, arguments.get(index)).awaitSecondary();
            int expected = index + 2;
            await(() -> lineCount(primary.received()) == expected);
            assertEquals(arguments.get(index), readRequests(primary.received()).get(index + 1));
        }
    }

    @Test
    void repeatedRegistrationDoesNotReplayArguments() throws Exception {
        Path state = temporary.resolve("state");
        Path sql = Files.writeString(temporary.resolve("initial.sql"), "select 1");
        Child primary = start(state, -1, sql.toString());
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        primary.command("REGISTER_AGAIN");
        await(() -> Files.exists(primary.directory.resolve("registered-again")));
        assertEquals("true", Files.readString(primary.directory.resolve("registered-again")));
        start(state, -1).awaitSecondary();
        await(() -> lineCount(primary.received()) == 2);
        assertEquals(sql.toString(), readRequests(primary.received()).get(0));
        assertEquals("", readRequests(primary.received()).get(1));
    }

    @Test
    void doubleClickWithoutArgumentsStillRequestsActivation() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        start(state, -1).awaitSecondary();
        await(() -> lineCount(primary.received()) == 2);
        assertTrue(readRequests(primary.received()).stream().allMatch(String::isEmpty));
    }

    @Test
    void sameTimestampReplacementsDeliverChangedAndRepeatedArgumentsOnce() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        FileTime timestamp = FileTime.fromMillis(System.currentTimeMillis() - 10000);
        List<String> arguments = List.of("first.sql", "second.sql", "second.sql", "", "");
        for (int index = 0; index < arguments.size(); index++) {
            Path replacement = Files.createTempFile(state, "ipc-", ".tmp");
            Files.writeString(replacement, arguments.get(index));
            Files.setLastModifiedTime(replacement, timestamp);
            Files.move(replacement, state.resolve("app.ipc"), ATOMIC_MOVE, REPLACE_EXISTING);
            int expectedCount = index + 2;
            await(() -> lineCount(primary.received()) == expectedCount);
            assertEquals(arguments.get(index), readRequests(primary.received()).get(index + 1));
            Thread.sleep(250);
            assertEquals(expectedCount, lineCount(primary.received()), "Late file events repeated a request");
        }
    }

    @Test
    void inPlaceWriteWithTheSameTimestampIsDetectedFromFileEvents() throws Exception {
        Path state = temporary.resolve("state");
        Path ipc = state.resolve("app.ipc");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        primary.command("PAUSE_NEXT_DELIVERY");
        await(() -> Files.exists(primary.directory.resolve("pause-enabled")));
        publish(ipc, "first.sql");
        await(() -> Files.exists(primary.directory.resolve("delivery-paused")));
        FileTime timestamp = Files.getLastModifiedTime(ipc);
        try {
            Files.writeString(ipc, "other.sql");
            Files.setLastModifiedTime(ipc, timestamp);
        } finally {
            primary.command("RESUME_DELIVERY");
        }
        await(() -> lineCount(primary.received()) == 3);
        assertEquals("other.sql", readRequests(primary.received()).get(2));
        Thread.sleep(300);
        assertEquals(3, lineCount(primary.received()));
    }

    @Test
    void lockRemainsHeldUntilShutdownHooksAndServicesFinish() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, 0);
        primary.awaitPrimary();
        int port = primary.port();
        primary.command("EXIT");
        await(() -> Files.exists(primary.directory.resolve("stopping")));
        Child restarted = start(state, port);
        await(() -> restarted.output().contains("Waiting for the previous desktop instance to exit"));
        assertFalse(Files.exists(restarted.status()), "New instance initialized before the old process exited");
        Files.createFile(primary.directory.resolve("allow-exit"));
        assertTrue(primary.process.waitFor(10, TimeUnit.SECONDS));
        assertTrue(Files.exists(state.resolve("app.lock")));

        restarted.awaitPrimary();
        assertEquals(port, restarted.port());
    }

    @Test
    void concurrentRequestsAreConfirmedBeforeWindowReadinessWithoutOverwriting() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        List<String> expected = new ArrayList<>();
        List<Child> senders = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            Path file = Files.writeString(temporary.resolve("file-" + index + ".sql"), "select " + index);
            expected.add(file.toString());
            senders.add(start(state, -1, file.toString()));
        }
        for (Child sender : senders) { sender.awaitSecondary(); }
        assertFalse(Files.exists(primary.received()));
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 13);
        assertEquals(expected.stream().sorted().toList(),
                readRequests(primary.received()).subList(1, 13).stream().sorted().toList());
        assertFalse(Files.exists(state.resolve("app.ipc")), "Current senders still used the shared mailbox");
    }

    @Test
    void blockedWindowDispatchDoesNotBlockReceiptOrLoseRepeatedRequests() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        primary.command("PAUSE_NEXT_DELIVERY");
        await(() -> Files.exists(primary.directory.resolve("pause-enabled")));
        start(state, -1).awaitSecondary();
        await(() -> Files.exists(primary.directory.resolve("delivery-paused")));
        String file = Files.writeString(temporary.resolve("repeated.sql"), "select 1").toString();
        List<String> expected = List.of(file, "", "chat2db-community://open?console=test", file, "", file);
        try {
            List<Child> senders = new ArrayList<>();
            for (String argument : expected) {
                senders.add(argument.isEmpty() ? start(state, -1) : start(state, -1, argument));
            }
            for (Child sender : senders) { sender.awaitSecondary(); }
            assertEquals(2, lineCount(primary.received()));
        } finally {
            primary.command("RESUME_DELIVERY");
        }
        await(() -> lineCount(primary.received()) == expected.size() + 2);
        assertEquals(expected.stream().sorted().toList(),
                readRequests(primary.received()).subList(2, 8).stream().sorted().toList());
    }

    @Test
    void receiverRejectsWrongTokensAndSurvivesIncompleteRequests() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        var endpoint = MAPPER.readTree(state.resolve("app.ipc.endpoint").toFile());
        try (Socket socket = new Socket("127.0.0.1", endpoint.get("port").asInt())) {
            socket.setSoTimeout(5000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeUTF("wrong-token");
            output.flush();
            assertEquals(-1, socket.getInputStream().read());
        }
        try (Socket socket = new Socket("127.0.0.1", endpoint.get("port").asInt())) {
            socket.setSoTimeout(5000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeUTF(endpoint.get("token").asText());
            output.writeInt(10);
            output.writeByte(1);
            output.flush();
            assertEquals(-1, socket.getInputStream().read());
        }
        start(state, -1).awaitSecondary();
        await(() -> lineCount(primary.received()) == 2);
    }

    @Test
    void acknowledgedRequestsPreventExitUntilWindowHandoffCompletes() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        String file = temporary.resolve("queued.sql").toString();
        start(state, -1, file).awaitSecondary();
        assertExitResult(primary, "TRY_EXIT", "false");
        assertFalse(Files.exists(primary.directory.resolve("exit-action")));
        primary.command("READY_FRAME");
        await(() -> lineCount(primary.received()) == 2);
        assertEquals(List.of("", file), readRequests(primary.received()));
        assertExitResult(primary, "TRY_EXIT", "true");
        assertTrue(Files.exists(primary.directory.resolve("exit-action")));
    }

    @Test
    void initialStartupCanBeCancelledBeforeReadinessWithoutReopeningTheWindow() throws Exception {
        for (String initial : List.of("", temporary.resolve("initial.sql").toString())) {
            Child primary = initial.isEmpty() ? start(temporary.resolve("empty-state"), -1)
                    : start(temporary.resolve("file-state"), -1, initial);
            primary.awaitPrimary();
            assertExitResult(primary, "TRY_EXIT", "true");
            primary.command("READY_FRAME");
            primary.command("EDT_BARRIER");
            await(() -> Files.exists(primary.directory.resolve("edt-barrier")));
            Thread.sleep(300);
            assertFalse(Files.exists(primary.received()), "Cancelled startup reopened its window");
        }
    }

    @Test
    void exitOnEdtCannotDiscardAnAcknowledgedEdtPendingRequestOrDeadlock() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY_FRAME");
        await(() -> lineCount(primary.received()) == 1);
        primary.command("BLOCK_EDT");
        await(() -> Files.exists(primary.directory.resolve("edt-blocked")));
        String file = temporary.resolve("on-edt.sql").toString();
        start(state, -1, file).awaitSecondary();
        assertEquals(1, lineCount(primary.received()));
        assertExitResult(primary, "RESUME_EDT_AND_EXIT", "false");
        await(() -> lineCount(primary.received()) == 2);
        assertEquals(file, readRequests(primary.received()).get(1));
        assertExitResult(primary, "TRY_EXIT", "true");
    }

    @Test
    void realWindowHandlerFailuresRemainPendingAndAreRetriedOnEdt() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY_FRAME");
        await(() -> lineCount(primary.received()) == 1);
        primary.command("FAIL_FRAME");
        await(() -> Files.exists(primary.directory.resolve("frame-failing")));
        String file = temporary.resolve("retry.sql").toString();
        start(state, -1, file).awaitSecondary();
        await(() -> primary.output().contains("Cannot dispatch desktop launch request"));
        assertExitResult(primary, "TRY_EXIT", "false");
        primary.command("RECOVER_FRAME");
        await(() -> lineCount(primary.received()) == 2);
        assertEquals(List.of("", file), readRequests(primary.received()));
    }

    @Test
    void unsupportedAndMalformedProtocolArgumentsDoNotBlockLaterLaunchesOrExit() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY_FRAME");
        await(() -> lineCount(primary.received()) == 1);
        for (String argument : List.of("chat2db-community://restart", "chat2db-community://invalid space")) {
            start(state, -1, argument).awaitSecondary();
        }
        String file = temporary.resolve("after-invalid.sql").toString();
        start(state, -1, file).awaitSecondary();
        await(() -> lineCount(primary.received()) == 4);
        assertEquals(List.of("", "", "", file), readRequests(primary.received()));
        assertTrue(primary.output().contains("Cannot handle desktop launch argument"));
        assertExitResult(primary, "TRY_EXIT", "true");
    }

    @Test
    void aNewLaunchInvalidatesPendingCloseRestartAndUpdateConfirmations() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        int expected = 1;
        for (String action : List.of("CLOSE", "RESTART", "INSTALL_UPDATE")) {
            primary.command("REQUEST_" + action);
            await(() -> Files.exists(primary.directory.resolve("exit-requested")));
            Files.delete(primary.directory.resolve("exit-requested"));
            start(state, -1).awaitSecondary();
            int count = ++expected;
            await(() -> lineCount(primary.received()) == count);
            assertExitResult(primary, "CONFIRM_EXIT", "false");
            assertFalse(Files.exists(primary.directory.resolve("exit-action")));
        }
    }

    @Test
    void cancelledFailedAndRejectedExitsLeaveReceiptAndDispatchAvailable() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        primary.command("REQUEST_CLOSE");
        await(() -> Files.exists(primary.directory.resolve("exit-requested")));
        assertExitResult(primary, "CANCEL_EXIT", "true");
        int expected = 1;
        for (String command : List.of("REJECT_EXIT", "FAIL_EXIT", "REJECT_EXIT")) {
            assertExitResult(primary, command, "false");
            start(state, -1).awaitSecondary();
            int count = ++expected;
            await(() -> lineCount(primary.received()) == count);
        }
        assertExitResult(primary, "TRY_EXIT", "true");
        Child next = start(state, -1);
        await(() -> next.output().contains("Waiting for the previous desktop instance to exit"));
        assertFalse(Files.exists(next.status()));
        primary.command("STOP");
        assertTrue(primary.process.waitFor(10, TimeUnit.SECONDS));
        next.awaitPrimary();
    }

    @Test
    void launchLockAcquisitionUsesTheOperationDeadline() throws Exception {
        Path state = Files.createDirectories(temporary.resolve("state"));
        try (FileChannel channel = FileChannel.open(state.resolve("app.launch.lock"), CREATE, WRITE);
             FileLock ignored = channel.lock()) {
            Child sender = start(ShortDeadlineProcess.class, state, -1, null);
            assertTrue(sender.process.waitFor(5, TimeUnit.SECONDS), sender::output);
            assertNotEquals(0, sender.process.exitValue());
            assertTrue(sender.output().contains("Timed out waiting for desktop instance"), sender::output);
            assertFalse(Files.exists(sender.directory.resolve("initialized")));
        }
        start(state, -1).awaitPrimary();
    }

    @Test
    void legacyRequestDuringAFailedExitIsDeliveredAfterRecovery() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        primary.command("HOLD_FAILED_EXIT");
        await(() -> Files.exists(primary.directory.resolve("exit-running")));
        String file = temporary.resolve("legacy-during-exit.sql").toString();
        publish(state.resolve("app.ipc"), file);
        Thread.sleep(500);
        assertEquals(1, lineCount(primary.received()));
        Files.createFile(primary.directory.resolve("resume-exit"));
        await(() -> Files.exists(primary.directory.resolve("exit-result")));
        assertEquals("false", Files.readString(primary.directory.resolve("exit-result")));
        await(() -> lineCount(primary.received()) == 2);
        assertEquals(file, readRequests(primary.received()).get(1));
        start(state, -1).awaitSecondary();
        await(() -> lineCount(primary.received()) == 3);
    }

    @Test
    void readinessArrivingDuringAFailedExitIsRetainedForRecovery() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("HOLD_FAILED_EXIT");
        await(() -> Files.exists(primary.directory.resolve("exit-running")));
        primary.command("READY_FRAME");
        primary.command("EDT_BARRIER");
        await(() -> Files.exists(primary.directory.resolve("edt-barrier")));
        assertFalse(Files.exists(primary.received()));
        Files.createFile(primary.directory.resolve("resume-exit"));
        await(() -> Files.exists(primary.directory.resolve("exit-result")));
        assertEquals("false", Files.readString(primary.directory.resolve("exit-result")));
        await(() -> lineCount(primary.received()) == 1);
        start(state, -1).awaitSecondary();
        await(() -> lineCount(primary.received()) == 2);
    }

    @Test
    void launchLockWaitHonorsThreadInterruption() throws Exception {
        Path state = Files.createDirectories(temporary.resolve("state"));
        try (FileChannel channel = FileChannel.open(state.resolve("app.launch.lock"), CREATE, WRITE);
             FileLock ignored = channel.lock()) {
            Child sender = start(ShortDeadlineProcess.class, state, -1, null, "interrupt");
            assertTrue(sender.process.waitFor(5, TimeUnit.SECONDS), sender::output);
            assertNotEquals(0, sender.process.exitValue());
            assertTrue(sender.output().contains("Interrupted while waiting"), sender::output);
        }
    }

    @Test
    void slowPartialAuthenticationAndPayloadCannotStarveAnOverlappingLauncher() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        var endpoint = MAPPER.readTree(state.resolve("app.ipc.endpoint").toFile());
        int expected = 1;
        for (boolean authenticated : List.of(false, true)) {
            try (Socket slow = new Socket("127.0.0.1", endpoint.get("port").asInt())) {
                DataOutputStream output = new DataOutputStream(slow.getOutputStream());
                if (authenticated) {
                    output.writeUTF(endpoint.get("token").asText());
                    output.writeInt(64);
                } else {
                    output.writeShort(64);
                }
                output.writeByte(1);
                output.flush();
                Thread drip = new Thread(() -> {
                    try {
                        for (int index = 0; index < 30; index++) {
                            Thread.sleep(300);
                            output.writeByte(1);
                            output.flush();
                        }
                    } catch (IOException ignored) {
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
                drip.start();
                try {
                    Child healthy = start(state, -1);
                    healthy.awaitSecondary();
                    int count = ++expected;
                    await(() -> lineCount(primary.received()) == count);
                    slow.setSoTimeout(1000);
                    assertEquals(-1, slow.getInputStream().read());
                } finally {
                    drip.interrupt();
                    drip.join(2000);
                    assertFalse(drip.isAlive());
                }
            }
        }
    }

    private void assertExitResult(Child primary, String command, String expected) throws Exception {
        Path result = primary.directory.resolve("exit-result");
        Files.deleteIfExists(result);
        primary.command(command);
        await(() -> Files.exists(result));
        assertEquals(expected, Files.readString(result), primary::output);
    }

    @Test
    void aFailedDeliveryIsRetriedWithoutStoppingOtherRequests() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        primary.command("FAIL_NEXT_DELIVERY");
        await(() -> Files.exists(primary.directory.resolve("failure-enabled")));
        start(state, -1).awaitSecondary();
        await(() -> lineCount(primary.received()) == 2);
        assertTrue(primary.output().contains("Cannot dispatch desktop launch request"));
        start(state, -1).awaitSecondary();
        await(() -> lineCount(primary.received()) == 3);
    }

    @Test
    void operatingSystemReleasesLockAfterCrash() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("CRASH");
        assertTrue(primary.process.waitFor(10, TimeUnit.SECONDS));
        start(state, -1).awaitPrimary();
    }

    @Test
    void invalidLockLocationDoesNotStartAnUnprotectedInstance() throws Exception {
        Path state = Files.writeString(temporary.resolve("not-a-directory"), "test");
        Child child = start(state, -1);
        assertTrue(child.process.waitFor(10, TimeUnit.SECONDS));
        assertNotEquals(0, child.process.exitValue());
        assertFalse(Files.exists(child.directory.resolve("initialized")));
    }

    @Test
    void onlyDesktopGuiRuntimeUsesTheInstanceGate() {
        assertTrue(SingleInstanceUtil.requiresInstanceLock("DESKTOP", null, "community", false, false));
        assertTrue(SingleInstanceUtil.requiresInstanceLock("DESKTOP", "true", "extension", false, false));
        assertFalse(SingleInstanceUtil.requiresInstanceLock("DESKTOP", "false", "community", false, false));
        assertFalse(SingleInstanceUtil.requiresInstanceLock("DESKTOP", "true", "cli", false, false));
        assertFalse(SingleInstanceUtil.requiresInstanceLock("DESKTOP", "true", "community", true, false));
        assertFalse(SingleInstanceUtil.requiresInstanceLock(null, null, "community", false, false));
        assertFalse(SingleInstanceUtil.requiresInstanceLock("DESKTOP", "true", "community", false, true));
    }

    @Test
    void relativeFileNamedLikeAProtocolUsesTheSendersDirectory() throws Exception {
        Path state = temporary.resolve("state");
        Path sender = Files.createDirectories(temporary.resolve("sender"));
        Path sql = Files.writeString(sender.resolve("chat2db-export.sql"), "select 1");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        start(InstanceProcess.class, state, -1, sender, sql.getFileName().toString()).awaitSecondary();
        await(() -> lineCount(primary.received()) == 2);
        String argument = readRequests(primary.received()).get(1);
        assertTrue(Path.of(argument).isAbsolute());
        assertTrue(Files.isSameFile(sql, Path.of(argument)));
    }

    @Test
    void ipcReadFailureDoesNotStopTheListenerOrRepeatRequests() throws Exception {
        Path state = temporary.resolve("state");
        Path ipc = state.resolve("app.ipc");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        Files.createDirectory(ipc);
        try {
            primary.command("READY");
            await(() -> primary.output().contains("waiting for recovery"));
        } finally {
            Files.delete(ipc);
        }
        start(state, -1).awaitSecondary();
        await(() -> lineCount(primary.received()) == 2);
        Thread.sleep(300);
        assertEquals(2, lineCount(primary.received()), "Unchanged IPC content was delivered twice");
        start(state, -1).awaitSecondary();
        await(() -> lineCount(primary.received()) == 3);
    }

    @Test
    void legacySenderRequestsAreRetainedUntilTheNewWindowIsReady() throws Exception {
        Path state = temporary.resolve("state");
        Path sql = Files.writeString(temporary.resolve("legacy.sql"), "select 1");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        Files.writeString(state.resolve("app.ipc"), sql.toString());
        Thread.sleep(300);
        assertFalse(Files.exists(primary.received()));
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 2);
        assertTrue(readRequests(primary.received()).contains(sql.toString()));
    }

    @Test
    void newSenderUsesTheLegacyOwnersProtocol() throws Exception {
        Path state = temporary.resolve("state");
        Path sql = Files.writeString(temporary.resolve("legacy.sql"), "select 1");
        Child legacy = start(LegacyInstanceProcess.class, state, -1, null);
        legacy.awaitPrimary();
        start(state, -1, sql.toString()).awaitSecondary();
        await(() -> lineCount(legacy.received()) == 1);
        assertEquals(sql.toString(), readRequests(legacy.received()).get(0));
        assertFalse(Files.exists(state.resolve("app.ipc.d")), "New sender queued a request the old owner cannot read");
    }

    @Test
    void staleLegacyRequestIsNotReplayedWhenStartingANewInstance() throws Exception {
        Path state = Files.createDirectories(temporary.resolve("state"));
        Files.writeString(state.resolve("app.ipc"), "chat2db-community://open?console=old");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        start(state, -1).awaitSecondary();
        await(() -> lineCount(primary.received()) == 2);
        assertTrue(readRequests(primary.received()).stream().allMatch(String::isEmpty));
    }

    private Child start(Path state, int port, String... arguments) throws Exception {
        return start(InstanceProcess.class, state, port, null, arguments);
    }

    private Child start(Class<?> mainClass, Path state, int port, Path workingDirectory, String... arguments) throws Exception {
        Path directory = Files.createTempDirectory(temporary, "process-");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Duser.home=" + directory,
                "-DsocksProxyHost=127.0.0.1", "-DsocksProxyPort=1", "-DsocksNonProxyHosts=",
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                mainClass.getName(), state.toString(), directory.toString(), String.valueOf(port)));
        command.addAll(Arrays.asList(arguments));
        Path argumentFile = directory.resolve("java.args");
        Files.write(argumentFile, command.subList(1, command.size()).stream()
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList());
        Process process = new ProcessBuilder(command.get(0), "@" + argumentFile).redirectErrorStream(true)
                .directory(workingDirectory == null ? directory.toFile() : workingDirectory.toFile()).start();
        Child child = new Child(process, directory);
        children.add(child);
        return child;
    }

    private static long lineCount(Path path) {
        try {
            return Files.readAllLines(path).size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static List<String> readRequests(Path path) throws Exception {
        List<String> result = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            result.add(MAPPER.readValue(line, String.class));
        }
        return result;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for subprocess");
    }

    private static final class Child {
        private final Process process;
        private final Path directory;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final Thread outputReader;
        private volatile IOException outputFailure;

        private Child(Process process, Path directory) {
            this.process = process;
            this.directory = directory;
            outputReader = new Thread(() -> {
                try {
                    process.getInputStream().transferTo(output);
                } catch (IOException exception) {
                    outputFailure = exception;
                }
            }, "instance-test-output");
            outputReader.setDaemon(true);
            outputReader.start();
        }

        Path status() { return directory.resolve("status"); }
        Path received() { return directory.resolve("received.jsonl"); }
        int port() throws Exception { return Integer.parseInt(Files.readString(directory.resolve("initialized"))); }

        void awaitPrimary() throws Exception {
            await(() -> Files.exists(status()) || !process.isAlive());
            assertTrue(Files.exists(status()), () -> "Child failed: " + output());
            assertEquals("PRIMARY", Files.readString(status()), this::output);
        }

        void awaitSecondary() throws Exception {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), this::output);
            assertEquals(0, process.exitValue(), this::output);
            assertEquals("SECONDARY", Files.readString(status()), this::output);
        }

        void command(String command) throws Exception {
            process.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
        }

        String output() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static void publish(Path path, String value) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), "status-", ".tmp");
        Files.writeString(temporary, value);
        Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING);
    }

    public static final class InstanceProcess {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[1]);
            if (!SingleInstanceUtil.registerInstance(Path.of(args[0]), Arrays.copyOfRange(args, 3, args.length))) {
                publish(directory.resolve("status"), "SECONDARY");
                return;
            }
            int port = Integer.parseInt(args[2]);
            ServerSocket server = port < 0 ? null : new ServerSocket(port, 1, InetAddress.getLoopbackAddress());
            publish(directory.resolve("initialized"), String.valueOf(server == null ? -1 : server.getLocalPort()));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.createFile(directory.resolve("stopping"));
                    await(() -> Files.exists(directory.resolve("allow-exit")));
                    if (server != null) { server.close(); }
                } catch (Exception exception) { throw new RuntimeException(exception); }
            }));
            publish(directory.resolve("status"), "PRIMARY");
            AtomicReference<CountDownLatch> deliveryPause = new AtomicReference<>();
            AtomicBoolean failNextDelivery = new AtomicBoolean();
            AtomicReference<CountDownLatch> edtPause = new AtomicReference<>();
            LaunchFrame frame = LaunchFrame.create(directory);
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String command;
            while ((command = input.readLine()) != null) {
                switch (command) {
                    case "READY_FRAME" -> SingleInstanceUtil.onReady(frame::handleLaunchRequest);
                    case "EDT_BARRIER" -> SwingUtilities.invokeAndWait(() -> {
                        try { Files.createFile(directory.resolve("edt-barrier")); }
                        catch (IOException exception) { throw new RuntimeException(exception); }
                    });
                    case "FAIL_FRAME" -> {
                        frame.fail = true;
                        Files.createFile(directory.resolve("frame-failing"));
                    }
                    case "RECOVER_FRAME" -> frame.fail = false;
                    case "BLOCK_EDT" -> {
                        CountDownLatch pause = new CountDownLatch(1);
                        edtPause.set(pause);
                        SwingUtilities.invokeLater(() -> {
                            try {
                                Files.createFile(directory.resolve("edt-blocked"));
                                if (!pause.await(10, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("EDT was not resumed");
                                }
                                attemptExit(directory, "TRY_EXIT");
                            } catch (Exception exception) { throw new RuntimeException(exception); }
                        });
                    }
                    case "RESUME_EDT_AND_EXIT" -> edtPause.get().countDown();
                    case "TRY_EXIT", "FAIL_EXIT", "REJECT_EXIT" -> attemptExit(directory, command);
                    case "HOLD_FAILED_EXIT" -> new Thread(() -> {
                        try { attemptExit(directory, "HOLD_FAILED_EXIT"); }
                        catch (Exception exception) { throw new RuntimeException(exception); }
                    }).start();
                    case "REQUEST_CLOSE", "REQUEST_RESTART", "REQUEST_INSTALL_UPDATE" -> {
                        Field browser = JcefContext.class.getDeclaredField("browser_");
                        browser.setAccessible(true);
                        browser.set(JcefContext.getInstance(), Proxy.newProxyInstance(
                                CefBrowser.class.getClassLoader(), new Class<?>[]{CefBrowser.class},
                                (proxy, method, parameters) -> null));
                        ApplicationExitCoordinator.markFrontendReady();
                        if (!ApplicationExitCoordinator.request(command.substring(8), "test-exit", () -> {
                            try { Files.createFile(directory.resolve("exit-action")); }
                            catch (IOException exception) { throw new RuntimeException(exception); }
                            return true;
                        }, 30000) || !ApplicationExitCoordinator.acknowledge("test-exit")) {
                            throw new IllegalStateException("Exit request was not accepted");
                        }
                        Files.createFile(directory.resolve("exit-requested"));
                    }
                    case "CONFIRM_EXIT" -> publish(directory.resolve("exit-result"),
                            String.valueOf(ApplicationExitCoordinator.confirm("test-exit")));
                    case "CANCEL_EXIT" -> publish(directory.resolve("exit-result"),
                            String.valueOf(ApplicationExitCoordinator.cancel("test-exit")));
                    case "READY" -> SingleInstanceUtil.onReady(argument -> {
                        if (!SwingUtilities.isEventDispatchThread()) {
                            throw new IllegalStateException("Window handler must run on EDT");
                        }
                        if (failNextDelivery.getAndSet(false)) {
                            throw new IllegalStateException("Injected dispatch failure");
                        }
                        try {
                            Files.writeString(directory.resolve("received.jsonl"),
                                    MAPPER.writeValueAsString(argument) + "\n", CREATE, APPEND);
                            CountDownLatch pause = deliveryPause.get();
                            if (pause != null) {
                                Files.createFile(directory.resolve("delivery-paused"));
                                if (!pause.await(15, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("Test did not resume delivery");
                                }
                            }
                        } catch (Exception exception) { throw new RuntimeException(exception); }
                    });
                    case "EXIT" -> System.exit(0);
                    case "PAUSE_NEXT_DELIVERY" -> {
                        deliveryPause.set(new CountDownLatch(1));
                        Files.createFile(directory.resolve("pause-enabled"));
                    }
                    case "RESUME_DELIVERY" -> deliveryPause.getAndSet(null).countDown();
                    case "FAIL_NEXT_DELIVERY" -> {
                        failNextDelivery.set(true);
                        Files.createFile(directory.resolve("failure-enabled"));
                    }
                    case "REGISTER_AGAIN" -> publish(directory.resolve("registered-again"), String.valueOf(
                            SingleInstanceUtil.registerInstance(Path.of(args[0]), Arrays.copyOfRange(args, 3, args.length))));
                    case "STOP" -> {
                        Files.writeString(directory.resolve("allow-exit"), "");
                        System.exit(0);
                    }
                    case "CRASH" -> Runtime.getRuntime().halt(0);
                    default -> throw new IllegalArgumentException(command);
                }
            }
        }

        private static void attemptExit(Path directory, String command) throws Exception {
            ApplicationExitCoordinator.markFrontendUnavailable();
            boolean result;
            try {
                result = ApplicationExitCoordinator.request("CLOSE", "immediate-exit", () -> {
                    try {
                        if ("HOLD_FAILED_EXIT".equals(command)) {
                            Files.createFile(directory.resolve("exit-running"));
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                            while (!Files.exists(directory.resolve("resume-exit")) && System.nanoTime() < deadline) {
                                Thread.sleep(25);
                            }
                            return false;
                        }
                        if (!"TRY_EXIT".equals(command)) {
                            Thread.sleep(300);
                            if ("FAIL_EXIT".equals(command)) {
                                throw new IllegalStateException("Injected exit failure");
                            }
                            return false;
                        }
                        Files.createFile(directory.resolve("exit-action"));
                        return true;
                    } catch (IOException | InterruptedException exception) { throw new RuntimeException(exception); }
                });
            } catch (IllegalStateException exception) {
                if (!"FAIL_EXIT".equals(command)) { throw exception; }
                result = false;
            }
            publish(directory.resolve("exit-result"), String.valueOf(result));
        }
    }

    public static final class ShortDeadlineProcess {
        public static void main(String[] args) throws Exception {
            if (args.length > 3) {
                Thread main = Thread.currentThread();
                new Thread(() -> {
                    try { Thread.sleep(100); }
                    catch (InterruptedException exception) { throw new RuntimeException(exception); }
                    main.interrupt();
                }).start();
            }
            SingleInstanceUtil.registerInstance(Path.of(args[0]), new String[0], Duration.ofMillis(400));
            publish(Path.of(args[1]).resolve("initialized"), "unexpected");
        }
    }

    // Exercise the real window handler without constructing native AWT/JCEF resources.
    public static final class LaunchFrame extends MainJFrame {
        private Path directory;
        private String argument;
        private volatile boolean fail;

        static LaunchFrame create(Path directory) throws Exception {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            LaunchFrame frame = (LaunchFrame) ((sun.misc.Unsafe) field.get(null)).allocateInstance(LaunchFrame.class);
            frame.directory = directory;
            return frame;
        }

        @Override public void processUri(URI uri) {
            if (uri == null || !"file".equals(uri.getScheme())) {
                super.processUri(uri);
            } else {
                argument = Path.of(uri).toString();
            }
        }
        @Override public void setVisible(boolean visible) {
            if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Not on EDT"); }
            if (fail) { throw new IllegalStateException("Injected window handoff failure"); }
            try {
                Files.writeString(directory.resolve("received.jsonl"),
                        MAPPER.writeValueAsString(argument == null ? "" : argument) + "\n", CREATE, APPEND);
            } catch (IOException exception) { throw new RuntimeException(exception); }
            argument = null;
        }
        @Override public void setExtendedState(int state) { }
        @Override public int getExtendedState() { return java.awt.Frame.ICONIFIED; }
        @Override public void toFront() { }
        @Override public void requestFocus() { }
    }

    public static final class LegacyInstanceProcess {
        public static void main(String[] args) throws Exception {
            Path state = Files.createDirectories(Path.of(args[0]));
            Path directory = Path.of(args[1]);
            try (FileChannel channel = FileChannel.open(state.resolve("app.lock"), CREATE, WRITE);
                 FileLock lock = channel.lock()) {
                publish(directory.resolve("initialized"), "-1");
                publish(directory.resolve("status"), "PRIMARY");
                Thread listener = new Thread(() -> {
                    try {
                        Path ipc = state.resolve("app.ipc");
                        while (!Files.exists(ipc)) { Thread.sleep(25); }
                        Files.writeString(directory.resolve("received.jsonl"),
                                MAPPER.writeValueAsString(Files.readString(ipc)) + "\n");
                    } catch (Exception exception) { throw new RuntimeException(exception); }
                });
                listener.setDaemon(true);
                listener.start();
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            }
        }
    }
}
