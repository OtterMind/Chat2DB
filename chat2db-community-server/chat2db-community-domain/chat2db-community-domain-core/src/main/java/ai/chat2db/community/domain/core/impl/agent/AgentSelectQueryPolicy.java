package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.spi.util.JdbcUtils;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;

final class AgentSelectQueryPolicy {
    private AgentSelectQueryPolicy() { }

    static boolean accepts(String sql, String databaseType) {
        try {
            var statements = SQLUtils.parseStatements(sql, JdbcUtils.parse2DruidDbType(databaseType));
            if (statements.isEmpty()) return false;
            boolean[] allowed = { true };
            for (SQLStatement statement : statements) {
                if (!(statement instanceof SQLSelectStatement)) return false;
                statement.accept(new SQLASTVisitorAdapter() {
                @Override
                public void preVisit(SQLObject node) {
                    if (node instanceof SQLStatement && !(node instanceof SQLSelectStatement)) allowed[0] = false;
                    if (node instanceof SQLSelectQueryBlock query
                            && (query.getInto() != null || query.isForUpdate() || query.isForShare())) allowed[0] = false;
                    if (node instanceof PGSelectQueryBlock query && query.getForClause() != null) allowed[0] = false;
                    if (node instanceof MySqlSelectQueryBlock query && (query.isLockInShareMode() || query.getProcedureName() != null)) allowed[0] = false;
                }
                });
            }
            return allowed[0];
        } catch (RuntimeException error) {
            return false;
        }
    }
}
