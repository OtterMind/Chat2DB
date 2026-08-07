# MYSQL-OPS-005: Table maintenance operations

## Fixture

- `init.sql` creates two tables:
  - `ops005_innodb` — InnoDB engine table with an index
  - `ops005_myisam` — MyISAM engine table with an index
- `grants.sql` grants maintenance privileges.
- `cleanup.sql` drops both tables.

## Verification

1. Connect with the test user.
2. Right-click the `ops005_innodb` table in the object tree.
3. Select "Analyze table" — verify SQL preview shows `ANALYZE TABLE ops005_innodb`.
4. Execute — verify the result shows Table, Op, Msg_type, Msg_text columns.
5. Select "Optimize table" — verify the SQL preview includes InnoDB rebuild warning.
6. Select "Check table" — verify the result shows status.
7. Right-click the `ops005_myisam` table.
8. Select "Repair table" — verify it's available for MyISAM.
9. Go back to `ops005_innodb` — verify "Repair table" is NOT available for InnoDB.
10. Verify long-running operations can be cancelled through the task flow.
