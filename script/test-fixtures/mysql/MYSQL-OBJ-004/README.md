# MYSQL-OBJ-004: Index prefix length management

## Fixture

- `init.sql` creates two tables:
  - `obj004_prefix_test` (InnoDB) with VARCHAR, TEXT, BLOB columns and single-column + composite prefix indexes
  - `obj004_prefix_myisam` (MyISAM) with prefix indexes on VARCHAR and TEXT
- `grants.sql` grants SELECT, INDEX, ALTER
- `cleanup.sql` drops both tables

## Verification

1. Connect with the test user.
2. Open `obj004_prefix_test` in the table editor.
3. Verify the index column list shows prefix lengths (e.g., 20 for `idx_name_prefix`, 50 for `idx_desc_prefix`).
4. Verify composite index `idx_name_code` shows per-column prefix lengths (10, 5).
5. Create a new index with a prefix length — verify the SQL preview includes `column_name(N)`.
6. Change a prefix length — verify the SQL preview shows DROP + ADD with the new prefix.
7. Verify ineligible column types (e.g., INT) reject prefix length input.
8. Verify prefix length reloads accurately after refresh.
9. On non-MySQL databases, verify the prefix length column does not appear.
