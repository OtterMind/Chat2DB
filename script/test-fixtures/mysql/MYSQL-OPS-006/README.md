# MYSQL-OPS-006: InnoDB status and latest deadlock diagnostics

## Fixture

- `init.sql` creates:
  - `ops006_admin` — administrator with PROCESS privilege
  - `ops006_test` — InnoDB table with sample data
  - Instructions for producing a deadlock with two concurrent connections
- `grants.sql` grants PROCESS for SHOW ENGINE INNODB STATUS access
- `cleanup.sql` drops test objects and users

## Verification

1. Connect as `ops006_admin`.
2. Open the InnoDB diagnostics view — verify raw SHOW ENGINE INNODB STATUS output is displayed.
3. Verify recognizable sections are present (BUFFER POOL, LOG, TRANSACTIONS, etc.).
4. Navigate between sections.
5. If no deadlock has occurred, verify the view says "No deadlock detected".
6. Produce a deadlock using the concurrent-session instructions in init.sql.
7. Refresh — verify the LATEST DETECTED DEADLOCK section is present.
8. Verify the deadlock summary includes: time, transactions, held/waited locks, SQL, victim.
9. Verify refresh on failure keeps the previous result visible with its timestamp.
10. Verify copy to clipboard works.
11. Verify the page never changes innodb_print_all_deadlocks or another server variable.
