# MySQL Account Rename Fixture

These files create isolated accounts and all five MySQL object types whose
`DEFINER` references the source account. They are fixture evidence for manual or
container-backed verification; the unit tests mock JDBC metadata reads.

## Setup

Run as an administrative MySQL test user on MySQL 5.7 or 8.0:

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p < grants.sql
mysql -h 127.0.0.1 -P 3306 -u root -p < init.sql
```

## Expected Preview Evidence

Previewing this rename:

```sql
RENAME USER 'chat2db_rename_old'@'127.0.0.1' TO 'chat2db_rename_new'@'127.0.0.1'
```

must list these visible definer objects before execution:

```text
VIEW chat2db_account_rename_fixture.v_account_rename_source
FUNCTION chat2db_account_rename_fixture.fn_account_rename_source
PROCEDURE chat2db_account_rename_fixture.pr_account_rename_source
TRIGGER chat2db_account_rename_fixture.tr_account_rename_source_bi
EVENT chat2db_account_rename_fixture.ev_account_rename_source
```

The confirmation must warn that `RENAME USER` does not rewrite object
`DEFINER` clauses and does not terminate existing sessions. If the connected
account cannot read one or more `information_schema` metadata tables, the
preview must still show visible objects and mark enumeration incomplete.

## Execution Checks

After executing the rename, verify the account and grants moved:

```sql
SELECT User, Host, plugin, account_locked
FROM mysql.user
WHERE User IN ('chat2db_rename_old', 'chat2db_rename_new')
  AND Host = '127.0.0.1';

SHOW GRANTS FOR 'chat2db_rename_new'@'127.0.0.1';
```

The old account should be absent, the new account should retain grants and
authentication state, and the fixture objects should still report
`chat2db_rename_old@127.0.0.1` as their definer until an administrator rewrites
those objects explicitly.

## Cleanup

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p < cleanup.sql
```
