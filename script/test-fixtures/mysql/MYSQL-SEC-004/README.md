# MYSQL-SEC-004: Role lifecycle, grants, defaults, and active-role state

## Fixture

- `init.sql` creates:
  - `sec004_admin` — administrator with CREATE USER, SYSTEM_USER, ROLE_ADMIN
  - `sec004_user1`, `sec004_user2` — test users
  - 3 roles: `sec004_role_reader` (SELECT), `sec004_role_writer` (CRUD), `sec004_role_admin` (ALL)
  - Role grants to users (with WITH ADMIN OPTION on writer)
  - Nested role grant (reader → writer)
  - Default role set for user1
- `grants.sql` grants role management privileges
- `cleanup.sql` drops all roles and users

## Verification

1. Connect as `sec004_admin` (MySQL 8.0+).
2. Create a new role — verify SQL preview shows `CREATE ROLE ...`.
3. Grant a role to a user — verify SQL shows `GRANT role TO user`.
4. Grant with WITH ADMIN OPTION — verify SQL includes `WITH ADMIN OPTION`.
5. Set default role to ALL — verify SQL shows `SET DEFAULT ROLE ALL TO user`.
6. Set default role to specific roles — verify SQL lists role names.
7. Set default role to NONE — verify SQL shows `SET DEFAULT ROLE NONE TO user`.
8. Revoke a role from a user — verify SQL shows `REVOKE role FROM user`.
9. Drop a role — verify SQL shows `DROP ROLE IF EXISTS ...`.
10. Verify nested role grants are displayed correctly.
11. Verify cyclic role grants are rejected or surface server rejection.
12. Verify CURRENT_ROLE() shows active roles.
13. On MySQL 5.7, verify role management entry points are not shown.
14. Verify role deletion requires entering the role name for confirmation.
