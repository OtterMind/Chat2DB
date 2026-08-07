# MYSQL-OBJ-013: Event lifecycle management

## Fixture

- `init.sql` creates `obj013_test` with `obj013_jobs`, a disabled recurring event
  (`obj013_cleanup_event`) and an enabled one-time event (`obj013_one_shot`).
- `grants.sql` grants EVENT on the database to `obj013_admin`.
- `cleanup.sql` drops test objects and users.

## Verification

1. Connect as `obj013_admin`; expand the database node — verify the Events group shows
   both events with their names.
2. Right-click the Events group -> Create event — verify a console opens with a
   CREATE EVENT template.
3. Right-click `obj013_cleanup_event` -> Open event — verify a console opens with the
   SHOW CREATE EVENT statement.
4. Right-click `obj013_cleanup_event` -> Enable event — verify the ALTER EVENT ...
   ENABLE preview is shown, execute, and the tree refreshes.
5. Right-click `obj013_one_shot` -> Disable event — verify the ALTER EVENT ... DISABLE
   preview, execute, and refresh.
6. Right-click `obj013_one_shot` -> Drop event — verify dropping requires typing the
   event name and the DROP EVENT ... preview; after execution the node disappears.
7. When the global event_scheduler is OFF (SET GLOBAL event_scheduler = OFF on a
   disposable instance), verify the created events still list correctly — the view
   reports them as created, and the scheduler state is surfaced (SHOW VARIABLES).
8. Re-enable the scheduler afterwards (SET GLOBAL event_scheduler = ON).
