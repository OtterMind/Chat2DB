-- MYSQL-OPS-002: Grants for test user
-- ops002_admin has PROCESS for full transaction visibility (all users + SQL text).
GRANT PROCESS ON *.* TO 'ops002_admin'@'%';
GRANT SELECT ON `performance_schema`.`data_lock_waits` TO 'ops002_admin'@'%';
GRANT SELECT ON `performance_schema`.`data_locks` TO 'ops002_admin'@'%';
-- ops002_user intentionally has NO PROCESS: cross-user transaction/process details can
-- be hidden, returned with NULL SQL text, or rejected by MySQL privilege checks.
FLUSH PRIVILEGES;
