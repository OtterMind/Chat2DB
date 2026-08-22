# MYSQL-SEC-002: Auth plugins and TLS requirements

## Fixture

- `init.sql` creates 6 test accounts:
  - `sec002_admin` — administrator with CREATE USER and SYSTEM_USER
  - `sec002_default` — default auth plugin (caching_sha2_password on 8.0)
  - `sec002_native` — mysql_native_password plugin
  - `sec002_ssl` — REQUIRE SSL
  - `sec002_x509` — REQUIRE X509
  - `sec002_none` — REQUIRE NONE
- `grants.sql` grants CREATE USER and SYSTEM_USER
- `cleanup.sql` drops all test accounts

## Verification

1. Connect as `sec002_admin`.
2. Read auth plugin for each account — verify values match init.sql.
3. Read TLS requirement for `sec002_ssl` — verify SSL is set.
4. Change `sec002_default` to mysql_native_password — preview shows `ALTER USER ... IDENTIFIED WITH mysql_native_password BY ***`.
5. Change `sec002_ssl` to REQUIRE NONE — verify SQL preview shows `REQUIRE NONE`.
6. Change `sec002_none` to REQUIRE SSL — verify SQL preview.
7. Set certificate constraints with CIPHER, ISSUER, SUBJECT — verify SQL preview includes all options.
8. Clear TLS requirement back to NONE — verify.
9. Verify an unavailable plugin is rejected before SQL is previewed or executed.
10. Verify no plaintext passwords in previews or logs.
11. Verify cancelling leaves the account unchanged.
