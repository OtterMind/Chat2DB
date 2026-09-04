# MYSQL-OBJ-002: Generated column management

## Fixture

- `init.sql` creates `obj002_products` with a STORED generated column
  (`price_with_tax`) and a VIRTUAL generated column (`name_upper`) plus `obj002_admin`.
- `grants.sql` grants ALTER/SELECT/INSERT/UPDATE.
- `cleanup.sql` drops test objects and users.

## Verification

1. Connect as `obj002_admin`; open `obj002_products` in the table editor.
2. Verify the generated columns are loaded: `price_with_tax` shows the expression
   `price * (1 + tax_rate)` with type STORED, and `name_upper` shows `UPPER(name)`
   with type VIRTUAL.
3. Add a new generated column `discounted` of type DECIMAL(10,2) with expression
   `price * 0.9` and type VIRTUAL — verify the preview contains
   `GENERATED ALWAYS AS (price * 0.9) VIRTUAL` and executes.
4. Verify the preview for generated columns does NOT include DEFAULT or
   AUTO_INCREMENT clauses.
5. Modify the expression of `name_upper` to `LOWER(name)` — verify the CHANGE COLUMN
   preview and reload shows the updated expression.
6. Add a VIRTUAL generated column with a UNIQUE index requirement on MySQL 8.0 — verify
   the server error for unsupported constraints is surfaced (generated columns support
   only UNIQUE indexes, not foreign keys).
7. Change `discounted` between VIRTUAL and STORED — verify preview is rejected until
   the rebuild confirmation is accepted, then uses one `DROP COLUMN` + `ADD COLUMN`
   statement and reloads with the requested storage type.
8. Drop the `discounted` column — verify it is removed and reloads correctly.
