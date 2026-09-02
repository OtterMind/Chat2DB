# MYSQL-OBJ-012: Trigger create, modify, and delete with recovery

## Fixture

- `init.sql` creates:
  - `obj012_audit` — audit log table
  - `obj012_data` — main data table
  - 6 triggers covering all timing/event combinations:
    - `obj012_bi` — BEFORE INSERT (increments value)
    - `obj012_ai` — AFTER INSERT (audit log)
    - `obj012_bu` — BEFORE UPDATE (uppercases name)
    - `obj012_au` — AFTER UPDATE (audit log)
    - `obj012_bd` — BEFORE DELETE (audit log)
    - `obj012_ad` — AFTER DELETE (placeholder)
- `grants.sql` grants TRIGGER, ALTER, etc.
- `cleanup.sql` drops all triggers and tables

## Verification

1. Connect with the test user.
2. Right-click "Triggers" node → "Create trigger" — verify the trigger editor opens.
3. Enter trigger name, timing (BEFORE/AFTER), event (INSERT/UPDATE/DELETE), body.
4. Preview and execute — verify the trigger appears in the tree.
5. Right-click an existing trigger → "Drop trigger" — verify confirmation shows the name.
6. Confirm — verify the trigger is removed from the tree.
7. Insert into `obj012_data` — verify BEFORE INSERT trigger increments value.
8. Verify AFTER INSERT trigger logs to audit table.
9. Update a row — verify BEFORE UPDATE uppercases name, AFTER UPDATE logs.
10. Delete a row — verify BEFORE DELETE trigger logs.
11. On MySQL 5.7, verify FOLLOWS/PRECEDES ordering is not shown.
