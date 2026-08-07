# MYSQL-SEC-006: Function and procedure object-level privileges

## Fixture

- `init.sql` creates `sec006_double` (function) and `sec006_bump` (procedure) plus a
  manager with EXECUTE/ALTER ROUTINE + GRANT OPTION and an unprivileged user.
- `grants.sql` is a no-op placeholder; routine grants are applied through the UI.
- `cleanup.sql` drops test objects and users.

## Verification

1. Connect as `sec006_admin`, open the account page for `sec006_user`.
2. Choose Grant privilege with scope Function, select `sec006_test` and
   `sec006_double`, privilege EXECUTE.
3. Verify the preview `GRANT EXECUTE ON FUNCTION \`sec006_test\`.\`sec006_double\` TO 'sec006_user'@'%'`
   and execute.
4. Verify `SHOW GRANTS FOR 'sec006_user'@'%'` includes the routine grant.
5. Connect as `sec006_user` — verify `SELECT sec006_double(21)` returns 42 and
   `CALL sec006_bump(@n)` is denied.
6. Back as admin: grant EXECUTE on the procedure `sec006_bump` — verify the preview and
   that `CALL sec006_bump(@n)` now works for the user.
7. Try granting SELECT at function scope — verify it is rejected with the
   "only EXECUTE and ALTER ROUTINE" error.
8. Revoke the procedure grant — verify the REVOKE preview and SHOW GRANTS consistency.
