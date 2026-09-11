# MYSQL-IMPORT-001: Import preview and column mapping

## Fixture

- `init.sql` creates `import001_contacts` (with defaults) and `import001_strict`
  (NOT NULL `code` without a default) plus `import001_admin`.
- `fixture_contacts.csv` — 3 rows matching the target columns by name.
- `fixture_reordered.csv` — reordered columns (age,name,email).
- `fixture_extra.csv` — an extra source column that must be skippable.
- `fixture_missing_required.csv` — no field matching the required `code` column.
- For XLS/XLSX, convert any fixture CSV with the same header/rows (a deterministic
  large-file generator may produce a 1M-row CSV to verify the bounded preview).

## Verification

1. Connect as `import001_admin`; right-click `import001_contacts` -> Import Data,
   choose `fixture_contacts.csv`.
2. Verify the preview shows ten data rows at most and automatically maps name->name,
   email->email, age->age (id unmapped, auto-increment). Target options show column comments.
3. Execute — verify the summary reports 3 rows imported, 0 failed, and the rows are
   readable from the table (note column uses its DEFAULT 'imported').
4. Import `fixture_reordered.csv` — verify the auto mapping is still correct by name
   (order does not matter), and rows import with age 28.
5. Import `fixture_extra.csv` — verify extra_column is automatically set to "Skip field";
   execute; verify the row imports and the extra column is ignored.
6. Import `fixture_missing_required.csv` into `import001_strict` — verify the UI blocks
   submission because `code` has no mapping. If the API is called directly, verify the task
   fails and its transaction leaves no partial rows.
7. Set unmapped strategy to DEFAULT on `import001_contacts` for a file missing `note` —
   verify the note default is applied; with NULL strategy verify `note` becomes NULL.
8. Feed a file with an invalid numeric `age` — verify the task fails, the batch error is
   recorded, and the file-level transaction rolls back all rows.
9. Feed a large CSV (generated) — verify only the bounded preview rows are shown and the
   preview stays fast.
