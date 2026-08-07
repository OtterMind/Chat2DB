# MYSQL-OBJ-009: Partition inspection and maintenance

## Fixture

- `init.sql` creates:
  - `obj009_sales_range` — RANGE by year (4 partitions, sample rows in 3 of them)
  - `obj009_regions_list` — LIST COLUMNS (2 partitions)
  - `obj009_events_hash` — HASH (4 partitions)
- `grants.sql` grants ALTER on the database.
- `cleanup.sql` drops test objects and users.

## Verification

1. Connect as `obj009_admin`; right-click `obj009_sales_range` -> Partitions.
2. Verify four partitions are listed with method RANGE, expression YEAR(sale_date),
   boundaries (2024/2025/2026/MAXVALUE), and row counts.
3. TRUNCATE PARTITION `p2023` — verify the destructive warning + preview
   `ALTER TABLE ... TRUNCATE PARTITION \`p2023\``; execute and verify the row count
   for p2023 becomes 0.
4. DROP PARTITION `p2023` — verify the preview and that the partition disappears
   after execution.
5. Open `obj009_regions_list` — verify LIST partitions show DROP/TRUNCATE actions.
6. Open `obj009_events_hash` — verify HASH partitions show the COALESCE count input
   (no DROP/TRUNCATE actions); coalesce 4 -> 2 and verify the partition count via
   information_schema.PARTITIONS.
7. Run ANALYZE/CHECK/OPTIMIZE PARTITION on a RANGE partition — verify each preview and
   that CHECK reports OK.
8. A non-partitioned table shows the "not partitioned" empty state.
