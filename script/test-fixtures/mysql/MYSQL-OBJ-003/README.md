# MYSQL-OBJ-003: Visible and invisible column management

## Fixture

- `init.sql` creates `obj003_column_test` with id, name, secret, and created_at columns.
- On MySQL 8.0.23+, `secret` is made INVISIBLE via `ALTER TABLE ... MODIFY COLUMN ... INVISIBLE`.
- `grants.sql` grants SELECT, INSERT, UPDATE, ALTER to the test user.
- `cleanup.sql` drops the test table.

## Verification

1. Connect with the test user.
2. Open the table in the table editor.
3. Verify the column list shows VISIBLE/INVISIBLE for each column.
4. The `secret` column should show INVISIBLE.
5. Toggle `secret` from INVISIBLE to VISIBLE and preview the SQL.
6. Execute and refresh — the column should show VISIBLE.
7. Create a new invisible column and verify the SQL includes INVISIBLE.
8. Verify `SELECT *` omits invisible columns (explained in UI tooltip).
9. Explicitly querying an invisible column by name still works.
10. On MySQL 5.7 and 8.0.22 or earlier, the visibility control should not appear.
