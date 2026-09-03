# MYSQL-SQL-002: Structured EXPLAIN JSON and EXPLAIN ANALYZE

## Fixture

- `init.sql` creates two tables with indexes and sample data:
  - `obj002_users` with PK, email index, dept index
  - `obj002_orders` with PK, user_id index, status index
- `grants.sql` creates `chat2db_explain_reader` with SELECT and `chat2db_explain_limited` without table grants
- `cleanup.sql` drops both tables and fixture users

## Verification

1. As an administrative local test user, run `init.sql` in the selected test database, then run `grants.sql`.
2. Connect Chat2DB as `chat2db_explain_reader` and run:
   - `SELECT * FROM obj002_users WHERE id = 1`
   - `EXPLAIN SELECT * FROM obj002_users WHERE id = 1`
   - `EXPLAIN FORMAT=JSON SELECT * FROM obj002_users WHERE id = 1`
3. In the SQL editor, switch the same SELECT between the existing tabular EXPLAIN, the JSON plan tree, and raw JSON.
4. Verify every displayed JSON tree node shows a source path, such as `$.query_block.nested_loop[0].table`, that can be found in the raw JSON structure.
5. Run `EXPLAIN FORMAT=JSON SELECT * FROM obj002_users WHERE name LIKE 'A%'` and verify full-scan access is represented without requiring exact cost values.
6. Run `EXPLAIN FORMAT=JSON SELECT * FROM obj002_orders WHERE user_id = 1` and verify index access is represented.
7. Run `EXPLAIN FORMAT=JSON SELECT u.name, o.amount FROM obj002_users u JOIN obj002_orders o ON o.user_id = u.id WHERE o.status = 'completed'` and verify join/nested-loop nodes are represented.
8. On MySQL 8.0.18+, run `EXPLAIN ANALYZE SELECT u.name, o.amount FROM obj002_users u JOIN obj002_orders o ON o.user_id = u.id WHERE o.status = 'completed'` and verify estimated rows, actual rows, loops, first-row time, and total time are displayed.
9. On MySQL 8.0+, run `WITH recent AS (SELECT * FROM obj002_orders WHERE status = 'completed') SELECT * FROM recent` through JSON EXPLAIN and verify CTE SELECT validation accepts it.
10. On MySQL 5.7, verify ANALYZE is disabled or rejected with the MySQL 8.0.18+ message while FORMAT=JSON still works.
11. Connect as `chat2db_explain_limited` and verify FORMAT=JSON returns the normal database permission error rather than bypassing table privileges.
12. Run `UPDATE obj002_orders SET amount = 0` through JSON EXPLAIN and verify the client/server reject it before executing JDBC SQL.
13. Run malformed SQL, such as `SELECT FROM`, and verify a clear validation or database syntax error is shown.
14. Start `EXPLAIN ANALYZE SELECT SLEEP(10) FROM obj002_users` on MySQL 8.0.18+, cancel it from the modal, and verify the request is cancelled.

## Automated checks

- Frontend parser: `cd chat2db-community-client && yarn tsx src/components/SQLEditor/helper/explainPlan.test.ts`
- Frontend i18n: `cd chat2db-community-client && yarn run test:i18n`
- Backend focused test:
  `mvn -B -f chat2db-community-server/pom.xml -pl :chat2db-community-domain-core -am -Dmaven.test.skip=false -DskipTests=false -Dtest=DbExplainServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.test.failure.ignore=false test`

Optimizer cost values are version- and statistics-dependent. Treat node types, access methods, source paths, row fields, and timing field presence as the stable assertions.

## Cleanup

Run `cleanup.sql` in the same local test database.
