# MYSQL-SEC-005: Column-level privilege management

## Fixture

- `init.sql` creates `sec005_employees` with sensitive `salary`/`notes` columns, a
  manager account with GRANT OPTION, and an unprivileged user.
- `grants.sql` is a no-op placeholder; column grants are applied through the UI.
- `cleanup.sql` drops test objects and users.

## Verification

1. Connect as `sec005_admin`, open the account page for `sec005_user`.
2. Choose Grant privilege with scope Column, select `sec005_test`/`sec005_employees`,
   select the `name` column and privilege SELECT.
3. Verify the preview `GRANT SELECT (\`name\`) ON \`sec005_test\`.\`sec005_employees\` TO 'sec005_user'@'%'`
   and execute.
4. Verify `SHOW GRANTS FOR 'sec005_user'@'%'` includes the column grant.
5. Connect as `sec005_user` — verify `SELECT name FROM sec005_employees` works and
   `SELECT salary FROM sec005_employees` is denied (no broader grant).
6. Back as admin: revoke the column grant — verify the REVOKE preview and that
   `SHOW GRANTS` no longer lists it.
7. Verify that submitting an empty column selection is rejected.
8. Verify that selecting DELETE/CREATE with column scope is rejected with the
   "only SELECT/INSERT/UPDATE/REFERENCES" error.
9. Grant `SELECT (name, id)` on two columns — verify both are quoted in the preview and
   reflected in SHOW GRANTS.
