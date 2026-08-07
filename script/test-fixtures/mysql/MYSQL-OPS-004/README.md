# MYSQL-OPS-004: Variables and status management

## Fixture

- `init.sql` creates `ops004_admin` (variable privileges) and `ops004_user` (no variable
  privileges), plus `ops004_test`.
- `grants.sql` grants SYSTEM_VARIABLES_ADMIN (8.0) / SUPER (5.7) to the admin only.
- `cleanup.sql` drops test objects and users.

## Verification

1. Connect as `ops004_admin`.
2. Open Variables & Status (right-click the datasource node).
3. Global Variables — filter `wait_timeout`; verify the server value is shown.
4. Edit `wait_timeout` with scope SET SESSION — verify the preview
   `SET SESSION wait_timeout = <value>` and that it applies to the current console only
   (query `SELECT @@session.wait_timeout` to read back).
5. Edit `sql_mode` (STRING) — verify the value is quoted in the preview.
6. Edit `foreign_key_checks` with `ON`/`OFF` — verify validation rejects invalid input.
7. Edit `max_connections` (HIGH risk) — verify typing the variable name is required
   before the preview is enabled.
8. Connect as `ops004_user` and attempt a global edit — verify the original server
   permission error is shown.
9. Global Status / Session Status tabs — verify they are read-only (no Edit action).
10. An unknown variable (e.g. `no_such_variable_xyz`) — verify it shows Read-only and
    no SET preview can be generated.
11. On MySQL 8.0, edit `max_connections` with scope SET PERSIST — verify the preview is
    `SET PERSIST max_connections = ...` and the value can be read back via
    `performance_schema.persisted_variables`; restore the original value afterwards.
