package ai.chat2db.community.jcef.handler.biz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The desktop shell reaches the SQLX command line through the tools bridge, never by depending on
 * the implementation module, so the module boundary stays one-way.
 */
class SqlxDesktopBoundaryTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "ai", "chat2db", "community", "jcef");
    private static final String IMPLEMENTATION_PACKAGE = "ai.chat2db.community.sqlx.";

    @Test
    void handlersDoNotImportTheSqlxImplementation() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            List<String> offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains(IMPLEMENTATION_PACKAGE);
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .map(Path::toString)
                    .sorted()
                    .toList();
            assertTrue(offenders.isEmpty(), "handlers must use the tools bridge instead: " + offenders);
        }
    }

    @Test
    void jcefModuleKeepsItsToolsOnlyDependency() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        assertFalse(pom.contains("chat2db-community-sqlx"), pom);
    }
}
