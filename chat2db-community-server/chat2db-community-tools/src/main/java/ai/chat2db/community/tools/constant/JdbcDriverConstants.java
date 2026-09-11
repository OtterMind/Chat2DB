package ai.chat2db.community.tools.constant;


import ai.chat2db.community.tools.util.ConfigUtils;
import java.io.File;

public final class JdbcDriverConstants {

    public static final String DRIVER_LIB_PATH = ConfigUtils.getBasePath() + File.separator
            + "jdbc-lib" + File.separator;
    public static final String DRIVER_UPLOAD_PATH = DRIVER_LIB_PATH + ".uploads" + File.separator;
    public static final String DOWNLOAD_URL_HOST = "https://cdn.chat2db-ai.com/lib/";

    private JdbcDriverConstants() {
    }
}
