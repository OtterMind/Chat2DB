package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskSpec;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.tools.util.ManagedTaskInputFiles;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
final class TaskInputCleanup {

    private final Path trustedRoot;

    @Autowired
    TaskInputCleanup(@Value("${chat2db.task.import.staging-directory:}") String configuredRoot) {
        this(StringUtils.isBlank(configuredRoot)
                ? Path.of(ConfigUtils.getBasePath(), "task-inputs") : Path.of(configuredRoot));
    }

    TaskInputCleanup(Path trustedRoot) {
        this.trustedRoot = trustedRoot.toAbsolutePath().normalize();
    }

    InputReference reference(TaskSpec spec) {
        if (!(spec instanceof ImportTaskSpec importSpec) || !importSpec.isTemporarySourceFile()
                || importSpec.getSourceFile() == null || importSpec.getSourceFile().isBlank()) {
            return null;
        }
        if (importSpec.getTemporarySourceToken() == null || importSpec.getTemporarySourceToken().isBlank()) {
            throw new IllegalArgumentException("Temporary task input cleanup token is required");
        }
        return new InputReference(importSpec.getSourceFile(), importSpec.getTemporarySourceToken());
    }

    Runnable forSpec(TaskSpec spec, Runnable completed) {
        InputReference reference = reference(spec);
        if (reference == null) {
            return null;
        }
        return () -> {
            if (!delete(reference)) {
                throw new IllegalStateException("Temporary task input cleanup is pending");
            }
            if (completed != null) {
                completed.run();
            }
        };
    }

    boolean delete(InputReference reference) {
        return reference != null && ManagedTaskInputFiles.cleanup(
                trustedRoot, reference.sourceFile(), reference.cleanupToken());
    }

    record InputReference(String sourceFile, String cleanupToken) {
    }
}
