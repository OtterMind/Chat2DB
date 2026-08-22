# MYSQL-SEC-003: Password expiration and resource limits

## Fixture

- `init.sql` creates 6 test accounts:
  - `sec003_admin` — administrator with CREATE USER and SYSTEM_USER
  - `sec003_default` — default password policy
  - `sec003_never` — PASSWORD EXPIRE NEVER
  - `sec003_interval` — PASSWORD EXPIRE INTERVAL 90 DAY
  - `sec003_limited` — resource limits (100/50/10/5 queries/updates/connections/user_connections)
  - `sec003_unlimited` — all resource limits set to 0 (removed)
- `grants.sql` documents privileges needed for testing
- `cleanup.sql` drops all test accounts

## Verification

1. Connect as `sec003_admin`.
2. Read password expiration policy for each account — verify values match init.sql.
3. Change `sec003_default` to PASSWORD EXPIRE NEVER — preview shows `ALTER USER ... PASSWORD EXPIRE NEVER`.
4. Change `sec003_never` to PASSWORD EXPIRE DEFAULT — verify SQL preview.
5. Change `sec003_interval` to PASSWORD EXPIRE INTERVAL 30 DAY — verify SQL.
6. Set `sec003_default` to PASSWORD EXPIRE (immediate) — verify account is expired on next login.
7. Read resource limits for `sec003_limited` — verify values match.
8. Change MAX_QUERIES_PER_HOUR from 100 to 200 — verify only that field changes.
9. Set all resource limits to 0 — verify limits are removed.
10. Verify changing one field does not overwrite others.
11. Verify negative/out-of-range values are rejected.
12. Verify no plaintext passwords appear in SQL preview.
