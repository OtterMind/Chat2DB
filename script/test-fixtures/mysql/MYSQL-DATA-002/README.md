# MYSQL-DATA-002: JSON invalid-submission protection and scalar/NULL semantics

## Fixture

- `init.sql` creates `obj002_json_test` with 7 JSON columns and 1 TEXT column
- Sample data covers all 6 legal JSON root types: object, array, string, number, boolean, null
- Row 3 has all JSON columns set to SQL NULL
- `grants.sql` grants SELECT, INSERT, UPDATE
- `cleanup.sql` drops the test table

## Verification

1. Connect with the test user.
2. Open the table and view results — verify all 6 JSON root types are displayed correctly.
3. Edit a JSON cell with valid JSON (e.g., `{"key":"value"}`) — verify it saves correctly.
4. Edit a JSON cell with INVALID JSON (e.g., `{key:value}`) — verify the submission is rejected with an error message.
5. Verify JSON `null` (the string "null") is distinct from SQL NULL (empty cell) after save and reload.
6. Verify JSON empty string `""` is distinct from SQL NULL and JSON null.
7. Verify numbers (e.g., `42`) and booleans (e.g., `true`) are not coerced into strings.
8. Verify malformed commas and truncated input are rejected.
9. Verify cancelling an edit leaves the original value unchanged.
10. Verify the error message includes useful context (line/column if available).
11. On databases without native JSON support, verify the text editor is used instead.
