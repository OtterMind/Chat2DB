package ai.chat2db.plugin.mysql.builder;

import ai.chat2db.community.domain.api.model.metadata.Tablespace;
import ai.chat2db.plugin.mysql.MysqlSqlGuards;
import ai.chat2db.plugin.mysql.identifier.MysqlIdentifierProcessor;
import ai.chat2db.spi.sql.builder.ITablespaceSqlBuilder;
import org.apache.commons.lang3.StringUtils;

import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_ALTER_TABLESPACE_ADD_DATAFILE_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_ALTER_TABLESPACE_RENAME_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_CREATE_TABLESPACE_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_DROP_TABLESPACE_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_TABLESPACE_FILE_BLOCK_SIZE_ASSIGNMENT;

/**
 * Builds DDL for InnoDB General Tablespaces.
 *
 * <p>Data-file paths are user-supplied and belong to the MySQL server filesystem; they are emitted
 * verbatim (escaped) and never validated, canonicalized, or written by the application. Identifiers
 * are validated with {@link MysqlSqlGuards#requireMysqlName} and quoted with
 * {@link MysqlIdentifierProcessor#quoteIdentifierAlways}.
 */
public class MysqlTablespaceSqlBuilder implements ITablespaceSqlBuilder {

    @Override
    public String buildCreateTablespace(Tablespace tablespace) {
        String name = MysqlSqlGuards.requireMysqlName(tablespace.getName(), "tablespace name");
        String quotedName = MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways(name);
        // A general tablespace has at least one data file; use the first if provided.
        String dataFile = firstDataFile(tablespace);
        String escapedDataFile = MysqlIdentifierProcessor.INSTANCE.escapeString(dataFile);
        String fileBlockSizeClause = "";
        if (tablespace.getFileBlockSize() != null && tablespace.getFileBlockSize() > 0) {
            fileBlockSizeClause = String.format(SQL_TABLESPACE_FILE_BLOCK_SIZE_ASSIGNMENT,
                    tablespace.getFileBlockSize());
        }
        return String.format(SQL_CREATE_TABLESPACE_TEMPLATE, quotedName, escapedDataFile,
                fileBlockSizeClause);
    }

    @Override
    public String buildDropTablespace(String tablespaceName) {
        String name = MysqlSqlGuards.requireMysqlName(tablespaceName, "tablespace name");
        return String.format(SQL_DROP_TABLESPACE_TEMPLATE,
                MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways(name));
    }

    @Override
    public String buildRenameTablespace(String oldTablespaceName, String newTablespaceName) {
        String oldName = MysqlSqlGuards.requireMysqlName(oldTablespaceName, "tablespace name");
        String newName = MysqlSqlGuards.requireMysqlName(newTablespaceName, "tablespace name");
        return String.format(SQL_ALTER_TABLESPACE_RENAME_TEMPLATE,
                MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways(oldName),
                MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways(newName));
    }

    @Override
    public String buildAlterTablespaceAddDatafile(String tablespaceName, String dataFile) {
        String name = MysqlSqlGuards.requireMysqlName(tablespaceName, "tablespace name");
        String escapedDataFile = MysqlIdentifierProcessor.INSTANCE.escapeString(dataFile);
        return String.format(SQL_ALTER_TABLESPACE_ADD_DATAFILE_TEMPLATE,
                MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways(name), escapedDataFile);
    }

    private String firstDataFile(Tablespace tablespace) {
        if (tablespace.getDataFiles() != null && !tablespace.getDataFiles().isEmpty()) {
            return tablespace.getDataFiles().get(0);
        }
        return StringUtils.EMPTY;
    }
}
