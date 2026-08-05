# MYSQL-OBJ-006: Visible and invisible index management

## Fixture

- `init.sql` creates `obj006_index_test` with primary key, unique, ordinary, and composite indexes.
- On MySQL 8.0+, `idx_name` is made INVISIBLE via `ALTER INDEX`.
- `grants.sql` grants SELECT, INDEX, ALTER to the test user.
- `cleanup.sql` drops the test table.

## Verification

1. Connect with the test user.
2. Open the table in the table editor.
3. Verify the index list shows VISIBLE/INVISIBLE for each index.
4. Toggle `idx_name` from INVISIBLE to VISIBLE and preview the SQL.
5. Execute and refresh — the index should show VISIBLE.
6. Toggle `uk_code` to INVISIBLE and verify the SQL previews correctly.
7. Verify the primary key row does not offer a visibility toggle.
8. On MySQL 5.7, the visibility column should not appear.
