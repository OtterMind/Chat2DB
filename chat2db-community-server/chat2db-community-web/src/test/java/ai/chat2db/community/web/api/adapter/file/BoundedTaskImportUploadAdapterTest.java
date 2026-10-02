package ai.chat2db.community.web.api.adapter.file;

import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ManagedTaskInputFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BoundedTaskImportUploadAdapterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void declaredOversizeIsRejectedBeforeOpeningTheUploadStream() throws IOException {
        AtomicBoolean streamOpened = new AtomicBoolean();
        MockMultipartFile file = new MockMultipartFile("file", "large.csv", "text/csv", new byte[]{1}) {
            @Override
            public long getSize() {
                return 5L;
            }

            @Override
            public InputStream getInputStream() throws IOException {
                streamOpened.set(true);
                return super.getInputStream();
            }
        };
        BoundedTaskImportUploadAdapter adapter = adapter(4L, false, "declared");

        BusinessException exception = assertThrows(BusinessException.class, () -> adapter.stage(file));

        assertEquals(BoundedTaskImportUploadAdapter.FILE_TOO_LARGE_CODE, exception.getCode());
        assertFalse(streamOpened.get());
        assertFalse(Files.exists(temporaryDirectory.resolve("declared")));
    }

    @Test
    void streamOversizeRemovesThePartialFileAndDurableMarker() throws IOException {
        byte[] bytes = new byte[]{1, 2, 3, 4, 5};
        MockMultipartFile file = new MockMultipartFile("file", "large.csv", "text/csv", bytes) {
            @Override
            public long getSize() {
                return 1L;
            }
        };
        Path stagingDirectory = temporaryDirectory.resolve("partial");
        BoundedTaskImportUploadAdapter adapter = new BoundedTaskImportUploadAdapter(stagingDirectory, 4L, false);

        BusinessException exception = assertThrows(BusinessException.class, () -> adapter.stage(file));

        assertEquals(BoundedTaskImportUploadAdapter.FILE_TOO_LARGE_CODE, exception.getCode());
        try (var stagedFiles = Files.list(stagingDirectory)) {
            assertEquals(0L, stagedFiles.count());
        }
    }

    @Test
    void exactLimitIsCopiedWithCleanupIdentityMarker() throws IOException {
        byte[] bytes = new byte[]{1, 2, 3, 4};
        BoundedTaskImportUploadAdapter adapter = adapter(bytes.length, false, "accepted");

        TaskImportUploadService.StagedTaskInput staged = adapter.stage(
                new MockMultipartFile("file", "data.csv", "text/csv", bytes));
        Path source = Path.of(staged.sourceFile());

        assertArrayEquals(bytes, Files.readAllBytes(source));
        assertTrue(Files.exists(ManagedTaskInputFiles.markerPath(source, staged.cleanupToken())));
        assertTrue(adapter.cleanup(staged));
        assertFalse(Files.exists(source));
    }

    @Test
    void posixStagingUsesAtomicOwnerOnlyDirectoryFileAndMarkerPermissions() throws IOException {
        FileStore store = Files.getFileStore(temporaryDirectory);
        assumeTrue(store.supportsFileAttributeView(PosixFileAttributeView.class));
        Path stagingDirectory = temporaryDirectory.resolve("posix");
        BoundedTaskImportUploadAdapter adapter =
                new BoundedTaskImportUploadAdapter(stagingDirectory, 4L, false);

        TaskImportUploadService.StagedTaskInput staged = adapter.stage(
                new MockMultipartFile("file", "data.csv", "text/csv", new byte[]{1}));
        Path source = Path.of(staged.sourceFile());

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(source));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(ManagedTaskInputFiles.markerPath(source, staged.cleanupToken())));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(stagingDirectory));
    }

    @Test
    void aclStagingRestrictsDirectoryFileAndMarkerToTheOwner() throws IOException {
        FileStore store = Files.getFileStore(temporaryDirectory);
        assumeFalse(store.supportsFileAttributeView(PosixFileAttributeView.class));
        assumeTrue(Files.getFileAttributeView(temporaryDirectory, AclFileAttributeView.class) != null);
        Path stagingDirectory = temporaryDirectory.resolve("acl");
        BoundedTaskImportUploadAdapter adapter =
                new BoundedTaskImportUploadAdapter(stagingDirectory, 4L, false);

        TaskImportUploadService.StagedTaskInput staged = adapter.stage(
                new MockMultipartFile("file", "data.csv", "text/csv", new byte[]{1}));
        Path source = Path.of(staged.sourceFile());

        assertOwnerOnlyAcl(stagingDirectory);
        assertOwnerOnlyAcl(source);
        assertOwnerOnlyAcl(ManagedTaskInputFiles.markerPath(source, staged.cleanupToken()));
    }

    @Test
    void unsupportedOwnerOnlyPermissionsFailClosedBeforeCreatingTheDirectory() {
        Path stagingDirectory = temporaryDirectory.resolve("unsupported");
        BoundedTaskImportUploadAdapter adapter =
                new BoundedTaskImportUploadAdapter(stagingDirectory, 4L, true);

        BusinessException exception = assertThrows(BusinessException.class, () -> adapter.stage(
                new MockMultipartFile("file", "data.csv", "text/csv", new byte[]{1})));

        assertEquals(BoundedTaskImportUploadAdapter.FILE_UPLOAD_FAILED_CODE, exception.getCode());
        assertFalse(Files.exists(stagingDirectory));
    }

    @Test
    void durableMarkerRetriesRejectedSubmissionCleanupOnReconciliation() throws IOException {
        BoundedTaskImportUploadAdapter adapter = adapter(4L, false, "retry");
        TaskImportUploadService.StagedTaskInput staged = adapter.stage(
                new MockMultipartFile("file", "data.csv", "text/csv", new byte[]{1}));
        Path source = Path.of(staged.sourceFile());
        Path marker = ManagedTaskInputFiles.markerPath(source, staged.cleanupToken());

        assertFalse(adapter.cleanup(new TaskImportUploadService.StagedTaskInput(
                source.toString(), "wrong-token")));
        assertTrue(Files.exists(source));
        adapter.reconcileOrphans();

        assertFalse(Files.exists(source));
        assertFalse(Files.exists(marker));
    }

    private void assertOwnerOnlyAcl(Path path) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        assertEquals(1, view.getAcl().size());
        assertEquals(owner, view.getAcl().get(0).principal());
    }

    private BoundedTaskImportUploadAdapter adapter(long maxBytes, boolean unsupported, String directory) {
        return new BoundedTaskImportUploadAdapter(
                temporaryDirectory.resolve(directory), maxBytes, unsupported);
    }
}
