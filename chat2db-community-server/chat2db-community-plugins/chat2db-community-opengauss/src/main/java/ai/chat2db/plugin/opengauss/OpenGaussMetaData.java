package ai.chat2db.plugin.opengauss;

import ai.chat2db.plugin.postgresql.PostgreSQLMetaData;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static ai.chat2db.plugin.opengauss.constant.OpenGaussMetaDataConstants.SEARCH_PATH_STATEMENT_PREFIX;
import static ai.chat2db.plugin.opengauss.constant.OpenGaussMetaDataConstants.SYSTEM_DATABASES;
import static ai.chat2db.plugin.opengauss.constant.OpenGaussMetaDataConstants.SYSTEM_SCHEMAS;
import static ai.chat2db.plugin.opengauss.constant.OpenGaussMetaDataConstants.TABLE_DDL_SQL;

@Slf4j
public class OpenGaussMetaData extends PostgreSQLMetaData implements IDbMetaData {

    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        String ddl = DefaultSQLExecutor.getInstance().preExecute(connection, TABLE_DDL_SQL,
                new String[]{schemaName, tableName}, resultSet -> resultSet.next() ? resultSet.getString(1) : null);
        return stripLeadingSearchPath(ddl);
    }

    static String stripLeadingSearchPath(String ddl) {
        if (ddl == null) {
            return null;
        }

        String normalizedDdl = ddl.stripLeading();
        if (!normalizedDdl.regionMatches(true, 0, SEARCH_PATH_STATEMENT_PREFIX, 0,
                SEARCH_PATH_STATEMENT_PREFIX.length())) {
            return ddl;
        }

        int prefixEnd = SEARCH_PATH_STATEMENT_PREFIX.length();
        if (prefixEnd < normalizedDdl.length()
                && Character.isJavaIdentifierPart(normalizedDdl.charAt(prefixEnd))) {
            return ddl;
        }

        char quote = 0;
        for (int i = prefixEnd; i < normalizedDdl.length(); i++) {
            char current = normalizedDdl.charAt(i);
            if (quote != 0) {
                if (current == quote) {
                    if (i + 1 < normalizedDdl.length() && normalizedDdl.charAt(i + 1) == quote) {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == ';') {
                return normalizedDdl.substring(i + 1).stripLeading();
            }
        }
        return ddl;
    }

    @Override
    public List<Database> databases(Connection connection) {
        log.info("OpenGaussMetaData databases");
        List<Database> databases = super.databases(connection);
        if(CollectionUtils.isEmpty(databases)){
            log.info("OpenGaussMetaData_1");
            return databases;
        }
        List<Database> result = new ArrayList<>();
        for (Database database : databases) {
            if(SYSTEM_DATABASES.contains(database.getName())){
                continue;
            }
            result.add(database);
        }
        return result;
    }

    @Override
    public List<Schema> schemas(Connection connection, String database) {
        List<Schema> schemas = super.schemas(connection,database);
        if(CollectionUtils.isEmpty(schemas)){
            return schemas;
        }
        List<Schema> result = new ArrayList<>();
        for (Schema schema : schemas) {
            if(SYSTEM_SCHEMAS.contains(schema.getName())){
                continue;
            }
            result.add(schema);
        }
        return result;
    }
}
