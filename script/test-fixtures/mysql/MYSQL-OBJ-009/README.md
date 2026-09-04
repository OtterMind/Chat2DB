# MYSQL-OBJ-009: Partition inspection and maintenance

## Fixture

- `init.sql` creates:
  - `obj009_sales_range` — RANGE by year (4 partitions, sample rows in 3 of them)
  - `obj009_sales_range_columns` — RANGE COLUMNS with multicolumn boundaries
  - `obj009_regions_list_int` — LIST
  - `obj009_regions_list` — LIST COLUMNS
  - `obj009_events_hash` — HASH
  - `obj009_events_linear_hash` — LINEAR HASH
  - `obj009_events_key` — KEY
  - `obj009_events_linear_key` — LINEAR KEY
  - `obj009_plain_table` — non-partitioned empty-state table
  - commented unique-key and foreign-key conflict statements for actionable-error checks
- `grants.sql` grants ALTER on the database.
- `cleanup.sql` drops test objects and users.

## Verification

1. Connect as `obj009_admin`; right-click `obj009_sales_range` -> Partitions.
2. Verify four partitions are listed with method RANGE, expression YEAR(sale_date),
   boundaries (2024/2025/2026/MAXVALUE), and row counts.
3. ADD PARTITION on `obj009_regions_list` — enter partition `p_north` and definition
   `VALUES IN ('NORTH')`; verify the preview, execute it, and confirm the partition
   appears after reload. Direct ADD on the RANGE fixture is rejected because its
   `MAXVALUE` partition must be split with REORGANIZE PARTITION.
4. REORGANIZE PARTITION `p_future` — enter replacement definitions
   `PARTITION p2027 VALUES LESS THAN (2028), PARTITION p_future VALUES LESS THAN MAXVALUE`;
   verify preview + reload.
5. TRUNCATE PARTITION `p2023` — verify the destructive warning + preview
   `ALTER TABLE ... TRUNCATE PARTITION \`p2023\``; execute and verify the row count
   for p2023 becomes 0.
6. DROP PARTITION `p2023` — verify the typed-name confirmation, preview, and that the
   partition disappears after execution.
7. Open `obj009_regions_list` and `obj009_regions_list_int` — verify LIST partitions
   show ADD/REORGANIZE/DROP/TRUNCATE actions.
8. Open the HASH/KEY tables — verify ADD count + COALESCE count inputs are shown
   and DROP/TRUNCATE/REORGANIZE actions are hidden; add or coalesce partitions and
   verify the partition count via information_schema.PARTITIONS.
9. Run ANALYZE/CHECK/OPTIMIZE PARTITION on a RANGE partition — verify each preview and
   that CHECK reports OK.
10. `obj009_plain_table` shows the "not partitioned" empty state.
11. Run the commented conflict statements from `init.sql` manually when validating
    MySQL error messaging for unique-key and foreign-key partition constraints.
