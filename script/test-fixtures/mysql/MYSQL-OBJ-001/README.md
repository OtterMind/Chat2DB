# MYSQL-OBJ-001: Database and table default character set and collation

## Fixture

- `init.sql` creates `obj001_test` (utf8mb4 / utf8mb4_unicode_ci) with a table
  `obj001_contacts` and `obj001_admin`.
- `grants.sql` grants ALTER on the database and tables.
- `cleanup.sql` drops test objects and users.

## Verification

1. Connect as `obj001_admin`.
2. Right-click the `obj001_test` database node -> Database Properties.
3. Verify the loaded character set is utf8mb4 and collation utf8mb4_unicode_ci.
4. Change the collation to utf8mb4_general_ci — verify the collation list is filtered
   by the selected character set.
5. Save — verify the preview `ALTER DATABASE \`obj001_test\` DEFAULT COLLATE utf8mb4_general_ci`
   is shown; execute and reopen — verify the value is reloaded from
   information_schema.schemata.
6. Save again without changes — verify no DDL is executed ("no changes to save").
7. Open `obj001_contacts` in the table editor — verify the new Collation field shows
   utf8mb4_unicode_ci alongside the character set.
8. Change the table collation, preview `ALTER TABLE ... COLLATE ...`, execute, and
   verify the reloaded value in information_schema.tables.
9. Verify the UI states that changing defaults does not convert existing column data.
10. Restore the original collations afterwards.
