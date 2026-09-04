package ai.chat2db.spi;

import java.sql.Connection;
import java.util.Map;

/**
 * Provides dialect-specific database property inspection and alteration previews.
 */
public interface IDatabasePropertiesManager {

    Map<String, String> databaseInfo(Connection connection, String databaseName);

    String previewAlterDatabaseSql(Connection connection, String databaseName, String charset, String collation);
}
