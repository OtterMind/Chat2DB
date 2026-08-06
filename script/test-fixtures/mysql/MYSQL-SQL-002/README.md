# MYSQL-SQL-002: Structured EXPLAIN JSON and EXPLAIN ANALYZE

## Fixture

- `init.sql` creates two tables with indexes and sample data:
  - `obj002_users` with PK, email index, dept index
  - `obj002_orders` with PK, user_id index, status index
- `grants.sql` grants SELECT for EXPLAIN
- `cleanup.sql` drops both tables

## Verification

1. Connect with the test user.
2. Run `EXPLAIN FORMAT=JSON SELECT * FROM obj002_users WHERE id = 1` — verify JSON output is returned.
3. Switch between tabular EXPLAIN, JSON tree, and raw JSON views.
4. Run a full scan query — verify the plan shows a table scan.
5. Run an index lookup — verify the plan shows index access.
6. Run a join query — verify the plan shows join node type.
7. On MySQL 8.0.18+, run `EXPLAIN ANALYZE SELECT ...` — verify estimated vs actual rows, loops, timing.
8. On MySQL 5.7, verify ANALYZE is hidden/disabled but FORMAT=JSON works.
9. Verify cancellation interrupts a running ANALYZE statement.
10. Verify syntax errors produce clear error messages.
