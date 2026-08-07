package ai.chat2db.spi.util;

import ai.chat2db.spi.util.JdbcUtils;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAggregateExpr;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SqlGenerateUtil {
    private static final Logger log = LoggerFactory.getLogger(SqlGenerateUtil.class);

    public static String generateSelectCountSql(String originalSql, String dataBaseType) {
        try {
            Statement statement = CCJSqlParserUtil.parse(originalSql);
            if (!(statement instanceof Select select)) {
                throw new IllegalArgumentException("Not a SELECT statement");
            }

            Select selectBody = select.getSelectBody();

            if (selectBody instanceof SetOperationList) {
                return handleUnion(originalSql);
            } else {
                if (containsDistinct(selectBody)) {
                    return handleDistinct(originalSql);
                }

                // If the query has a GROUP BY, in-place rewrite to COUNT(*) would
                // return one row per group. Wrap in a subquery instead.
                if (selectBody instanceof PlainSelect plainSelect
                        && plainSelect.getGroupBy() != null) {
                    return buildSubQuery(originalSql);
                }

                SelectCountVisitor visitor = new SelectCountVisitor();
                selectBody.accept(visitor);
                return select.toString();
            }
        } catch (Exception e) {
            log.error("jsqlparser parser sql error", e);
            return generateSelectCountSqlWithDruid(originalSql, dataBaseType);
        }
    }

    static String generateSelectCountSqlWithDruid(String originalSql, String dataBaseType) {
        DbType dbType = JdbcUtils.parse2DruidDbType(dataBaseType);

        if (dbType == null) {
            throw new IllegalArgumentException("Unsupported database type: " + dataBaseType);
        }

        SQLStatement stmt = SQLUtils.parseSingleStatement(originalSql, dbType);

        if (!(stmt instanceof SQLSelectStatement)) {
            throw new IllegalArgumentException("Not a SELECT statement");
        }
        SQLSelectQueryBlock query = ((SQLSelectStatement) stmt).getSelect().getQueryBlock();
        if (query.getDistionOption() != 0) {
            return handleDistinct(originalSql);
        }
        // If the query has a GROUP BY, in-place rewrite to COUNT(*) would
        // return one row per group. Wrap in a subquery instead.
        if (query.getGroupBy() != null) {
            return buildSubQuery(originalSql);
        }
        query.getSelectList().clear();
        SQLAggregateExpr countExpr = new SQLAggregateExpr("COUNT");
        countExpr.addArgument(new SQLAllColumnExpr());
        query.addSelectItem(countExpr);
        query.setOrderBy(null);
        query.setLimit(null);
        query.setOffset(null);
        return SQLUtils.toSQLString(stmt, dbType);
    }


    private static boolean containsDistinct(Select selectBody) {
        if (selectBody instanceof PlainSelect plainSelect) {
            return plainSelect.getDistinct() != null;
        }
        return false;
    }


    private static String handleDistinct(String originalSql) {
        return buildSubQuery(originalSql);
    }

    private static String handleUnion(String originalSql) {
        return buildSubQuery(originalSql);
    }

    private static String buildSubQuery(String originalSql) {
        return "SELECT COUNT(*) FROM (" + originalSql + ") chat2db_count_temp_table";
    }

    private static class SelectCountVisitor extends SelectVisitorAdapter {
        @Override
        public void visit(PlainSelect plainSelect) {
            plainSelect.getSelectItems().clear();
            Column column = new Column("COUNT(*)");
            SelectItem selectItem = new SelectItem();
            selectItem.setExpression(column);

            plainSelect.addSelectItems(selectItem);
            plainSelect.setOrderByElements(null);
            plainSelect.setLimit(null);
            plainSelect.setOffset(null);
            plainSelect.setFetch(null);

        }
    }
}
