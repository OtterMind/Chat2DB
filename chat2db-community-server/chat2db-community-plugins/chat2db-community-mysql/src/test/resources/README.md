# MySQL Manual Transaction Fixture

This fixture verifies console-scoped manual transactions for issue #2586.

## Files

- `init.sql` creates the InnoDB and MyISAM test tables.
- `grants.sql` creates separate administrator and DML-only accounts.
- `cleanup.sql` removes the test database and accounts.

The SQL files support manual verification through the running Chat2DB Web application. Automated
JDBC coverage runs against the loopback-only MySQL 5.7 and 8.0 fixtures managed by the repository
test scripts:

```bash
./script/test/start-test-databases.sh mysql57 mysql80
mvn -B -f chat2db-community-server/pom.xml \
  -pl :chat2db-community-mysql -am \
  -Dmaven.test.skip=false -DskipTests=false \
  -Dtest=MysqlManualTransactionIntegrationTest \
  '-Dsurefire.includes=**/*Test.java' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.test.failure.ignore=false \
  -Dchat2db.mysql.fixture.required=true test
```

The parameterized test verifies both server versions and skips cleanly when a fixture is not
running during ordinary local test runs. CI runs the same test with
`-Dchat2db.mysql.fixture.required=true`, so a missing MySQL fixture fails the job instead of
being reported as skipped. Manual Web verification remains useful for the Controller, renderer,
and close-dialog lifecycle.

## Manual Concurrent Check

For an existing MySQL server, run `init.sql` and `grants.sql` as an administrator. Open two
sessions against `c2d_tx_test`, using `c2d_tx_dml` for the writer and `c2d_tx_admin` for the
observer:

```sql
-- writer
SET autocommit = 0;
INSERT INTO tx_innodb(val) VALUES ('pending');

-- observer: expected 0 before commit, 1 after commit
SELECT COUNT(*) FROM tx_innodb WHERE val = 'pending';

-- writer
COMMIT;
```

Repeat with `ROLLBACK`; the observer must continue to report zero rows. For `tx_myisam`, the
observer sees the row immediately and rollback does not remove it. MySQL DDL implicitly commits:
run a pending InnoDB insert followed by `CREATE TABLE`. Chat2DB must reject the DDL before it is
sent and tell the user to commit, roll back, or leave manual mode; the pending row must remain
invisible and the table must not be created.

If commit or rollback loses the network connection, the outcome is unknown and the bound
connection must be discarded rather than returned to the pool. Closing a console must offer
Commit, Rollback, and Cancel; Cancel keeps the console open. Application shutdown and datasource
changes roll back and release every bound console connection.

Run `cleanup.sql` as an administrator after manual testing.
