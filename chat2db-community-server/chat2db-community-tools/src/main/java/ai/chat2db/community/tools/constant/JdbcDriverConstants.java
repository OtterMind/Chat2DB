package ai.chat2db.community.tools.constant;


import ai.chat2db.community.tools.util.ConfigUtils;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class JdbcDriverConstants {

    /**
     * @deprecated Use {@link #getDriverLibPath()} so the path reflects the active runtime home.
     */
    @Deprecated
    public static final String DRIVER_LIB_PATH = getDriverLibPath();

    public static final String DOWNLOAD_URL_HOST = "https://cdn.chat2db-ai.com/lib/";

    private JdbcDriverConstants() {
    }

    public static String getDriverLibPath() {
        return ConfigUtils.getBasePath() + File.separator + "jdbc-lib" + File.separator;
    }

    public static File createDriverLibDirectory() throws IOException {
        Path directory = Path.of(getDriverLibPath());
        Files.createDirectories(directory);
        return directory.toFile();
    }
}
