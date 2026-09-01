# MYSQL-OBJ-005: Functional index management

## Fixture

- `init.sql` creates `obj005_func_test` with:
  - Functional index `idx_lower_name` on `((LOWER(name)))`
  - Functional index `idx_lower_email` on `((LOWER(email)))`
  - Mixed index `idx_mixed` on `((LOWER(name)), email)` (expression + physical column)
  - Functional index `idx_year_created` on `((YEAR(created_at)))`
- `grants.sql` grants SELECT, INDEX, ALTER
- `cleanup.sql` drops the test table

## Verification

1. Connect with the test user (MySQL 8.0.13+).
2. Open `obj005_func_test` in the table editor.
3. Verify the index column list shows expression entries (e.g., `((lower(`name`)))`).
4. Verify mixed index `idx_mixed` shows both expression and physical column entries.
5. Create a new functional index — verify SQL preview includes `((expression))` syntax.
6. Modify an expression — verify SQL preview shows DROP + ADD with double-parenthesized syntax.
7. Delete a functional index — verify SQL shows DROP INDEX.
8. Verify expression entries reload accurately after refresh.
9. On MySQL 5.7, verify functional index options do not appear.
10. Verify hidden generated columns are not shown as editable normal columns.
