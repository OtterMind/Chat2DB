# MYSQL-SEC-006: Function and procedure object-level privileges

## Fixture

- `init.sql` creates `sec006_double` (function), `sec006_bump` (procedure), a
  manager with EXECUTE/ALTER ROUTINE + GRANT OPTION and read-only grant metadata
  access, an unprivileged user, and a routine role for inherited-role evidence.
- `grants.sql` applies direct routine grants, an inherited database EXECUTE
  grant, a role assignment, and then runs `SHOW GRANTS FOR 'sec006_user'@'%'`.
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
9. Run `grants.sql` and refresh the privilege panel. Verify direct Function and
   Procedure grants are labeled separately from inherited Database and Role
   sources, and that routine REVOKE remains available only for matching direct
   routine grants.
