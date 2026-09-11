# MYSQL-OPS-002: Active transaction inspection

## Fixture

- `init.sql` creates:
  - `ops002_admin` — administrator with PROCESS plus read access to MySQL 8.0
    lock-wait metadata (full transaction and blocking-chain visibility)
  - `ops002_user` — limited account (no PROCESS; can inspect its own visible
    InnoDB transactions, but cross-user transaction/process details can be hidden
    or rejected by MySQL privilege checks)
  - `ops002_test` database with `ops002_accounts` and sample data
- `grants.sql` grants PROCESS to the admin account only
- `cleanup.sql` drops test objects and users

## Verification

1. Connect as `ops002_admin`.
2. Expand the datasource's Monitor node, then double-click Active Transactions.
3. Open a second connection and run `START TRANSACTION; UPDATE ops002_accounts SET balance = balance - 100 WHERE id = 1;` — leave it open.
4. Refresh the view — verify the transaction appears with state RUNNING, a growing age, isolation level REPEATABLE READ, thread ID, user, host, database, and the UPDATE SQL text.
5. Open a third connection and run a second open transaction — verify both are listed, ordered by start time.
6. On the third connection run `START TRANSACTION; UPDATE ops002_accounts SET balance = balance + 10 WHERE id = 1;` while the first transaction holds the row lock — verify the waiting transaction's state is LOCK WAIT.
7. On MySQL 8.0, capture fixture evidence with:
   ```sql
   SELECT REQUESTING_ENGINE_TRANSACTION_ID, REQUESTING_ENGINE_LOCK_ID,
          BLOCKING_ENGINE_TRANSACTION_ID, BLOCKING_ENGINE_LOCK_ID
   FROM performance_schema.data_lock_waits;
   ```
8. On MySQL 5.7, capture fixture evidence with:
   ```sql
   SELECT requesting_trx_id, requested_lock_id, blocking_trx_id, blocking_lock_id
   FROM information_schema.innodb_lock_waits;
   ```
9. Verify the Active Transactions view shows waited-lock and blocker fields for the waiting row, and that the owner and blocker connection ID links open datasource-bound consoles filtered by the exact `information_schema.PROCESSLIST.ID`.
10. Commit or roll back the first transaction — refresh — verify committed or rolled-back transactions disappear instead of remaining as historical rows.
11. Connect as `ops002_user`, open a transaction, and refresh the view — verify hidden SQL is rendered as an explicit unavailable state when MySQL returns NULL, lock-wait metadata degrades explicitly when the account or server cannot expose it, and a PROCESS denial from the transaction/process query is reported as a permission-required state rather than a generic runtime error.
12. With no open transactions, refresh — verify the empty state is shown normally.
