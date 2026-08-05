# MYSQL-SEC-001: Rename MySQL accounts and change Host

## Fixture

- `init.sql` creates:
  - `sec001_admin` — administrator with CREATE USER and SYSTEM_USER
  - `sec001_source`@`localhost` — source account to be renamed
  - `sec001_target`@`localhost` — target account (for conflict testing)
  - View, Function, Procedure, and Trigger with Definer
- `grants.sql` grants SELECT on mysql.user for account enumeration
- `cleanup.sql` drops all test objects and accounts

## Verification

1. Connect as `sec001_admin`.
2. Rename `sec001_source`@`localhost` to `sec001_renamed`@`%` — preview shows `RENAME USER 'sec001_source'@'localhost' TO 'sec001_renamed'@'%'`.
3. Confirm — verify old account disappears, new account retains grants and auth plugin.
4. Rename only the username (keep host) — verify SQL preview is correct.
5. Rename only the host (keep username) — verify SQL preview is correct.
6. Test conflict: try renaming to `sec001_target`@`localhost` — verify conflict is detected.
7. Verify Definer objects (View, Function, Procedure, Trigger) are listed before execution.
8. Verify confirmation explains that rename doesn't rewrite Definers.
9. Verify no password hashes appear in previews or logs.
10. On MySQL 8.0, verify SYSTEM_USER restrictions are enforced.
