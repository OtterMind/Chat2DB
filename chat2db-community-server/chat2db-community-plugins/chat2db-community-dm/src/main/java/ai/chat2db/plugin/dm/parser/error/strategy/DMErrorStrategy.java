package ai.chat2db.plugin.dm.parser.error.strategy;

import ai.chat2db.spi.parser.error.strategy.BaseErrorStrategy;
import ai.chat2db.plugin.dm.parser.base.DMParser;
import org.antlr.v4.runtime.misc.IntervalSet;

public class DMErrorStrategy extends BaseErrorStrategy {

    private static final int[] stmtBeginToken = new int[]{
            DMParser.SELECT, DMParser.INSERT, DMParser.UPDATE,
            DMParser.DELETE, DMParser.ALTER, DMParser.DROP,
            DMParser.ADMINISTER, DMParser.ANALYZE, DMParser.BEGIN,
            DMParser.COMMIT, DMParser.CREATE, DMParser.DECLARE,
            DMParser.EXECUTE, DMParser.GRANT, DMParser.RENAME,
            DMParser.REVOKE, DMParser.SAVEPOINT, DMParser.TRUNCATE,
            DMParser.LOCK, DMParser.NOAUDIT, DMParser.PURGE,
            DMParser.EXPLAIN, DMParser.FLASHBACK, DMParser.ASSOCIATE,
            DMParser.AUDIT, DMParser.MERGE, DMParser.ROLLBACK,
            DMParser.DISASSOCIATE,  DMParser.SEMICOLON,
            DMParser.COMMENT,DMParser.WITH,DMParser.EXIT,
            DMParser.PROMPT_MESSAGE,DMParser.SHOW,DMParser.WHENEVER,
            DMParser.TIMING,DMParser.START_CMD,

    };

    public static final IntervalSet recoverSet = new IntervalSet(stmtBeginToken);


}
