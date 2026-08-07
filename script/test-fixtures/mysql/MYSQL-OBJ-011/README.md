# MYSQL-OBJ-011: Create and drop functions and procedures from object navigation

## Fixture

- `init.sql` creates:
  - Base table `obj011_data` with sample data
  - Same-named function and procedure `obj011_add` (to test type-aware deletion)
  - Function `obj011_compute` with a return value
  - Procedure `obj011_swap` with INOUT parameters
- `grants.sql` grants CREATE ROUTINE, ALTER ROUTINE, DROP
- `cleanup.sql` drops all routines and the base table

## Verification

1. Connect with the test user.
2. Right-click "Functions" node → "Create function" — verify the function editor opens.
3. Enter a function name, parameters, return type, and body.
4. Preview and execute — verify the function appears in the tree.
5. Right-click an existing function → "Drop function" — verify confirmation shows the name.
6. Confirm — verify the function is removed from the tree.
7. Repeat for procedures: Create procedure and Drop procedure.
8. Test same-name case: create both a function and procedure named `test`, then drop only the function — verify the procedure remains.
9. Test creation failure: enter invalid DDL — verify error is shown and draft is preserved.
10. Verify existing function/procedure editing still works without regression.
