-- MYSQL-OPS-003: Grants for test user
-- PROCESS is needed to read innodb_lock_waits/innodb_locks rows of other sessions.
GRANT PROCESS ON *.* TO 'ops003_admin'@'%';
FLUSH PRIVILEGES;
