# MYSQL-OBJ-014 InnoDB General Tablespaces

Fixture evidence for issue #2582 / PR #2634.

Use only on isolated local MySQL 5.7.6+ or 8.0 test servers. Data-file paths are created by the MySQL server, not by Chat2DB. The fixture uses relative `.ibd` filenames so the server places files under its own configured data directory. Run `cleanup.sql` before deleting any container or volume. MySQL `DROP TABLESPACE` has no `IF EXISTS` form; cleanup assumes the fixture tablespaces were created.

Expected checks:
- MySQL 5.7.6+: create/drop general tablespace, place a new InnoDB table, migrate an existing InnoDB table with `ALTER TABLE ... TABLESPACE`, list data files, and block non-empty drop.
- MySQL 8.0+: the same checks plus `ALTER TABLESPACE ... RENAME TO`.
- Privilege coverage: use `grants.sql` for a least-privilege account with `CREATE TABLESPACE`, `ALTER`, DDL privileges on the fixture schema, and metadata readback privileges.

Run `init.sql`, then use the version-specific verification script:

- MySQL 5.7: `verify-57.sql`
- MySQL 8.0: `verify-80.sql`

MySQL 8.0 object readback queries (`verify-80.sql`):

```sql
SELECT TABLESPACE_NAME, ENGINE, TABLESPACE_TYPE FROM INFORMATION_SCHEMA.TABLESPACES WHERE TABLESPACE_NAME LIKE 'chat2db_ts_%';
SELECT FILE_NAME, TABLESPACE_NAME FROM INFORMATION_SCHEMA.FILES WHERE TABLESPACE_NAME LIKE 'chat2db_ts_%';
```

MySQL 5.7 object and occupancy readback queries (`verify-57.sql`):

```sql
SELECT SPACE, NAME, SPACE_TYPE, PAGE_SIZE
FROM INFORMATION_SCHEMA.INNODB_SYS_TABLESPACES
WHERE SPACE_TYPE = 'General' AND NAME LIKE 'chat2db_ts_%';
SELECT REPLACE(I.NAME, '/', '.') AS OBJECT_NAME
FROM INFORMATION_SCHEMA.INNODB_SYS_TABLES I
JOIN INFORMATION_SCHEMA.INNODB_SYS_TABLESPACES S ON S.SPACE = I.SPACE
WHERE S.NAME LIKE 'chat2db_ts_%';
```

MySQL 8.0 occupancy readback uses `INNODB_TABLES` and `INNODB_TABLESPACES`, as shown in
`verify-80.sql`.

MySQL does not allow partitioned tables in a shared general tablespace, so this
fixture intentionally covers table occupancy only.
