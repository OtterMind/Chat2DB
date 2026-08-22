# MYSQL-OPS-002: Active transaction inspection

## Fixture

- `init.sql` creates:
  - `ops002_admin` — administrator with PROCESS (full transaction visibility)
  - `ops002_user` — limited account (no PROCESS; sees NULL SQL text for others)
  - `ops002_test` database with `ops002_accounts` and sample data
- `grants.sql` grants PROCESS to the admin account only
- `cleanup.sql` drops test objects and users

## Verification

1. Connect as `ops002_admin`.
2. Open the Active Transactions view (right-click the datasource node).
3. Open a second connection and run `START TRANSACTION; UPDATE ops002_accounts SET balance = balance - 100 WHERE id = 1;` — leave it open.
4. Refresh the view — verify the transaction appears with state RUNNING, a growing age, isolation level REPEATABLE READ, thread ID, user, host, database, and the UPDATE SQL text.
5. Open a third connection and run a second open transaction — verify both are listed, ordered by start time.
6. On the same connection run a lock wait (`UPDATE ops002_accounts ... WHERE id = 1` while the first transaction holds the row lock) — verify the waiting transaction's state is LOCK WAIT.
7. Commit the first transaction — refresh — verify the row disappears.
8. Connect as `ops002_user`, open a transaction, and refresh the view — verify the current user's transaction appears, and other users' transactions show NULL/empty SQL text (explicit unavailable state).
9. With no open transactions, refresh — verify the empty state is shown normally.
