package ai.chat2db.community.web.api.adapter.file;

import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.tools.util.ManagedTaskInputFiles;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@Lazy(false)
public class BoundedTaskImportUploadAdapter implements TaskImportUploadService {

    static final String FILE_TOO_LARGE_CODE = "task.import.fileTooLarge";
    static final String FILE_UPLOAD_FAILED_CODE = "task.import.fileUploadFailed";

    private static final int COPY_BUFFER_BYTES = 8192;
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path stagingDirectory;
    private final long maxFileBytes;
    private final boolean forceUnsupportedPermissions;

    @Autowired
    public BoundedTaskImportUploadAdapter(
            @Value("${chat2db.task.import.max-upload-bytes:536870912}") long maxFileBytes,
            @Value("${chat2db.task.import.staging-directory:}") String configuredDirectory) {
        this(StringUtils.isBlank(configuredDirectory)
                ? Path.of(ConfigUtils.getBasePath(), "task-inputs") : Path.of(configuredDirectory),
                maxFileBytes, false);
    }

    BoundedTaskImportUploadAdapter(Path stagingDirectory, long maxFileBytes, boolean forceUnsupportedPermissions) {
        this.stagingDirectory = stagingDirectory.toAbsolutePath().normalize();
        this.maxFileBytes = Math.max(1L, maxFileBytes);
        this.forceUnsupportedPermissions = forceUnsupportedPermissions;
    }

    @PostConstruct
    void reconcileOrphans() {
        ManagedTaskInputFiles.cleanupOrphans(stagingDirectory);
    }

    @Override
    public StagedTaskInput stage(MultipartFile file) {
        if (file.getSize() < 0L || file.getSize() > maxFileBytes) {
            throw fileTooLarge();
        }

        StagedPaths staged = null;
        try {
            staged = createSecureStagedPaths();
            try (InputStream input = file.getInputStream();
                    OutputStream output = Files.newOutputStream(staged.source(), StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                copyBounded(input, output);
            }
            return new StagedTaskInput(staged.source().toString(), staged.cleanupToken());
        } catch (BusinessException e) {
            cleanupPartial(staged, e);
            throw e;
        } catch (IOException | SecurityException e) {
            cleanupPartial(staged, e);
            throw new BusinessException(FILE_UPLOAD_FAILED_CODE, null, e);
        }
    }

    @Override
    public boolean cleanup(StagedTaskInput input) {
        return input != null && ManagedTaskInputFiles.cleanup(
                stagingDirectory, input.sourceFile(), input.cleanupToken());
    }

    private StagedPaths createSecureStagedPaths() throws IOException {
        PermissionMode permissionMode = ensureSecureDirectory();
        Path source = null;
        Path marker = null;
        try {
            source = createSecureTemporaryFile(permissionMode);
            String cleanupToken = UUID.randomUUID().toString();
            marker = createSecureFile(ManagedTaskInputFiles.markerPath(source, cleanupToken), permissionMode);
            return new StagedPaths(source, marker, cleanupToken);
        } catch (IOException | RuntimeException e) {
            deleteDirect(marker, e);
            deleteDirect(source, e);
            throw e;
        }
    }

    private PermissionMode ensureSecureDirectory() throws IOException {
        if (forceUnsupportedPermissions) {
            throw new IOException("Owner-only task input permissions are unsupported");
        }
        Path parent = stagingDirectory.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)) {
            throw new IOException("Task input staging parent is unavailable or untrusted");
        }
        PermissionMode mode = permissionMode(parent);
        if (!Files.exists(stagingDirectory, LinkOption.NOFOLLOW_LINKS)) {
            if (mode == PermissionMode.POSIX) {
                Files.createDirectory(stagingDirectory,
                        PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
            } else {
                Files.createDirectory(stagingDirectory, aclAttribute(Files.getOwner(parent), true));
            }
        }
        if (Files.isSymbolicLink(stagingDirectory)
                || !Files.isDirectory(stagingDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Task import staging directory is untrusted");
        }
        applyAndVerifyDirectoryPermissions(mode);
        return mode;
    }

    private PermissionMode permissionMode(Path path) throws IOException {
        FileStore store = Files.getFileStore(path);
        if (store.supportsFileAttributeView(PosixFileAttributeView.class)) {
            return PermissionMode.POSIX;
        }
        if (Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
            return PermissionMode.ACL;
        }
        throw new IOException("Owner-only task input permissions are unsupported");
    }

    private void applyAndVerifyDirectoryPermissions(PermissionMode mode) throws IOException {
        if (mode == PermissionMode.POSIX) {
            Files.setPosixFilePermissions(stagingDirectory, DIRECTORY_PERMISSIONS);
            if (!DIRECTORY_PERMISSIONS.equals(Files.getPosixFilePermissions(stagingDirectory))) {
                throw new IOException("Could not verify owner-only task input directory permissions");
            }
            return;
        }
        setOwnerOnlyAcl(stagingDirectory, true);
        verifyOwnerOnlyAcl(stagingDirectory);
    }

    private Path createSecureTemporaryFile(PermissionMode mode) throws IOException {
        if (mode == PermissionMode.POSIX) {
            return Files.createTempFile(stagingDirectory, ManagedTaskInputFiles.FILE_PREFIX,
                    ManagedTaskInputFiles.FILE_SUFFIX, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        }
        return Files.createTempFile(stagingDirectory, ManagedTaskInputFiles.FILE_PREFIX,
                ManagedTaskInputFiles.FILE_SUFFIX, aclAttribute(Files.getOwner(stagingDirectory), false));
    }

    private Path createSecureFile(Path path, PermissionMode mode) throws IOException {
        if (mode == PermissionMode.POSIX) {
            return Files.createFile(path, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        }
        return Files.createFile(path, aclAttribute(Files.getOwner(stagingDirectory), false));
    }

    private FileAttribute<List<AclEntry>> aclAttribute(UserPrincipal owner, boolean directory) {
        List<AclEntry> acl = List.of(ownerEntry(owner, directory));
        return new FileAttribute<>() {
            @Override
            public String name() {
                return "acl:acl";
            }

            @Override
            public List<AclEntry> value() {
                return acl;
            }
        };
    }

    private void setOwnerOnlyAcl(Path path, boolean directory) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("ACL task input permissions are unsupported");
        }
        view.setAcl(List.of(ownerEntry(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS), directory)));
    }

    private AclEntry ownerEntry(UserPrincipal owner, boolean directory) {
        AclEntry.Builder entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) {
            entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        return entry.build();
    }

    private void verifyOwnerOnlyAcl(Path path) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        if (view == null || view.getAcl().size() != 1
                || !owner.equals(view.getAcl().get(0).principal())) {
            throw new IOException("Could not verify owner-only task input ACL");
        }
    }

    private void copyBounded(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (read > maxFileBytes - total) {
                throw fileTooLarge();
            }
            output.write(buffer, 0, read);
            total += read;
        }
    }

    private BusinessException fileTooLarge() {
        return new BusinessException(FILE_TOO_LARGE_CODE, new Object[]{maxFileBytes});
    }

    private void cleanupPartial(StagedPaths staged, Throwable failure) {
        if (staged == null) {
            return;
        }
        if (!ManagedTaskInputFiles.cleanup(stagingDirectory,
                staged.source().toString(), staged.cleanupToken())) {
            failure.addSuppressed(new IOException("Partial task input cleanup was deferred"));
        }
    }

    private void deleteDirect(Path path, Throwable failure) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException | SecurityException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private enum PermissionMode {
        POSIX,
        ACL
    }

    private record StagedPaths(Path source, Path marker, String cleanupToken) {
    }
}
