# Cancellable batch inserts and SQL script transactions

Import execution uses the existing task and SQL entrypoints. The JDBC insert path processes at most 500 statements per chunk, checks cancellation between chunks, and registers the active statement for cancellation. No new API, UI, account, storage provider or import manifest is required.

Cancellation is checked again after execution returns and before a self-managed chunk commits. A cancellation already observed at that boundary rolls back the current chunk even when the driver returned a successful execution result.

When a connection is in JDBC auto-commit mode and the script has not opened a transaction, each successful chunk is committed. A failed current chunk is rolled back; earlier committed chunks remain. The remaining chunks are not executed. If rollback or restoring the connection fails, the connection is discarded rather than returned in an uncertain state.

When the caller has disabled JDBC auto-commit, the caller retains its commit/rollback decision. An SQL script that executes `BEGIN` or `START TRANSACTION` also owns its transaction, even if the driver still reports JDBC auto-commit. Insert batches do not commit behind that script. `ROLLBACK TO [SAVEPOINT]` keeps the enclosing transaction open, and `COMMIT/ROLLBACK AND CHAIN` keeps the next transaction under script control. PostgreSQL `END` and `ABORT` aliases are covered by the same boundary. See the [PostgreSQL 16 transaction documentation](https://www.postgresql.org/docs/16/sql-rollback.html) and [savepoint rollback documentation](https://www.postgresql.org/docs/16/sql-rollback-to.html).

Ordinary CSV, Excel and JSON imports retain the successful-prefix contract for the default `ABORT` policy: one prepared statement is reused row by row, and rows after the first rejection are never submitted. Parameter binding, duplicate handling, rejected-row artifacts and resumable checkpoints remain available. `FAST` imports keep the upstream JDBC batch path. The tolerant `SKIP_ERROR_ROWS` policy isolates each attempted batch in its own transaction or a caller-owned savepoint before replaying rejected rows. SQL script transaction safety is implemented separately in `SqlScriptExecutor`.

Row replay requires a confirmed rollback. A failed rollback never restores auto-commit, and a lost commit response or failed connection restore discards the connection and prevents replay. Connection loss during auto-commit execution is also treated as an uncertain outcome because the server may already have applied the row. These failures retain `IMPORT_TRANSACTION_UNSAFE`; continuing such a task from its old row checkpoint is rejected. The target data must be reconciled before a new import. A caller-owned transaction is never committed by the importer; successful savepoint retries remain subject to the caller’s final commit or rollback.

SQL script events retain the upstream per-chunk statement order, affected-row counts and final summary. For self-managed chunks, a successful batch event is emitted only after the pre-commit cancellation check and commit. A failed chunk retains the driver-reported execution counts for diagnostics even though that chunk is rolled back; those counts are not a committed-row receipt.

SQL Server's JDBC auto-commit flag does not describe explicit/nested T-SQL transaction state. Its metadata plugin probes `SELECT @@TRANCOUNT` before choosing the insert path. Named savepoint rollback and an inner `COMMIT` therefore keep the outer transaction protected. The probe is read-only, bounded to five seconds and exposes its statement to task cancellation; failures stop the import instead of guessing that no transaction exists. Other metadata plugins use the existing script/JDBC guard through a compatible default SPI method. See [Microsoft's transaction-count contract](https://learn.microsoft.com/en-us/sql/t-sql/functions/trancount-transact-sql).

`SQLScriptSqlServerTransactionContractTest` exercises the production SQL Server `GO` parser/plugin against a driver-shaped H2 transaction fixture; `SqlServerTransactionStateTest` covers the probe's depth, failure and resource boundary. These are contract tests, not a real SQL Server engine run.

## Verification

Use Java17 and the checked-in Maven reactor. The focused suite exercises actual JDBC calls, cancellation and transaction classification:

```powershell
mvn -B -f chat2db-community-server/pom.xml -pl ':chat2db-community-domain-core,:chat2db-community-spi' -am `
  '-Dmaven.test.skip=false' '-DskipTests=false' `
  '-Dtest=SqlTransactionScopeTest,ImportSqlExecutorScriptTransactionTest,DefaultSQLExecutorTaskCancellationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmaven.test.failure.ignore=false' clean test
```

The real PostgreSQL test drives UTF-8 SQL files through the production importer/parser into a newly created PostgreSQL16 fixture. It verifies commit, rollback, savepoints, chained transactions, an existing caller-owned transaction, aliases, and cancellation after a `pg_sleep` statement is confirmed running. The cancellation case verifies that no rows were committed.

```powershell
python -X utf8 script/test/test-sql-batch-postgres-runner.py
python -X utf8 script/test/run-sql-batch-postgres-it.py
```

The runner requires Docker, creates a named fixture on loopback, keeps its generated password in inherited process environments, verifies a fresh report with exactly eight executed tests and no failures/errors/skips, and removes only the container whose ownership label it verified. Missing/empty/failed/skipped reports fail the gate. Cleanup failures preserve a preceding Maven failure. It never reuses a user database.

Run the full owning-module gate to catch effects on ordinary imports and other callers:

```powershell
mvn -B -f chat2db-community-server/pom.xml -pl ':chat2db-community-domain-core,:chat2db-community-web' -am `
  '-Dmaven.test.skip=false' '-DskipTests=false' '-Dsurefire.includes=**/*Test.java' `
  '-Dmaven.test.failure.ignore=false' clean package
```

This feature's real-database evidence targets PostgreSQL16 and H2 JDBC contracts. Other engines, native installers and provider CI require their own execution evidence; they are not inferred from a successful package.

Failed or cancelled ordinary SQL scripts discard their borrowed connection when JDBC auto-commit
was enabled at entry. A script may open a server transaction while that JDBC flag stays true;
returning the lease would expose an uncommitted transaction to later requests. Detachment occurs
before close, close failures are suppressed on the original error, and a caller that already had
auto-commit disabled keeps its transaction decision. Committed earlier chunks remain committed.
Four real-H2 script regressions cover cancellation, execution failure, caller ownership and the
committed prefix. The owning package runs958 tests/0 failures/0 errors/1 existing platform skip;
the required feature suites and fresh PostgreSQL16 eight-case run have no skips.

### Unfinished task scripts and procedural blocks (2026-10-14)

A task that borrowed an auto-commit connection must finish its script transaction and restore auto-commit before reporting success. An unfinished BEGIN or SET autocommit=0 script fails with IMPORT_SQL_TRANSACTION_OPEN and discards its borrowed connection; an initially caller-owned transaction retains its caller's decision. MySQL checks the actual session autocommit flag before insert batches, preserving SQL-controlled rollback even when JDBC caches a different flag. COMMIT alone does not restore a disabled MySQL session mode. The read-only probe has a five-second timeout and participates in task cancellation.

BEGIN/END procedural blocks are distinct from transaction control: Oracle anonymous PL/SQL blocks and SQL Server TRY blocks do not alter the generic transaction tracker. The production Oracle parser has a focused contract regression; it is not a real Oracle engine run. Six real-H2 script cases, ten transaction/parser cases and three MySQL probe contracts pass without skips. Fresh isolated MySQL BEGIN and SET-autocommit EOF cases fail, leave zero target rows and no active fixture transaction; the Chinese task error is verified. Earlier committed chunks remain committed.
