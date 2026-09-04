# MYSQL-IMPORT-001: Import preview and column mapping

## Fixture

- `init.sql` creates `import001_contacts` (with defaults) and `import001_strict`
  (NOT NULL `code` without a default) plus `import001_admin`.
- `fixture_contacts.csv` — 3 rows matching the target columns by name.
- `fixture_reordered.csv` — reordered columns (age,name,email).
- `fixture_extra.csv` — an extra source column that must be skippable.
- For XLS/XLSX, convert any fixture CSV with the same header/rows (a deterministic
  large-file generator may produce a 1M-row CSV to verify the bounded preview).

## Verification

1. Connect as `import001_admin`; right-click `import001_contacts` -> Import Data,
   choose `fixture_contacts.csv`.
2. Verify the preview shows the three source fields with sample values and an automatic
   mapping name->name, email->email, age->age (id unmapped, auto-increment).
3. Execute — verify the summary reports 3 rows imported, 0 failed, and the rows are
   readable from the table (note column uses its DEFAULT 'imported').
4. Import `fixture_reordered.csv` — verify the auto mapping is still correct by name
   (order does not matter), and rows import with age 28.
5. Import `fixture_extra.csv` — map the extra_column to "Skip field"; execute; verify the
   row imports and the extra column is ignored.
6. Import a file with an unmapped value for `code` into `import001_strict` — verify
   execution is blocked before any write (NOT NULL without default and strategy NULL)
   and no partial rows are inserted.
7. Set unmapped strategy to DEFAULT on `import001_contacts` for a file missing `note` —
   verify the note default is applied; with NULL strategy verify `note` becomes NULL.
8. Feed a file with an invalid numeric `age` — verify the row is reported as failed with
   the source row number and the target column, while valid rows still import.
9. Feed a large CSV (generated) — verify only the bounded preview rows are shown and the
   preview stays fast.
