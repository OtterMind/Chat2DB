# MYSQL-IMPORT-004: SQL-file encoding, error, and transaction options

## Fixture

- `init.sql` creates InnoDB `import004_items`, a MyISAM `import004_myisam`, and
  `import004_admin`.
- Scripts: UTF-8 with a mid-file type error (`fixture_utf8.sql`), GB18030
  (`fixture_gb18030.sql`), single-transaction (`fixture_single.sql`), transaction-control
  (`fixture_tx_control.sql`), DDL (`fixture_ddl.sql`), DELIMITER routine
  (`fixture_delimiter.sql`), and a 1M-row generator (`generate_large.py`).

## Verification

1. Connect as `import004_admin`; run `fixture_utf8.sql` in SCRIPT/STOP mode — verify the
   first row imports, the import stops at the broken statement, and the error shows the
   statement number, MySQL error code, and SQLSTATE.
2. Re-run in SCRIPT/CONTINUE — verify all three rows import (broken row skipped).
3. Run `fixture_gb18030.sql` with encoding GB18030 — verify 中文名 imports without
   corruption.
4. Run `fixture_single.sql` in SINGLE_TRANSACTION — verify both rows commit.
5. Run `fixture_tx_control.sql` in BATCH — verify it is rejected before execution with
   the transaction-control message.
6. Run `fixture_ddl.sql` in SINGLE_TRANSACTION — verify it is rejected with the DDL
   message and no table is created.
7. BATCH mode with batch size 2: script with 5 inserts, STOP — verify the first 2 commit,
   the third fails, the batch is rolled back, and statements 4-5 are unexecuted.
8. BATCH mode CONTINUE: verify only MySQL-confirmed statements commit at each boundary.
9. Run `fixture_delimiter.sql` in SCRIPT mode — verify the procedure is created and the
   routine row imports.
10. Generate the large file and import it — verify streaming progress and cancellation
    leave a clear outcome.
