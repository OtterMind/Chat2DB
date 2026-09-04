# MYSQL-IMPORT-002: CSV encoding and format options

## Fixture

- `init.sql` creates `import002_items` plus `import002_admin`.
- Byte-accurate CSV fixtures:
  - `fixture_utf8.csv` — UTF-8 with a quoted value containing a comma
  - `fixture_gb18030.csv` — GB18030-encoded Chinese text
  - `fixture_latin1.csv` — ISO-8859-1 (José/café)
  - `fixture_semicolon.csv` — semicolon delimiter with an embedded semicolon in quotes
  - `fixture_tab.csv` — tab delimiter
  - `fixture_no_header.csv` — no header row
  - `fixture_crlf.csv` — CRLF line endings with an embedded newline in a quoted field
  - `fixture_bad_quote.csv` — unclosed quote (must be rejected before any write)

## Verification

1. Connect as `import002_admin`; import `fixture_utf8.csv` — verify the preview shows the
   Chinese value correctly and the quoted value `quoted "value"` stays in one field.
2. Import `fixture_gb18030.csv` with encoding GB18030 — verify no character corruption and
   the row imports as 张伟/中文备注.
3. Import `fixture_latin1.csv` with encoding ISO-8859-1 — verify José/café import correctly.
4. Import `fixture_semicolon.csv` with delimiter `;` — verify the field `hello; world`
   remains a single value.
5. Import `fixture_tab.csv` with delimiter Tab — verify columns split on tabs.
6. Import `fixture_no_header.csv` with header row OFF — verify the first row is treated as
   data (columns column_1..3) and imports.
7. Import `fixture_crlf.csv` — verify the embedded newline stays inside the note field and
   the row imports once.
8. Import `fixture_bad_quote.csv` — verify it is rejected with the unclosed-quote error
   and the source line, and no rows are written.
9. With "Empty field = NULL" OFF, import a row with an empty note — verify the note is an
   empty string; with it ON the note is SQL NULL.
10. Feed a generated large CSV (1M rows) — verify the preview stays bounded and fast.
