# MYSQL-OBJ-010: Create and drop views from object navigation

## Fixture

- `init.sql` creates a base table `obj010_base` with employee data, plus two views:
  - `obj010_simple` — simple SELECT view
  - `obj010_aggregate` — GROUP BY aggregate view
- `grants.sql` grants SELECT, INSERT, CREATE VIEW, DROP, ALTER.
- `cleanup.sql` drops all views and the base table.

## Verification

1. Connect with the test user.
2. Right-click the "Views" node in the object tree.
3. Select "Create view" — the view editor opens with empty state.
4. Enter a view name, SQL body, and optional attributes (Algorithm, Definer, SQL Security, Check Option).
5. Preview the SQL and execute.
6. Verify the new view appears in the tree and can be opened.
7. Right-click an existing view and select "Drop view".
8. Confirm the drop — verify the view is removed from the tree.
9. Test create failure: enter invalid SQL, verify error is shown and editor content is preserved.
10. Verify editing an existing view still works without regression.
