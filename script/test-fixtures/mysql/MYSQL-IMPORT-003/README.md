# MYSQL-IMPORT-003: Excel sheet/header/NULL options

## Fixture

- `init.sql` creates `import003_records` plus `import003_admin`.
- Workbooks (deterministic, generated with openpyxl / Apache POI):
  - `fixture_multi_sheet.xlsx` — visible "Records" sheet with typed cells (big number,
    boolean, date, empty cell, cached formula =B5+B6), an "WithIntro" sheet with two
    introductory rows before the header, and a hidden "HiddenData" sheet.
  - `fixture_no_header.xlsx` — no header row.
  - `fixture_typed.xls` — XLS with numeric, boolean, and date cells.

## Verification

1. Connect as `import003_admin`; import `fixture_multi_sheet.xlsx`.
2. Verify the sheet selector lists Records and WithIntro but not HiddenData; select Records.
3. Verify typed previews: the big number stays a number, booleans show `true`/`false`
   with a [boolean] marker, the date cell shows a date, and the formula cell shows the
   cached result (30) with a [formula] marker — no formula is evaluated.
4. Import — verify rows land with the correct values (active 1/0, date, amount).
5. Switch to the WithIntro sheet with start row 2 and header row 3 — verify the first
   two rows are skipped and the header names feed the mapping.
6. With "Empty field = NULL" ON, an empty note cell imports as NULL; OFF imports as ''.
7. Import `fixture_no_header.xlsx` with header row 0 — verify the first row is data
   (columns column_1..N).
8. Import `fixture_typed.xls` — verify XLS works with typed cells.
9. Feed a damaged workbook — verify it fails before any write with the read error.
10. Verify the hidden sheet cannot be imported when another sheet is available.
