-- MYSQL-OPS-002: Grants for test user
-- ops002_admin has PROCESS for full transaction visibility (all users + SQL text).
GRANT PROCESS ON *.* TO 'ops002_admin'@'%';
-- ops002_user intentionally has NO PROCESS: transactions appear with NULL query text.
FLUSH PRIVILEGES;
