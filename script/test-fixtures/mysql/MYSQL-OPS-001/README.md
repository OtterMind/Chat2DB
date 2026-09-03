# MYSQL-OPS-001: Session inspection and termination

## Fixture

- `init.sql` creates a MySQL 8.0 fixture:
  - `ops001_admin` — administrator with PROCESS and CONNECTION_ADMIN (full session visibility and other-user kill authorization)
  - `ops001_user` — limited account (can only see own sessions)
  - `ops001_test` database with a `ops001_slow` table and sample data
- `grants.sql` grants PROCESS and CONNECTION_ADMIN for MySQL 8.0; for MySQL 5.7 use the documented SUPER fallback instead of CONNECTION_ADMIN.
- `cleanup.sql` drops test objects and users

## Verification

1. Connect as `ops001_admin`.
2. Open the session view — verify sessions are displayed with ID, User, Host, DB, Command, Time, State, Info columns.
3. Open a second connection running `SELECT SLEEP(60)`.
4. Verify the sleeping/long-running session appears in the list.
5. Use KILL QUERY on the SLEEP session — verify the query is stopped but the connection remains.
6. Use KILL CONNECTION on the same session — verify the connection is terminated.
7. Verify the current Chat2DB session is protected from being killed.
8. Verify filtering by user, database, state works.
9. Test killing an already-finished session — verify the backend reports the database result or error.
10. Connect as `ops001_user` — verify only own sessions are visible.
11. Verify every kill action requires confirmation and shows the submitted result.
12. Verify `ops001_user` cannot terminate another user's session while `ops001_admin` can terminate authorized other-user sessions.
