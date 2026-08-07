# MYSQL-OBJ-008: CHECK constraint management

## Fixture

- `init.sql` creates `obj008_check_test` with:
  - Named constraint `chk_age_positive` — CHECK (age >= 0) ENFORCED
  - Named constraint `chk_status_valid` — CHECK (status IN (...)) ENFORCED
  - Server-named constraint — CHECK (email IS NOT NULL OR name = 'anonymous') NOT ENFORCED
- Sample data includes a row that violates chk_age_positive (age = -5)
- `grants.sql` grants ALTER for constraint management
- `cleanup.sql` drops the test table

## Verification

1. Connect with the test user (MySQL 8.0.16+).
2. Open the table in the table editor.
3. Verify CHECK constraints section shows named and server-named constraints with expression and enforcement state.
4. Create a new CHECK constraint — verify SQL preview includes `ADD CONSTRAINT name CHECK (expr) ENFORCED`.
5. Toggle enforcement from ENFORCED to NOT ENFORCED — verify SQL preview shows `ALTER CHECK name NOT ENFORCED`.
6. Delete a constraint — verify SQL preview shows `DROP CHECK name`.
7. Modify an expression — verify SQL preview shows DROP + ADD.
8. Verify a newly created enforced constraint rejects invalid data.
9. On MySQL 8.0.15 and earlier, verify CHECK constraint controls are not shown.
