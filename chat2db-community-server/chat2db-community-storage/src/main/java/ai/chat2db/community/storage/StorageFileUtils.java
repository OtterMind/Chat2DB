package ai.chat2db.community.storage;

import ai.chat2db.community.tools.exception.storage.StorageException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.Comparator;
import java.util.List;

@Component
public class StorageFileUtils {

    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    public void createPrivateDirectory(Path directory) {
        rejectSymbolicLink(directory);
        try {
            Files.createDirectories(directory);
            applyPrivateDirectoryPermissions(directory);
        } catch (IOException exception) {
            throw new StorageException("Failed to create storage directory", exception);
        }
    }

    public void writeAtomically(Path target, String content) {
        Path temporary = null;
        try {
            Path parent = target.getParent();
            if (parent == null) {
                throw new StorageException("Storage target must have a parent directory");
            }
            rejectSymbolicLink(parent);
            rejectSymbolicLink(target);
            createPrivateDirectory(parent);
            temporary = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
            applyPrivateFilePermissions(temporary);
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            replaceStorageFile(temporary, target);
        } catch (IOException exception) {
            throw new StorageException("Failed to persist storage file", exception);
        } finally {
            deleteTemporaryFile(temporary);
        }
    }

    public void verifyInsideRoot(Path root, Path path) {
        try {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path normalizedPath = path.toAbsolutePath().normalize();
            if (!normalizedPath.startsWith(normalizedRoot)) {
                throw new StorageException("Storage path escapes its root");
            }
            rejectSymbolicLink(normalizedRoot);
            Path existing = Files.exists(normalizedPath, LinkOption.NOFOLLOW_LINKS)
                    ? normalizedPath : normalizedPath.getParent();
            if (existing == null) {
                throw new StorageException("Storage path has no existing parent");
            }
            Path cursor = normalizedRoot;
            for (Path segment : normalizedRoot.relativize(existing)) {
                cursor = cursor.resolve(segment);
                rejectSymbolicLink(cursor);
            }
            if (!existing.toRealPath().startsWith(normalizedRoot.toRealPath())) {
                throw new StorageException("Storage path escapes its root");
            }
        } catch (IOException exception) {
            throw new StorageException("Failed to validate storage path", exception);
        }
    }

    public void rejectSymbolicLink(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new StorageException("Symbolic links are not allowed in storage paths");
        }
    }

    public void deleteEmptyDirectory(Path directory) {
        try {
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // Preserve the original storage failure; a later create rejects the incomplete directory.
        }
    }

    public void deleteTree(Path root, Path target) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (normalizedTarget.equals(normalizedRoot)) {
            throw new StorageException("Storage root cannot be deleted as a resource tree");
        }
        if (!Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        verifyInsideRoot(normalizedRoot, normalizedTarget);
        try (var entries = Files.walk(normalizedTarget)) {
            List<Path> paths = entries.toList();
            paths.forEach(this::rejectSymbolicLink);
            for (Path path : paths.stream().sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException exception) {
            throw new StorageException("Failed to delete storage resource tree", exception);
        }
    }

    protected void replaceStorageFile(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void applyPrivateDirectoryPermissions(Path directory) throws IOException {
        try {
            Files.setPosixFilePermissions(directory, PRIVATE_DIRECTORY_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems retain their inherited access-control entries.
        }
    }

    private void applyPrivateFilePermissions(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PRIVATE_FILE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems retain their inherited access-control entries.
        }
    }

    private void deleteTemporaryFile(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // A later startup cleanup may remove a temporary file owned by the storage root.
        }
    }
}
