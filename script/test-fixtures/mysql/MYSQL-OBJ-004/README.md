# MYSQL-OBJ-004: Index prefix length management

## Fixture

- `init.sql` creates three tables:
  - `obj004_prefix_test` (InnoDB) with VARCHAR, TEXT, BLOB columns and single-column + composite prefix indexes
  - `obj004_prefix_myisam` (MyISAM) with prefix indexes on VARCHAR and TEXT
  - `obj004_prefix_compact` (InnoDB COMPACT) with a utf8mb4 prefix at the 767-byte boundary
- `grants.sql` grants SELECT, INDEX, ALTER
- `cleanup.sql` drops all fixture tables

## Verification

1. Connect with the test user.
2. Open `obj004_prefix_test` in the table editor.
3. Verify the index column list shows prefix lengths (e.g., 20 for `idx_name_prefix`, 50 for `idx_desc_prefix`).
4. Verify composite index `idx_name_code` shows per-column prefix lengths (10, 5).
5. Verify binary prefix index `idx_data_prefix` reloads with prefix length 32.
6. Create a new index with a prefix length — verify the SQL preview includes `column_name(N)`.
7. Change a prefix length — verify the SQL preview shows DROP + ADD with the new prefix and a rebuild warning.
8. Verify ineligible column types (e.g., INT), zero or negative lengths, and lengths above the column size reject before execution.
9. Open `obj004_prefix_compact`; changing `idx_name_compact` above 191 characters should be rejected because utf8mb4 exceeds the 767-byte InnoDB COMPACT limit.
10. On `obj004_prefix_myisam`, composite prefixes whose utf8mb4 byte total exceeds 1000 bytes should be rejected before execution.
11. Refresh the table and verify prefix lengths reload accurately after create, modify, and delete.
12. On non-MySQL databases, verify the prefix length column does not appear.
