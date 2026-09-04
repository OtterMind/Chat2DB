# MYSQL-IMPORT-004: SQL file execution options

This fixture verifies MySQL SQL-file charset handling, delimiter-aware routine and trigger parsing,
transaction modes, permission failures, cancellation, and non-transactional table preflight.

## Setup

Run as a MySQL administrator:

```bash
mysql -uroot -p < init.sql
mysql -uroot -p < grants.sql
```

Create a GB18030 input without committing a binary duplicate:

```bash
iconv -f UTF-8 -t GB18030 utf8-data.sql > /tmp/MYSQL-IMPORT-004-gb18030.sql
```

Generate a large cancellable script when needed:

```bash
bash generate-large-file.sh 100000 > /tmp/MYSQL-IMPORT-004-large.sql
```

## Verification

1. Import `delimiter.sql` with UTF-8 and STOP. Verify the procedure and trigger are created and the
   delimiter directives are not executed as SQL.
2. Import `/tmp/MYSQL-IMPORT-004-gb18030.sql` with GB18030. Verify the Chinese row round-trips.
3. Run `transactional.sql` with batch commit and single transaction. Verify committed and rollback
   summaries match the rows visible in `import004_innodb`.
4. Attempt `non-transactional.sql` in a transactional mode. Verify preflight rejects the MyISAM target
   before any statement executes. The legacy non-transactional mode may execute it.
5. Connect as `import004_limited` and import `permission-denied.sql`. Verify the task reports a bounded,
   actionable permission failure without exposing credentials.
6. Start `/tmp/MYSQL-IMPORT-004-large.sql`, cancel while running, and verify final status is CANCELLED,
   auto-commit is restored, and the summary identifies unexecuted statements.

## Cleanup

```bash
mysql -uroot -p < cleanup.sql
```
