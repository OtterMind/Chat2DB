# MYSQL-OPS-003: Data locks, metadata locks, and blocking chains

## Fixture

- `init.sql` creates `ops003_admin` and `ops003_user`, plus `ops003_ledger`
  with a unique key for row-lock and gap-lock wait chains.
- `grants.sql` grants PROCESS plus read access required by Performance Schema and
  the cross-version `sys.schema_table_lock_waits` view.
- `cleanup.sql` drops test objects and users.

## Verification

1. Connect as `ops003_admin` and open the Lock Waits view (right-click the datasource node).
2. **Two-level chain**: open connection B, run
   `START TRANSACTION; UPDATE ops003_ledger SET amount = amount - 10 WHERE account = 'alice';`
   and leave it open. Open connection C and run the same UPDATE on `alice` — it blocks.
   Refresh the view — verify the blocking chain shows C waiting on B, and B is the root blocker.
3. **Three-level chain**: with B holding `alice`, have C block on `alice`; then open
   connection D and UPDATE `bob` — D waits on C only if C holds `bob` first. Construct the
   chain B(holds alice) -> C(waits alice, holds bob) -> D(waits bob) and verify the view
   lists both waits with the correct direction and B as the root blocker.
4. **Metadata lock**: on connection E run
   `START TRANSACTION; SELECT * FROM ops003_ledger WHERE id = 1 FOR UPDATE;` then on
   connection F run `ALTER TABLE ops003_ledger ADD COLUMN note VARCHAR(32) NULL;` — F waits
   for the metadata lock. Refresh — verify the pending row appears in Metadata Locks and
   Metadata Blocking Chains links F to E with E as root blocker. No self-blocking edge
   should be shown.
5. **Release**: commit B — refresh — verify the chains disappear.
6. Connect as `ops003_user` (no PROCESS) — refresh — verify the view still loads and
   other sessions' locks are not visible (no crash, explicit empty/unavailable state).
7. Verify the view never offers a kill action (termination belongs to the session view).
8. Start an unrelated sleeping session and verify it is not returned in the Sessions tab.
