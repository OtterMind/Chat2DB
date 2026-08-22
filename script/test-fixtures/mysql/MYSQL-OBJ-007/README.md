# MYSQL-OBJ-007: Foreign key management

## Fixture

- `init.sql` creates 4 tables with different FK patterns:
  - `obj007_departments` — parent table
  - `obj007_employees` — single-column FK with CASCADE DELETE, RESTRICT UPDATE
  - `obj007_project_members` — FK with SET NULL DELETE, CASCADE UPDATE
  - `obj007_categories` — self-referencing FK with SET NULL DELETE, NO ACTION UPDATE
- `grants.sql` grants ALTER, INDEX, REFERENCES
- `cleanup.sql` drops all tables in reverse dependency order

## Verification

1. Connect with the test user.
2. Open `obj007_employees` in the table editor.
3. Verify the FK section shows `fk_emp_dept` with correct columns and actions.
4. Add a new FK to `obj007_employees` — verify SQL preview includes `ADD CONSTRAINT ... FOREIGN KEY ... REFERENCES ...`.
5. Modify the FK action from CASCADE to SET NULL — verify SQL preview shows DROP + ADD.
6. Delete the FK — verify SQL preview shows `DROP FOREIGN KEY fk_emp_dept`.
7. Open `obj007_categories` — verify self-referencing FK displays correctly.
8. Verify CASCADE is clearly identified as propagating data changes.
9. Test invalid FK (wrong column type) — verify actionable error.
10. Refresh after changes — verify FK state matches MySQL.
